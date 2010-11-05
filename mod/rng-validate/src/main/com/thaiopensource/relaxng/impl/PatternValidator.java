package com.thaiopensource.relaxng.impl;

import com.thaiopensource.relaxng.exceptions.BadAttributeValueException;
import com.thaiopensource.relaxng.exceptions.ImpossibleAttributeIgnoredException;
import com.thaiopensource.relaxng.exceptions.OnlyTextNotAllowedException;
import com.thaiopensource.relaxng.exceptions.OutOfContextElementException;
import com.thaiopensource.relaxng.exceptions.RequiredAttributesMissingException;
import com.thaiopensource.relaxng.exceptions.RequiredElementsMissingException;
import com.thaiopensource.relaxng.exceptions.StringNotAllowedException;
import com.thaiopensource.relaxng.exceptions.TextNotAllowedException;
import com.thaiopensource.relaxng.exceptions.UnfinishedElementException;
import com.thaiopensource.relaxng.exceptions.UnknownElementException;
import com.thaiopensource.relaxng.parse.sax.DtdContext;
import com.thaiopensource.validate.Validator;
import com.thaiopensource.xml.util.Name;
import com.thaiopensource.xml.util.WellKnownNamespaces;

import org.relaxng.datatype.DatatypeException;
import org.relaxng.datatype.ValidationContext2;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import java.util.HashMap;
import java.util.Map;

public class PatternValidator extends DtdContext implements Validator, ContentHandler, DTDHandler, ValidationContext2 {
  private final ValidatorPatternBuilder builder;
  private final Pattern start;
  private final ErrorHandler eh;
  private Map recoverPatternTable;
  private PatternMemo memo;
  private boolean hadError;
  private boolean collectingCharacters;
  private final StringBuffer charBuf = new StringBuffer();
  private PrefixMapping prefixMapping = new PrefixMapping("xml", WellKnownNamespaces.XML, null);
  private Locator locator;
  private final Map datatypeErrors = new HashMap();
  private Name[] stack = null;
  private int stackLen = 0;
  private int suppressDepth = 0;

  private static final class PrefixMapping {
    private final String prefix;
    private final String namespaceURI;
    private final PrefixMapping previous;

    PrefixMapping(String prefix, String namespaceURI, PrefixMapping prev) {
      this.prefix = prefix;
      this.namespaceURI = namespaceURI;
      this.previous = prev;
    }

    PrefixMapping getPrevious() {
      return previous;
    }
  }

  private void startCollectingCharacters() {
    if (!collectingCharacters) {
      collectingCharacters = true;
      charBuf.setLength(0);
    }
  }

  private void flushCharacters() throws SAXException {
    collectingCharacters = false;
    int len = charBuf.length();
    for (int i = 0; i < len; i++) {
      switch (charBuf.charAt(i)) {
      case ' ':
      case '\r':
      case '\t':
      case '\n':
	break;
      default:
	text();
	return;
      }
    }
  }

  public void startElement(String namespaceURI,
			   String localName,
			   String qName,
			   Attributes atts) throws SAXException {
    if (collectingCharacters)
      flushCharacters();
    if (suppressDepth > 0) {
      suppressDepth++;
    }
    
    Name name = new Name(namespaceURI, localName);
    if (!setMemo(memo.startTagOpenDeriv(name))) {
      PatternMemo next = memo.startTagOpenRecoverDeriv(name);
      if (!next.isNotAllowed())
        error(new RequiredElementsMissingException(locator, name, peek()));
      else {
        next = builder.getPatternMemo(builder.makeAfter(findElement(name),
            memo.getPattern()));
        if (next.isNotAllowed()) {
          error(new UnknownElementException(locator, name, peek()));
        }
        else {
          error(new OutOfContextElementException(locator, name, peek()));
        }
        if (suppressDepth == 0) {
          suppressDepth = 1;
        }
      }
      memo = next;
    }
    int len = atts.getLength();
    for (int i = 0; i < len; i++) {
      Name attName = new Name(atts.getURI(i), atts.getLocalName(i));
      String value = atts.getValue(i);
      datatypeErrors.clear();

      if (!setMemo(memo.startAttributeDeriv(attName)))
        error(new ImpossibleAttributeIgnoredException(locator, name, peek(),
            attName));
      else if (!setMemo(memo.dataDeriv(value, this))) {
        error(new BadAttributeValueException(locator, name, peek(), attName,
            value, datatypeErrors));
        memo = memo.recoverAfter();
      }
    }
    if (!setMemo(memo.endAttributes())) {
      // XXX should specify which attributes
      error(new RequiredAttributesMissingException(locator, name, peek()));
      memo = memo.ignoreMissingAttributes();
    }
    if (memo.getPattern().getContentType() == Pattern.DATA_CONTENT_TYPE)
      startCollectingCharacters();
    push(name);
  }

  private PatternMemo fixAfter(PatternMemo p) {
    return builder.getPatternMemo(p.getPattern().applyForPattern(new ApplyAfterFunction(builder) {
      Pattern apply(Pattern p) {
        return builder.makeEmpty();
      }
    }));
  }

