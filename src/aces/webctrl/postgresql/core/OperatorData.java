package aces.webctrl.postgresql.core;
import com.controlj.green.common.CJDataValueException;
import com.controlj.green.core.data.*;
public class OperatorData {
  public volatile String username;
  public volatile String display_name;
  public volatile String password;
  public volatile int lvl5_auto_logout;
  public volatile boolean lvl5_auto_collapse;
  public OperatorData(){}
  public OperatorData(CoreNode operator) throws CoreNotFoundException {
    read(operator);
  }
  public OperatorData(CoreNode operator, String username) throws CoreNotFoundException {
    this.username = username;
    read(operator,false);
  }
  public void read(CoreNode op) throws CoreNotFoundException {
    read(op,true);
  }
  public void read(CoreNode op, boolean readUsername) throws CoreNotFoundException {
    if (readUsername){
      username = op.getAttribute(CoreNode.KEY).toLowerCase();
    }
    display_name = op.getAttribute(CoreNode.DISPLAY_NAME);
    password = op.getChild("password").getValueString();
    final CoreNode pref = op.getChild("preferences");
    lvl5_auto_collapse = pref.getChild("lvl5_auto_collapse").getBooleanAttribute(CoreNode.VALUE);
    lvl5_auto_logout = pref.getChild("lvl5_auto_logout").getIntAttribute(CoreNode.VALUE);
  }
  public void write(OperatorLink link, CoreNode op) throws CoreNotFoundException, CoreDatabaseException, CJDataValueException {
    op.setAttribute(CoreNode.KEY, username);
    op.setAttribute(NodeAttribute.lookup(CoreNode.DISPLAY_NAME, "en", true), display_name);
    link.setRawPassword(op, password, false);
    final CoreNode pref = op.getChild("preferences");
    pref.getChild("lvl5_auto_collapse").setBooleanAttribute(CoreNode.VALUE, lvl5_auto_collapse);
    pref.getChild("lvl5_auto_logout").setIntAttribute(CoreNode.VALUE, lvl5_auto_logout);
    link.assignAdminRole(op);
  }
  @Override public boolean equals(Object obj){
    if (this==obj){
      return true;
    }
    if (obj instanceof OperatorData){
      OperatorData op = (OperatorData)obj;
      return lvl5_auto_collapse==op.lvl5_auto_collapse && lvl5_auto_logout==op.lvl5_auto_logout && username.equals(op.username) && display_name.equals(op.display_name) && password.equals(op.password);
    }else{
      return false;
    }
  }
  public String query(OperatorData op){
    final StringBuilder sb = new StringBuilder(512);
    sb.append("UPDATE webctrl.operator_whitelist SET");
    boolean first = true;
    if (!display_name.equals(op.display_name)){
      if (first){
        first = false;
      }else{
        sb.append(',');
      }
      sb.append(" \"display_name\" = ").append(Utility.escapePostgreSQL(op.display_name));
    }
    if (!password.equals(op.password)){
      if (first){
        first = false;
      }else{
        sb.append(',');
      }
      sb.append(" \"password\" = ").append(Utility.escapePostgreSQL(op.password));
    }
    if (lvl5_auto_logout!=op.lvl5_auto_logout){
      if (first){
        first = false;
      }else{
        sb.append(',');
      }
      sb.append(" \"lvl5_auto_logout\" = ").append(op.lvl5_auto_logout);
    }
    if (lvl5_auto_collapse!=op.lvl5_auto_collapse){
      if (first){
        first = false;
      }else{
        sb.append(',');
      }
      sb.append(" \"lvl5_auto_collapse\" = ").append(op.lvl5_auto_collapse?"TRUE":"FALSE");
    }
    sb.append(" WHERE \"username\" = ").append(Utility.escapePostgreSQL(op.username)).append(';');
    return sb.toString();
  }
}