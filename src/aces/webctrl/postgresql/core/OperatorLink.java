package aces.webctrl.postgresql.core;
import java.util.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.io.PrintStream;
import com.controlj.green.core.data.*;
import com.controlj.green.common.policy.*;
import com.controlj.green.common.*;
/**
 * Utility class meant to facilitate access to WebCTRL's internal operator API.
 */
public class OperatorLink implements AutoCloseable {
  /** Controls the connection to the underlying database. */
  private volatile CoreDataSession cds;
  /** Used to cache CoreNodes. */
  private volatile HashMap<String,CoreNode> nodeMap = new HashMap<String,CoreNode>();
  /** Specifies whether modifications can be made to the underlying database. */
  private volatile boolean readOnly;
  /**
   * Opens a new CoreDataSession.
   * @param readOnly specifies whether to expect any modifications to the underlying operator database.
   */
  public OperatorLink(boolean readOnly) throws CoreDatabaseException {
    this.readOnly = readOnly;
    cds = CoreDataSession.open(readOnly?0:1);
  }
  /**
   * @return whether the underlying database connection is read-only.
   */
  public boolean isReadOnly(){
    return readOnly;
  }
  /**
   * @return the CoreNode for the operator with the given username.
   */
  public CoreNode getOperator(String username) throws CoreIntegrityException, CoreNotFoundException {
    return getNode("/trees/config/operators/operatorlist").getChildByAttribute(CoreNode.KEY, username.toLowerCase(), true);
  }
  /**
   * @return a list of all operator CoreNodes.
   */
  public List<CoreNode> getOperators() throws CoreIntegrityException {
    return getNode("/trees/config/operators/operatorlist").getChildren();
  }
  /**
   * Note that {@code last_login_instant} was added in WebCTRL8.5, so this method will return {@code -1} for earlier versions.
   * @return the epoch seconds of the last login of the given operator, or {@code -1} if the operator has never logged in.
   */
  public long getLastLogin(CoreNode operator){
    try{
      final String s = operator.getChild("last_login_instant").getValueString();
      if (s.isBlank()){
        return -1L;
      }
      return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toEpochSecond();
    }catch(CoreNotFoundException|DateTimeParseException e){
      return -1L;
    }
  }
  /**
   * Creates a new administrative operator with the given username, displayName, and password.
   * If an operator of the same username already exists, it is overwritten.
   */
  public CoreNode createOperator(String username, String displayName, String password, boolean rawPassword) throws CoreIntegrityException, CJDataValueException, CoreNotFoundException, CoreDatabaseException {
    username = username.toLowerCase();
    final CoreNode opList = getNode("/trees/config/operators/operatorlist");
    int sort = -1;
    if (opList.hasChildByAttribute(CoreNode.KEY, username)){
      final CoreNode op = getOperator(username);
      sort = op.getSort();
      op.delete();
    }
    final CoreNode operator = getNode("/defs/core/operatorlist/operator").clone(opList, username.equals("administrator")?username:opList.makeUniqueRefName("operator"));
    if (sort!=-1){
      operator.setSort(sort);
    }
    operator.setAttribute(CoreNode.KEY, username);
    if (rawPassword){
      setRawPassword(operator, password, false, true);
    }else{
      setPassword(operator, password, false);
    }
    assignAdminRole(operator);
    operator.setAttribute(NodeAttribute.lookup(CoreNode.DISPLAY_NAME, "en", true), displayName);
    return operator;
  }
  /**
   * Sets the password of the given operator.
   */
  public void setPassword(CoreNode operator, String password, boolean temporary) throws CoreNotFoundException {
    operator.getChild("password").setValueString(password);
    operator.getChild("password_is_temporary").setBooleanAttribute(CoreNode.VALUE, temporary);
    operator.getChild("operator_exempt").setBooleanAttribute(CoreNode.VALUE, true);
  }
  /**
   * Sets the raw digested password of the given operator.
   */
  public void setRawPassword(CoreNode operator, String digest, boolean temporary, boolean create) throws CoreNotFoundException, CoreDatabaseException {
    if (create || Config.bypassPasswordPolicy){
      operator.getChild("password_is_temporary").setBooleanAttribute(CoreNode.VALUE, temporary);
    }
    operator.getChild("operator_exempt").setBooleanAttribute(CoreNode.VALUE, Config.bypassPasswordPolicy);
    final CoreNode passwordNode = operator.getChild("password");
    final String oldDigest = passwordNode.getValueString();
    if (!digest.equals(oldDigest)){
      passwordNode.setRawValueString(digest);
      operator.getChild("password_changed_date").setIntAttribute(CoreNode.VALUE, (int)(System.currentTimeMillis()/1000L));
      Operator.clearLoginLockout(operator.getAttribute(CoreNode.KEY));
      final CoreNode previous = operator.getChild("previous_passwords");
      final List<CoreNode> oldPasswords = previous.getSortedChildren();
      if (oldPasswords.size()>=20){
        oldPasswords.get(0).delete();
      }
      previous.createNewChild().setRawValueString(digest);
    }
  }
  /**
   * @return whether the given password is correct.
   */
  public boolean validatePassword(CoreNode operator, String password) throws CoreNotFoundException {
    return PolicyUtils_.rawMatches(operator.getChild("password").getValueString(), password);
  }
  /**
   * Assigns the administrator role to the given operator (if not already assigned).
   * @return whether any changes were made.
   */
  public boolean assignAdminRole(CoreNode operator) throws CoreIntegrityException, CoreNotFoundException {
    final CoreNode roles = operator.getChild("roles");
    for (CoreNode role:roles.getChildren()){
      if (role.getCoreNodeAttribute(CoreNode.TARGET).getReferenceName().equals("administrator")){
        return false;
      }
    }
    final CoreNode admin = getNode("/trees/config/roles/administrator");
    roles.createNewChild().setCoreNodeAttribute(CoreNode.TARGET, admin);
    return true;
  }
  /**
   * Note - this method ignores roles inherited from operator groups.
   * @return whether the given operator has been assigned the administrator role.
   */
  public boolean hasAdminRole(CoreNode operator) throws CoreNotFoundException {
    final CoreNode roles = operator.getChild("roles");
    for (CoreNode role:roles.getChildren()){
      if (role.getCoreNodeAttribute(CoreNode.TARGET).getReferenceName().equals("administrator")){
        return true;
      }
    }
    return false;
  }
  /**
   * @return the CoreNode corresponding to the given absolute path.
   */
  public CoreNode getNode(String path) throws CoreIntegrityException {
    CoreNode n = nodeMap.get(path);
    if (n==null){
      n = cds.getExpectedNode(path);
      nodeMap.put(path,n);
    }
    return n;
  }
  /**
   * @return the CoreDataSession used by this link.
   */
  public CoreDataSession getCoreDataSession(){
    return cds;
  }
  /**
   * Commits changes to the underlying database.
   */
  public void commit(){
    cds.commit();
  }
  /**
   * Closes the CoreDataSession associated with this Object.
   */
  @Override public void close(){
    cds.close();
  }
  /**
   * Prints details about the given node.
   * @param node is what to print details about.
   * @param out specifies where to print details.
   * @param indent specifies a prefix to use for printed out lines.
   * @param printAttrForUnknown specifies whether to print a list of attributes for nodes of unknown type.
   */
  public void printNode(CoreNode node, PrintStream out, String indent, boolean printAttrForUnknown) throws CoreNotFoundException {
    if (indent==null){ indent = ""; }
    final String newIndent = indent+"  ";
    final short type = node.getNodeType();
    out.print(indent+node.getReferenceName());
    if (node.hasAttribute(CoreNode.KEY)){
      out.print(": "+node.getAttribute(CoreNode.KEY));
    }
    if (node.hasAttribute(CoreNode.DISPLAY_NAME)){
      out.print(" - \""+node.getAttribute(CoreNode.DISPLAY_NAME)+'"');
    }
    out.print(" ("+NodeType.toString(type)+") -> ");
    if (node.getBooleanAttribute(CoreNode.HAS_CHILDREN)){
      final List<CoreNode> list = node.getChildrenSortedForPresentation();
      out.println("Children="+list.size());
      for (CoreNode n:list){
        printNode(n, out, newIndent, printAttrForUnknown);
      }
    }else{
      switch(type){
        case NodeType.CHRSTR:case NodeType.INT:case NodeType.BOOL:case NodeType.ASSOC:case NodeType.DATE:case NodeType.UNS:case NodeType.REAL:{
          out.println(node.getValueString());
          break;
        }
        case NodeType.REF:{
          out.println();
          printNode(node.getCoreNodeAttribute(CoreNode.TARGET), out, indent+"  ", printAttrForUnknown);
          break;
        }
        case NodeType.LIST:case NodeType.SEQ:case NodeType.DIR:{
          out.println("Children=0");
          break;
        }
        default:{
          out.println("(???) "+getDataTypeString(node.getBaseDataType()));
          if (printAttrForUnknown){
            for (NodeAttribute na:node.getAttributes()){
              if (na!=CoreNode.KEY && na!=CoreNode.NODE_TYPE && na!=CoreNode.REFERENCE_NAME && na!=CoreNode.DISPLAY_NAME){
                out.println(newIndent+na.toString()+": "+node.getAttribute(na));
              }
            }
          }
        }
      }
    }
  }
  /**
   * Prints a list of attributes for the given node.
   */
  public void printAttributes(CoreNode node, PrintStream out){
    for (NodeAttribute na:node.getAttributes()){
      out.println(na.toString()+": "+node.getAttribute(na));
    }
  }
  /**
   * Used when printing node details.
   * @return the CJDataType name associated to the given type.
   */
  private static String getDataTypeString(short type){
    switch(type){
      case CJDataType.COMPLEX: return "COMPLEX";
      case CJDataType.BOOLEAN: return "BOOLEAN";
      case CJDataType.BITSTRING: return "BITSTRING";
      case CJDataType.UNSIGNED: return "UNSIGNED";
      case CJDataType.INTEGER: return "INTEGER";
      case CJDataType.REAL: return "REAL";
      case CJDataType.DOUBLE: return "DOUBLE";
      case CJDataType.OCTETSTRING: return "OCTETSTRING";
      case CJDataType.STRING: return "STRING";
      case CJDataType.ENUMERATED: return "ENUMERATED";
      case CJDataType.DATE: return "DATE";
      case CJDataType.TIME: return "TIME";
      case CJDataType.OBJID: return "OBJID";
      case CJDataType.NULL: return "NULL";
      case CJDataType.ENUM_STRING: return "ENUM_STRING";
      case CJDataType.DATA_TABLE: return "DATA_TABLE";
      default: return "UNKNOWN:"+type;
    }
  }
}
/**
 * We need this class to access a protected method {@code rawMatches(String,String)} of PolicyUtils.
 * The other option is {@code matches(String,String)}; however, this would create a delay on failed validation.
 */
class PolicyUtils_ extends PolicyUtils {
  public static boolean rawMatches(String digestedData, String clearData){
    return PolicyUtils.rawMatches(digestedData, clearData);
  }
}