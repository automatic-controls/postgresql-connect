package aces.webctrl.postgresql.core;
import com.controlj.green.addonsupport.access.*;
import com.controlj.green.core.data.*;
import java.util.*;
import java.sql.*;
/**
 * Object intended to encapsulate a single synchronization event.
 */
public class Sync {
  /**
   * Used when no password has been set in the database.
   * Contains a hash of {@code 12345678}.
   */
  private final static String defaultPassword = "{SSHA512}8cPPJX8l58RlSBg7f8vL6g5MUVNtJL7N0XGfvp5KNl2/eLRylQyks2/X0g3nkhwM4YUur5XJfkOOqboju4AHMDP+lvPNtV8F";
  /** Contains the database table: webctrl.settings */
  public volatile static HashMap<String,String> settings = new HashMap<String,String>();
  /** The display name of the root of the geographic tree */
  public volatile static String serverName = null;
  public volatile static boolean started = false;
  public volatile static Set<String> operatorWhitelist = Collections.emptySet();
  public volatile static boolean lastGeneralSyncSuccessful = false;
  public static volatile boolean versionCompatible = false;
  public volatile boolean success = false;
  /** Stores a partial list of mappings from the operator reference name in the WebCTRL database to the operator username in the PostgreSQL database. */
  private final static HashMap<String,String> refusernameCache = new HashMap<String,String>();
  /**
   * Connects to the PostgreSQL database and synchronizes data.
   */
  public Sync(Event event, String... args){
    synchronized (Sync.class){
      if (Initializer.stop && event!=Event.SHUTDOWN){ return; }
      try{
        final String url = "jdbc:postgresql://"+Config.connectionURL;
        final String username = Config.username;
        final String password = Config.password;
        if (url.length()<=18){
          return;
        }
        final Properties connectionParams = new Properties();
        connectionParams.setProperty("user", username);
        connectionParams.setProperty("password", password);
        connectionParams.setProperty("sslmode", "verify-full");
        connectionParams.setProperty("sslfactory","org.postgresql.ssl.DefaultJavaSSLFactory");
        int ID = Config.ID;
        switch (event){
          case SHUTDOWN:{
            if (ID==-1 || !started){
              return;
            }
            try(
              Connection con = DriverManager.getConnection(url, connectionParams);
            ){
              syncLog(con,ID);
              try(
                Statement s = con.createStatement();
              ){
                s.executeUpdate("INSERT INTO webctrl.events VALUES("+ID+",'STOPPED',CURRENT_TIMESTAMP);");
              }
            }
            break;
          }
          case SAVE_OPERATOR:{
            if (ID==-1 || args.length!=2 || !versionCompatible){
              return;
            }
            final String refname = args[1];
            // Need this cache in case a operator tries to change their username more than once in a single session.
            final String oldUsername = refusernameCache.getOrDefault(refname, args[0].toLowerCase());
            final OperatorData data = new OperatorData();
            try(
              final OperatorLink link = new OperatorLink(true);
            ){
              final CoreNode op = link.getNode("/trees/config/operators/operatorlist").getChild(refname);
              if (op==null || Initializer.stop){
                return;
              }
              data.read(op);
            }
            if (Initializer.stop){ return; }
            boolean changeUsername = !oldUsername.equals(data.username);
            try(
              Connection con = DriverManager.getConnection(url, connectionParams);
            ){
              if (Initializer.stop){ return; }
              con.setAutoCommit(false);
              try{
                if (changeUsername){
                  try(
                    PreparedStatement s = con.prepareStatement("DELETE FROM webctrl.operator_blacklist WHERE \"username\" = ?;");
                  ){
                    s.setString(1,data.username);
                    s.executeUpdate();
                  }
                }
                try(
                  PreparedStatement s = con.prepareStatement(
                    "UPDATE webctrl.operator_whitelist SET"+
                    " \"username\" = ?"+
                    ", \"display_name\" = ?"+
                    ", \"password\" = ?"+
                    ", \"lvl5_auto_logout\" = ?"+
                    ", \"lvl5_auto_collapse\" = ?"+
                    " WHERE \"username\" = ?;"
                  );
                ){
                  s.setString(1,data.username);
                  s.setString(2,data.display_name);
                  s.setString(3,data.password);
                  s.setInt(4,data.lvl5_auto_logout);
                  s.setBoolean(5,data.lvl5_auto_collapse);
                  s.setString(6,oldUsername);
                  if (s.executeUpdate()!=1){
                    // If this operator changed their username on a different server, we won't be able to update from this server until the next general sync.
                    return;
                  }
                }
                if (changeUsername){
                  try(
                    PreparedStatement s = con.prepareStatement("INSERT INTO webctrl.operator_blacklist VALUES(?);");
                  ){
                    s.setString(1,oldUsername);
                    s.executeUpdate();
                  }
                }
                con.commit();
              }finally{
                con.rollback();
              }
            }
            if (changeUsername){
              refusernameCache.put(refname, data.username);
            }
            Initializer.log("Successfully saved operator "+data.username+'.');
            break;
          }
          case GENERAL:{
            if (serverName==null){
              DirectAccess.getDirectAccess().getRootSystemConnection().runReadAction(FieldAccessFactory.newDisabledFieldAccess(), new ReadAction(){
                public void execute(SystemAccess sys){
                  serverName = sys.getGeoRoot().getDisplayName();
                }
              });
              if (Initializer.stop){ return; }
            }
            Initializer.log("Attempting sync.");
            try(
              Connection con = DriverManager.getConnection(url, connectionParams);
            ){
              if (Initializer.stop){ return; }
              con.setAutoCommit(false);
              try{
                // Retrieve data from webctrl.settings
                {
                  final HashMap<String,String> map = new HashMap<String,String>();
                  String k,v;
                  try(
                    Statement s = con.createStatement();
                    ResultSet r = s.executeQuery("SELECT * FROM webctrl.settings;");
                  ){
                    while (r.next()){
                      k = r.getString(1);
                      v = r.getString(2);
                      if (k!=null && v!=null){
                        map.put(k,v);
                      }
                    }
                  }
                  con.commit();
                  settings = map;
                  v = map.get("version");
                  final int ver = Utility.compareVersions(Initializer.addonVersion,v);
                  versionCompatible = ver>=0;
                  if (!versionCompatible){
                    String update = map.get("auto_update");
                    if (update!=null){
                      update = update.trim();
                    }
                    if (update!=null && (update.equalsIgnoreCase("true") || update.equalsIgnoreCase("1"))){
                      HelperAPI.selfUpdate();
                      Initializer.log("Attempting auto-update from v"+Initializer.addonVersion+" to v"+v);
                      Initializer.stop = true;
                      success = true;
                    }else{
                      Initializer.status = "Version Mismatch: "+Initializer.addonVersion+" != "+v;
                    }
                    return;
                  }
                }
                if (Initializer.stop){ return; }
                // Update webctrl.servers
                if (ID<0){
                  ID = newServer(con);
                  if (ID<0){ return; }
                  Config.save();
                }else{
                  try(
                    PreparedStatement s = con.prepareStatement(
                      "INSERT INTO webctrl.servers VALUES(?,?,?,?,inet_client_addr(),CURRENT_TIMESTAMP,?) ON CONFLICT (\"id\") DO UPDATE SET"+
                      " \"name\" = EXCLUDED.\"name\""+
                      ", \"addon_version\" = EXCLUDED.\"addon_version\""+
                      ", \"version\" = EXCLUDED.\"version\""+
                      ", \"ip_address\" = EXCLUDED.\"ip_address\""+
                      ", \"last_sync\" = EXCLUDED.\"last_sync\""+
                      ", \"product_name\" = EXCLUDED.\"product_name\""
                    );
                  ){
                    s.setInt(1,ID);
                    s.setString(2,serverName);
                    s.setString(3,Initializer.version);
                    s.setString(4,Initializer.addonVersion);
                    s.setString(5,Initializer.productName);
                    s.executeUpdate();
                  }
                  con.commit();
                }
                if (Initializer.stop){ return; }
                // Update webctrl.events
                if (!started){
                  try(
                    Statement s = con.createStatement();
                  ){
                    s.executeUpdate("INSERT INTO webctrl.events VALUES("+ID+",'STARTED',CURRENT_TIMESTAMP);");
                  }
                  con.commit();
                  started = true;
                }
                // Update webctrl.log
                syncLog(con,ID);
                con.commit();
                if (Initializer.stop){ return; }
                // Delete operators in webctrl.operator_blacklist
                // Create operators in webctrl.operator_whitelist
                {
                  final HashSet<String> blacklist = new HashSet<String>(32);
                  final HashMap<String,OperatorData> whitelist = new HashMap<String,OperatorData>(64);
                  OperatorData data;
                  try(
                    Statement s = con.createStatement();
                  ){
                    try(
                      ResultSet r = s.executeQuery("SELECT * FROM webctrl.operator_blacklist;");
                    ){
                      while (r.next()){
                        blacklist.add(r.getString(1).toLowerCase());
                      }
                    }
                    try(
                      ResultSet r = s.executeQuery("SELECT * FROM webctrl.operator_whitelist;");
                    ){
                      while (r.next()){
                        data = new OperatorData();
                        data.username = r.getString(1).toLowerCase();
                        data.display_name = r.getString(2);
                        data.password = r.getString(3);
                        data.lvl5_auto_logout = r.getInt(4);
                        data.lvl5_auto_collapse = r.getBoolean(5);
                        if (data.password.isEmpty()){
                          data.password = defaultPassword;
                        }
                        whitelist.put(data.username,data);
                      }
                    }
                  }
                  con.commit();
                  if (Initializer.stop){ return; }
                  operatorWhitelist = new HashSet<String>(whitelist.keySet());
                  try(
                    OperatorLink link = new OperatorLink(false);
                  ){
                    String opname;
                    for (CoreNode op:link.getOperators()){
                      opname = op.getAttribute(CoreNode.KEY).toLowerCase();
                      if (blacklist.contains(opname)){
                        refusernameCache.remove(op.getReferenceName());
                        op.delete();
                        Initializer.log("Deleted blacklisted operator: "+opname);
                      }else if ((data = whitelist.get(opname))!=null){
                        data.write(link, op);
                        whitelist.remove(opname);
                      }
                    }
                    CoreNode op, pref;
                    for (OperatorData d:whitelist.values()){
                      op = link.createOperator(d.username, d.display_name, d.password, true);
                      pref = op.getChild("preferences");
                      pref.getChild("lvl5_auto_collapse").setBooleanAttribute(CoreNode.VALUE, d.lvl5_auto_collapse);
                      pref.getChild("lvl5_auto_logout").setIntAttribute(CoreNode.VALUE, d.lvl5_auto_logout);
                      Initializer.log("Created whitelisted operator: "+d.username);
                    }
                    link.commit();
                  }
                }
                if (Initializer.stop){ return; }
                // Update webctrl.operators
                {
                  final Map<String,String> operators = HelperAPI.getLocalOperators();
                  if (Initializer.stop){ return; }
                  try(
                    Statement s = con.createStatement();
                  ){
                    s.executeUpdate("DELETE FROM webctrl.operators WHERE \"server_id\" = "+ID+";");
                  }
                  try(
                    PreparedStatement s = con.prepareStatement("INSERT INTO webctrl.operators VALUES(?,?,?);");
                  ){
                    s.setInt(1,ID);
                    if (operators==null){
                      s.setString(2,"ERROR");
                      s.setString(3,"ERROR");
                      s.addBatch();
                    }else{
                      for (Map.Entry<String,String> op:operators.entrySet()){
                        s.setString(2,op.getKey());
                        s.setString(3,op.getValue());
                        s.addBatch();
                      }
                    }
                    s.executeBatch();
                  }
                  con.commit();
                }
                if (Initializer.stop){ return; }
                // Remove add-ons in webctrl.addon_blacklist
                {
                  final HashSet<String> blacklist = new HashSet<String>();
                  String min,max,addon;
                  try(
                    Statement s = con.createStatement();
                    ResultSet r = s.executeQuery("SELECT * FROM webctrl.addon_blacklist;");
                  ){
                    while (r.next()){
                      min = r.getString(2);
                      max = r.getString(3);
                      if ((min==null || Utility.compareVersions(Initializer.simpleVersion,min)>=0) && (max==null || Utility.compareVersions(Initializer.simpleVersion,max)<=0)){
                        addon = r.getString(1);
                        if (addon!=null && !Initializer.getName().equalsIgnoreCase(addon)){
                          blacklist.add(addon.toLowerCase());
                        }
                      }
                    }
                  }
                  con.commit();
                  if (Initializer.stop){ return; }
                  HelperAPI.removeAddons(blacklist,true);
                }
                if (Initializer.stop){ return; }
                // Download add-ons in webctrl.addon_whitelist
                {
                  final ArrayList<AddonDownload> whitelist = new ArrayList<AddonDownload>(24);
                  String min,max,addon;
                  AddonDownload d;
                  try(
                    Statement s = con.createStatement();
                    ResultSet r = s.executeQuery("SELECT * FROM webctrl.addon_whitelist;");
                  ){
                    while (r.next()){
                      min = r.getString(5);
                      max = r.getString(6);
                      if ((min==null || Utility.compareVersions(Initializer.simpleVersion,min)>=0) && (max==null || Utility.compareVersions(Initializer.simpleVersion,max)<=0)){
                        addon = r.getString(1);
                        if (addon!=null && !Initializer.getName().equalsIgnoreCase(addon)){
                          d = new AddonDownload();
                          d.name = addon;
                          d.version = r.getString(2);
                          d.keepNewer = r.getBoolean(3);
                          d.path = r.getString(4);
                          d.removeData = r.getBoolean(7);
                          whitelist.add(d);
                        }
                      }
                    }
                  }
                  con.commit();
                  if (Initializer.stop){ return; }
                  HelperAPI.downloadAddons(whitelist);
                }
                if (Initializer.stop){ return; }
                // UPDATE webctrl.addons
                {
                  final ArrayList<Addon> addons = HelperAPI.getAddons();
                  if (Initializer.stop){ return; }
                  try(
                    Statement s = con.createStatement();
                  ){
                    s.executeUpdate("DELETE FROM webctrl.addons WHERE \"server_id\" = "+ID+";");
                  }
                  try(
                    PreparedStatement s = con.prepareStatement("INSERT INTO webctrl.addons VALUES(?,?,?,?,?,?,?);");
                  ){
                    s.setInt(1,ID);
                    if (addons==null){
                      s.setString(2,"ERROR");
                      s.setString(3,"ERROR");
                      s.setString(4,"Failed to retrieve add-on list.");
                      s.setString(5,"ERROR");
                      s.setString(6,"ERROR");
                      s.setString(7,"ERROR");
                      s.executeUpdate();
                    }else{
                      for (Addon x:addons){
                        s.setString(2,x.name);
                        s.setString(3,x.displayName);
                        s.setString(4,x.description);
                        s.setString(5,x.vendor);
                        s.setString(6,x.version);
                        s.setString(7,x.state);
                        s.executeUpdate();
                      }
                    }
                  }
                  con.commit();
                }
                if (Initializer.stop){ return; }
                // UPDATE webctrl.events and webctrl.log
                try(
                  Statement s = con.createStatement();
                ){
                  s.executeUpdate("INSERT INTO webctrl.events VALUES("+ID+",'SYNCED',CURRENT_TIMESTAMP);");
                }
                syncLog(con,ID);
                con.commit();
              }finally{
                con.rollback();
              }
            }
            Initializer.log("Sync successful.");
            Initializer.status = "Sync Successful";
            break;
          }
        }
        success = true;
      }catch(Throwable t){
        if (!(t instanceof InterruptedException)){
          Initializer.status = "Sync Error";
          Initializer.log("Sync error.");
          Initializer.log(t);
        }
      }finally{
        if (event==Event.GENERAL){
          lastGeneralSyncSuccessful = success;
        }
      }
    }
  }
  private int newServer(Connection con) throws Throwable {
    int ID = -1;
    try(
      PreparedStatement s = con.prepareStatement("INSERT INTO webctrl.servers VALUES(DEFAULT,?,?,?,inet_client_addr(),CURRENT_TIMESTAMP,?) RETURNING \"id\";");
    ){
      s.setString(1,serverName);
      s.setString(2,Initializer.version);
      s.setString(3,Initializer.addonVersion);
      s.setString(4,Initializer.productName);
      try(
        ResultSet r = s.executeQuery();
      ){
        if (r.next()){
          ID = r.getInt(1);
        }
      }
    }
    if (ID<0){
      con.rollback();
      Initializer.log("Failed to automatically set server ID.");
      return -1;
    }else{
      con.commit();
      Config.ID = ID;
      Initializer.log("Registered with server ID="+ID);
    }
    return ID;
  }
  private void syncLog(Connection con, int ID) throws Throwable {
    if (!Initializer.logCache.isEmpty()){
      final ArrayList<LogMessage> cache = new ArrayList<LogMessage>(Initializer.logCache.size()+4);
      try(
        PreparedStatement s = con.prepareStatement("INSERT INTO webctrl.log VALUES("+ID+",?,?,?);");
      ){
        LogMessage m;
        while ((m=Initializer.logCache.poll())!=null){
          cache.add(m);
          s.setObject(1,m.getTimestamp());
          s.setBoolean(2,m.isError());
          s.setString(3,m.getMessage());
          s.addBatch();
        }
        s.executeBatch();
      }catch(Throwable t){
        Initializer.logCache.addAll(cache);
        throw t;
      }
    }
  }
}