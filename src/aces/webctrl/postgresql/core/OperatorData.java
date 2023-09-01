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
  public void read(CoreNode op) throws CoreNotFoundException {
    username = op.getAttribute(CoreNode.KEY).toLowerCase();
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
}