  public void endElement(String namespaceURI,
			 String localName,
			 String qName) throws SAXException {
    Name name = pop();
    // The tricky thing here is that the derivative that we compute may be notAllowed simply because the parent
    // is notAllowed; we don't want to give an error in this case.
    if (collectingCharacters) {
      collectingCharacters = false;
      if (!setMemo(memo.textOnly())) {
        error(new OnlyTextNotAllowedException(locator, name, peek()));
	memo = memo.recoverAfter();
	return;
      }
      final String data = charBuf.toString();
      if (!setMemo(memo.dataDeriv(data, this))) {
        PatternMemo next = memo.recoverAfter();
        datatypeErrors.clear();
        if (!memo.isNotAllowed()) {
          if (!next.isNotAllowed()
              || fixAfter(memo).dataDeriv(data, this).isNotAllowed())
            error(new StringNotAllowedException(locator, name, peek(), data, datatypeErrors));
        }
        memo = next;
      }
    }
    else if (!setMemo(memo.endTagDeriv())) {
      PatternMemo next = memo.recoverAfter();
      if (!memo.isNotAllowed()) {
        if (!next.isNotAllowed()
            || fixAfter(memo).endTagDeriv().isNotAllowed())
          error(new UnfinishedElementException(locator, name, peek()));
      }
      memo = next;
    }
    if (suppressDepth > 0) {
      suppressDepth--;
    }
  }

  public void characters(char ch[], int start, int length) throws SAXException {
    if (collectingCharacters) {
      charBuf.append(ch, start, length);
      return;
    }
    for (int i = 0; i < length; i++) {
      switch (ch[start + i]) {
      case ' ':
      case '\r':
      case '\t':
      case '\n':
	break;
      default:
	text();
	return;
      }
    }
  }

  private void text() throws SAXException {
    if (!setMemo(memo.mixedTextDeriv()))
      error(new TextNotAllowedException(locator, peek()));
  }

  public void endDocument() {
    // XXX maybe check that memo.isNullable if !hadError
  }

  public void setDocumentLocator(Locator loc) {
    locator = loc;
  }

  public void startDocument() throws SAXException {
    stack = new Name[48];
    stackLen = 0;
    suppressDepth = 0;
    if (memo.isNotAllowed())
      error("schema_allows_nothing");
  }
  public void processingInstruction(String target, String date) { }
  public void skippedEntity(String name) { }
  public void ignorableWhitespace(char[] ch, int start, int len) { }
  public void startPrefixMapping(String prefix, String uri) {
    prefixMapping = new PrefixMapping(prefix, uri, prefixMapping);
  }
  public void endPrefixMapping(String prefix) {
    prefixMapping = prefixMapping.getPrevious();
  }

  public PatternValidator(Pattern pattern, ValidatorPatternBuilder builder, ErrorHandler eh) {
    this.start = pattern;
    this.builder = builder;
    this.eh = eh;
    reset();
  }

  public void reset() {
    hadError = false;
    collectingCharacters = false;
    locator = null;
    memo = builder.getPatternMemo(start);
    prefixMapping = new PrefixMapping("xml", WellKnownNamespaces.XML, null);
    clearDtdContext();
    charBuf.setLength(0);
    datatypeErrors.clear();
    stack = null;
    recoverPatternTable = null;
  }

  public ContentHandler getContentHandler() {
    return this;
  }

  public DTDHandler getDTDHandler() {
    return this;
  }

  private void error(String key) throws SAXException {
    if ((suppressDepth > 0) || (hadError && memo.isNotAllowed()))
      return;
    hadError = true;
    eh.error(new SAXParseException(SchemaBuilderImpl.localizer.message(key), locator));
  }

  private void error(SAXParseException e) throws SAXException {
    if ((suppressDepth > 0) || (hadError && memo.isNotAllowed()))
      return;
    hadError = true;
    eh.error(e);
  }

  /* Return false if m is notAllowed. */
  private boolean setMemo(PatternMemo m) {
    if (m.isNotAllowed())
      return false;
    else {
      memo = m;
      return true;
    }
  }

  private Pattern findElement(Name name) {
    if (recoverPatternTable == null)
      recoverPatternTable = new HashMap();
    Pattern p = (Pattern)recoverPatternTable.get(name);
    if (p == null) {
      p = FindElementFunction.findElement(builder, name, start);
      recoverPatternTable.put(name, p);
    }
    return p;
  }

  public String resolveNamespacePrefix(String prefix) {
    PrefixMapping tem = prefixMapping;
    do {
      if (tem.prefix.equals(prefix))
        return tem.namespaceURI;
      tem = tem.previous;
    } while (tem != null);
    return null;
  }

  public String getBaseUri() {
    return null;
  }
  
  public final void addDatatypeError(String message, DatatypeException exception) {
    datatypeErrors.put(message, exception);
  }
  
  private final void push(Name name) {
      if (stackLen == stack.length) {
          Name[] newStack = new Name[stackLen + (stackLen >> 1)];
          System.arraycopy(stack, 0, newStack, 0, stackLen);
          stack = newStack;
      }
      stack[stackLen] = name;
      stackLen++;
  }
  
  private final Name pop() {
      stackLen--;
      return stack[stackLen];
  }
  
  private final Name peek() {
      if (stackLen == 0) {
          return null;
      } else {
          return stack[stackLen - 1];
      }
  }

  public Locator getLocator() {
    return locator;
  }
}