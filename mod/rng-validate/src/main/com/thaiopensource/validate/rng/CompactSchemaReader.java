package com.thaiopensource.validate.rng;

import com.thaiopensource.validate.rng.impl.SchemaReaderImpl;
import com.thaiopensource.relaxng.parse.Parseable;
import com.thaiopensource.relaxng.parse.compact.CompactParseable;
import com.thaiopensource.relaxng.parse.compact.UriOpenerImpl;
import com.thaiopensource.validate.SchemaReader;
import com.thaiopensource.xml.sax.BasicResolver;
import org.xml.sax.ErrorHandler;

import javax.xml.transform.sax.SAXSource;

public class CompactSchemaReader extends SchemaReaderImpl {
  private static final SchemaReader theInstance = new CompactSchemaReader();

  private CompactSchemaReader() {
  }

  public static SchemaReader getInstance() {
    return theInstance;
  }

  protected Parseable createParseable(SAXSource source, BasicResolver resolver, ErrorHandler eh) {
    return new CompactParseable(source.getInputSource(), new UriOpenerImpl(resolver), eh);
  }
}
