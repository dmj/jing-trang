package com.thaiopensource.xml.dtd;

public class EnumGroup {
  private EnumGroupMember[] members;

  public EnumGroup(EnumGroupMember[] members) {
    this.members = members;
  }

  public EnumGroupMember[] getMembers() {
    EnumGroupMember[] tem = new EnumGroupMember[members.length];
    System.arraycopy(members, 0, tem, 0, members.length);
    return tem;
  }
  
  public void accept(EnumGroupVisitor visitor) throws VisitException {
    for (int i = 0; i < members.length; i++)
      members[i].accept(visitor);
  }
}
