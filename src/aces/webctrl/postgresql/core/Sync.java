package aces.webctrl.postgresql.core;
import aces.webctrl.postgresql.web.*;
import com.controlj.green.core.data.*;
import com.controlj.green.addonsupport.access.*;
import com.controlj.green.addonsupport.access.trend.*;
import com.controlj.green.addonsupport.access.aspect.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.sql.*;
import java.time.*;
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
  public volatile static Map<String,OperatorData> operatorWhitelist = Collections.emptyMap();
  public volatile static boolean lastGeneralSyncSuccessful = false;
  public static volatile boolean versionCompatible = false;
  public volatile boolean success = false;
  public volatile static boolean delayUpdate = false;
  public volatile static boolean licenseSynced = false;
  private volatile static boolean cleanedLogs = false;
  private volatile static long lastSyncTime = -1L;
  public volatile static boolean excludeAutoLogoutTime = false;
  /** Stores a partial list of mappings from the operator reference name in the WebCTRL database to the operator username in the PostgreSQL database. */
  private final static HashMap<String,String> refusernameCache = new HashMap<String,String>();
  /**
   * Connects to the PostgreSQL database and synchronizes data.
   */
  public Sync(Event event, String... args){
    synchronized (Sync.class){
      if (!Initializer.hostValid || Initializer.stop && event!=Event.SHUTDOWN){ return; }
      try{
        boolean debug = Initializer.debug();
        final String connUrl = Config.connectionURL;
        final String url = "jdbc:postgresql://"+connUrl;
        final String username = Config.username;
        final String password = Config.password;
        if (url.length()<=18){
          Initializer.status = "Connection URL is invalid.";
          return;
        }
        final Properties connectionParams = new Properties();
        connectionParams.setProperty("user", username);
        connectionParams.setProperty("password", password);
        connectionParams.setProperty("sslmode", connUrl.startsWith("localhost") || connUrl.startsWith("127.0.0.1")?"require":"verify-full");
        connectionParams.setProperty("sslkey", Initializer.pgsslkey.toString());
        connectionParams.setProperty("sslpassword", Config.keystorePassword);
        connectionParams.setProperty("sslrootcert", Initializer.pgsslroot.toString());
        int ID = Config.ID;
        SSHProxy.activate();
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
          case SELECT_TABLE:{
            if (ID==-1 || !started || args.length!=1){
              return;
            }
            args[0] = args[0].replace("$ID", String.valueOf(ID));
            try(
              Connection con = DriverManager.getConnection(url, connectionParams);
            ){
              syncLog(con,ID);
              try(
                Statement s = con.createStatement();
              ){
                if (debug){
                  Initializer.log(args[0]);
                }
                fillCache(args[0],s);
              }
            }
            break;
          }
          case UPDATE_TABLE:{
            if (ID==-1 || !started || args.length<=1){
              return;
            }
            args[0] = args[0].replace("$ID", String.valueOf(ID));
            try(
              Connection con = DriverManager.getConnection(url, connectionParams);
            ){
              con.setAutoCommit(false);
              syncLog(con,ID);
              try(
                Statement s = con.createStatement();
              ){
                for (int i=1;i<args.length;++i){
                  if (Initializer.stop){ return; }
                  if (debug){
                    if (args[i].startsWith("INSERT INTO webctrl.operator_whitelist ") && !args[i].contains("{SSHA512}")){
                      Initializer.log("INSERT INTO webctrl.operator_whitelist VALUES (**Sensitive Information**);");
                    }else if (args[i].startsWith("UPDATE webctrl.operator_whitelist ") && !args[i].contains("{SSHA512}")){
                      Initializer.log("UPDATE webctrl.operator_whitelist SET (**Sensitive Information**);");
                    }else{
                      Initializer.log(args[i]);
                    }
                  }
                  s.executeUpdate(args[i]);
                }
                con.commit();
                if (debug){
                  Initializer.log(args[0]);
                }
                fillCache(args[0],s);
              }
              con.commit();
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
                    if (s.executeUpdate()>0 && debug){
                      Initializer.log("Deleted "+data.username+" from operator_blacklist.");
                    }
                  }
                }
                try(
                  PreparedStatement s = con.prepareStatement(
                    "UPDATE webctrl.operator_whitelist SET"+
                    " \"username\" = ?"+
                    ", \"display_name\" = ?"+
                    ", \"password\" = ?"+
                    (excludeAutoLogoutTime?"":", \"lvl5_auto_logout\" = ?")+
                    ", \"lvl5_auto_collapse\" = ?"+
                    " WHERE \"username\" = ?;"
                  );
                ){
                  int i=0;
                  s.setString(++i,data.username);
                  s.setString(++i,data.display_name);
                  s.setString(++i,data.password);
                  if (!excludeAutoLogoutTime){
                    s.setInt(++i,data.lvl5_auto_logout);
                  }
                  s.setBoolean(++i,data.lvl5_auto_collapse);
                  s.setString(++i,oldUsername);
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
                    if (s.executeUpdate()>0 && debug){
                      Initializer.log("Inserted "+oldUsername+" into operator_blacklist.");
                    }
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
            SystemConnection sysCon = null;
            if (serverName==null){
              if (sysCon==null){
                sysCon = Initializer.getConnection();
              }
              sysCon.runReadAction(FieldAccessFactory.newDisabledFieldAccess(), new ReadAction(){
                public void execute(SystemAccess sys){
                  serverName = sys.getGeoRoot().getDisplayName();
                }
              });
              if (Initializer.stop){ return; }
            }
            Initializer.log("Attempting sync.");
            // Gather local changes to operator_whitelist since last sync
            final ArrayList<String> opUpdates = new ArrayList<String>(8);
            if (lastSyncTime!=-1L && !operatorWhitelist.isEmpty()){
              OperatorData x,y;
              try(
                OperatorLink link = new OperatorLink(true);
              ){
                String opname;
                for (CoreNode op:link.getOperators()){
                  opname = op.getAttribute(CoreNode.KEY).toLowerCase();
                  if ((x = operatorWhitelist.get(opname))!=null && (link.getLastLogin(op)+28800L)*1000L>lastSyncTime){
                    y = new OperatorData(op,opname);
                    if (!x.equals(y)){
                      opUpdates.add(x.query(y));
                      Initializer.log("Pushing local change in operator "+opname+" to database.");
                    }
                  }
                  if (Initializer.stop){ return; }
                }
              }
            }
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
                      if (k!=null && v!=null && !k.isEmpty() && !v.isEmpty()){
                        map.put(k,v);
                      }
                    }
                  }
                  con.commit();
                  settings = map;
                  final boolean d = Initializer.debug();
                  if (d^debug){
                    debug = d;
                    Initializer.log("Debug mode "+(d?"en":"dis")+"abled.");
                  }
                  v = map.get("version");
                  final int ver = Utility.compareVersions(Initializer.addonVersion,v);
                  versionCompatible = ver>=0;
                  if (versionCompatible){
                    delayUpdate = false;
                  }else{
                    String update = map.get("auto_update");
                    if (update!=null){
                      update = update.trim();
                    }
                    if (update!=null && (update.equalsIgnoreCase("true") || update.equalsIgnoreCase("1"))){
                      if (delayUpdate){
                        delayUpdate = false;
                        Initializer.log("Warning - Infinite self-update loop detected.", true);
                      }else if (HelperAPI.selfUpdate()){
                        Initializer.log("Attempting auto-update from v"+Initializer.addonVersion+" to v"+v);
                        Initializer.stop = true;
                        success = true;
                        return;
                      }else{
                        Initializer.log("Warning - Self-update function failed.", true);
                      }
                    }else{
                      String s = "Version Mismatch: "+Initializer.addonVersion+" != "+v;
                      Initializer.status = s;
                      if (debug){
                        Initializer.log(s);
                      }
                    }
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
                      "INSERT INTO webctrl.servers VALUES(?,?,?,?,inet_client_addr(),CURRENT_TIMESTAMP,?,?,'') ON CONFLICT (\"id\") DO UPDATE SET"+
                      " \"addon_version\" = EXCLUDED.\"addon_version\""+
                      ", \"version\" = EXCLUDED.\"version\""+
                      ", \"ip_address\" = EXCLUDED.\"ip_address\""+
                      ", \"last_sync\" = EXCLUDED.\"last_sync\""+
                      ", \"product_name\" = EXCLUDED.\"product_name\""+
                      ", \"cum_updates\" = EXCLUDED.\"cum_updates\""
                    );
                  ){
                    s.setInt(1,ID);
                    s.setString(2,serverName);
                    s.setString(3,Initializer.version);
                    s.setString(4,Initializer.addonVersion);
                    s.setString(5,Initializer.productName);
                    s.setString(6,Initializer.cumUpdates);
                    s.executeUpdate();
                  }
                  con.commit();
                }
                // Load Miscellaneous Settings
                {
                  String s = settings.get("auto_logout_exclude_ids");
                  excludeAutoLogoutTime = s!=null && Pattern.compile("(?:^|;)"+ID+"(?:$|;)", Pattern.MULTILINE).matcher(s).find();
                }
                if (Initializer.stop){ return; }
                // Check for changes in SSH tunnels
                {
                  TunnelSSH.checkConnectionChanges();
                  TunnelSSH.checkTunnelExpiry();
                  final HashMap<Integer,TunnelSSH.Tunnel> tuns = new HashMap<>();
                  try(
                    Statement s = con.createStatement();
                    ResultSet r = s.executeQuery("SELECT \"src_port\", \"dst_port\" FROM webctrl.tunnels WHERE \"server_id\" = "+ID+";");
                  ){
                    int l;
                    while (r.next()){
                      l = r.getInt(1);
                      tuns.put(l, new TunnelSSH.Tunnel(l, r.getInt(2), 0));
                    }
                  }
                  con.commit();
                  TunnelSSH.refreshPersistentTunnels(tuns);
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
                if (Initializer.stop){ return; }
                // Push local operator_whitelist changes to database
                // Delete operators in webctrl.operator_blacklist
                // Create operators in webctrl.operator_whitelist
                {
                  if (!opUpdates.isEmpty()){
                    try(
                      Statement s = con.createStatement();
                    ){
                      for (String update: opUpdates){
                        if (debug){
                          Initializer.log(update);
                        }
                        s.executeUpdate(update);
                        if (Initializer.stop){ return; }
                      }
                    }
                    con.commit();
                  }
                  final HashSet<String> blacklist = new HashSet<String>(32);
                  final HashMap<String,OperatorData> whitelist = new HashMap<String,OperatorData>(64);
                  OperatorData data;
                  try(
                    Statement s = con.createStatement();
                  ){
                    try(
                      ResultSet r = s.executeQuery("SELECT \"x\".* FROM webctrl.operator_blacklist \"x\" LEFT JOIN webctrl.operator_blacklist_exceptions \"y\" ON \"y\".\"server_id\" = "+ID+" AND \"x\".\"username\" = \"y\".\"username\" WHERE \"y\".\"server_id\" IS NULL;");
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
                        if (data.password==null || data.password.isEmpty()){
                          data.password = defaultPassword;
                        }
                        whitelist.put(data.username,data);
                      }
                    }
                  }
                  con.commit();
                  if (Initializer.stop){ return; }
                  operatorWhitelist = new HashMap<String,OperatorData>(whitelist);
                  final HashSet<String> deletedOps = new HashSet<String>(16);
                  try(
                    OperatorLink link = new OperatorLink(false);
                  ){
                    String opname;
                    for (CoreNode op:link.getOperators()){
                      opname = op.getAttribute(CoreNode.KEY).toLowerCase();
                      if (blacklist.contains(opname)){
                        refusernameCache.remove(op.getReferenceName());
                        op.delete();
                        deletedOps.add(opname);
                        Initializer.log("Deleted blacklisted operator: "+opname);
                      }else if ((data = whitelist.get(opname))!=null){
                        data.write(link, op, true);
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
                  }finally{
                    HelperAPI.logout(deletedOps);
                  }
                  lastSyncTime = System.currentTimeMillis();
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
                  final HashMap<String,Boolean> blacklist = new HashMap<String,Boolean>();
                  String min,max,addon;
                  try(
                    Statement s = con.createStatement();
                    ResultSet r = s.executeQuery("SELECT * FROM webctrl.addon_blacklist;");
                  ){
                    while (r.next()){
                      min = r.getString(2);
                      max = r.getString(3);
                      if ((min==null || min.isEmpty() || Utility.compareVersions(Initializer.simpleVersion,min)>=0) && (max==null || max.isEmpty() || Utility.compareVersions(Initializer.simpleVersion,max)<=0)){
                        addon = r.getString(1);
                        if (addon!=null && !Initializer.getName().equalsIgnoreCase(addon)){
                          blacklist.put(addon.toLowerCase(), r.getBoolean(4));
                        }
                      }
                    }
                  }
                  con.commit();
                  if (!blacklist.isEmpty()){
                    if (Initializer.stop){ return; }
                    HelperAPI.removeAddons(blacklist);
                  }
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
                      if ((min==null || min.isEmpty() || Utility.compareVersions(Initializer.simpleVersion,min)>=0) && (max==null || max.isEmpty() || Utility.compareVersions(Initializer.simpleVersion,max)<=0)){
                        addon = r.getString(1);
                        if (addon!=null && !Initializer.getName().equalsIgnoreCase(addon)){
                          d = new AddonDownload();
                          d.displayName = addon;
                          d.version = r.getString(2);
                          d.keepNewer = r.getBoolean(3);
                          d.path = r.getString(4);
                          d.removeData = r.getBoolean(7);
                          d.optional = r.getBoolean(8);
                          whitelist.add(d);
                        }
                      }
                    }
                  }
                  con.commit();
                  if (!whitelist.isEmpty()){
                    if (Initializer.stop){ return; }
                    HelperAPI.downloadAddons(whitelist);
                  }
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
                // UPDATE webctrl.events
                try(
                  Statement s = con.createStatement();
                ){
                  s.executeUpdate("INSERT INTO webctrl.events VALUES("+ID+",'SYNCED',CURRENT_TIMESTAMP);");
                }
                con.commit();
                if (Initializer.stop){ return; }
                // UPDATE webctrl.trend_data
                {
                  final ArrayList<TrendMapping> trends = new ArrayList<TrendMapping>();
                  try(
                    Statement s = con.createStatement();
                    ResultSet r = s.executeQuery(
                      "SELECT\n"+
                      "  \"x\".\"id\",\n"+
                      "  \"x\".\"persistent_identifier\",\n"+
                      "  \"x\".\"field_access\",\n"+
                      "  GREATEST(\"y\".\"time\", CURRENT_TIMESTAMP-make_interval(days=>\"retain_data\")) AS \"start\"\n"+
                      "FROM (\n"+
                      "  SELECT * FROM webctrl.trend_mappings WHERE \"server_id\" = "+ID+"\n"+
                      ") \"x\" LEFT JOIN (\n"+
                      "  SELECT DISTINCT ON (\"id\")\n"+
                      "    \"id\",\n"+
                      "    \"time\"\n"+
                      "  FROM webctrl.trend_data\n"+
                      "  ORDER BY \"id\" ASC, \"time\" DESC\n"+
                      ") \"y\"\n"+
                      "ON \"x\".\"id\" = \"y\".\"id\";"
                    );
                  ){
                    TrendMapping tm;
                    OffsetDateTime t;
                    while (r.next()){
                      t = r.getObject(4, OffsetDateTime.class);
                      if (t==null){
                        continue;
                      }
                      tm = new TrendMapping();
                      tm.ID = r.getInt(1);
                      tm.persistentIdentifier = r.getString(2);
                      tm.fieldAccess = r.getBoolean(3);
                      tm.start = java.util.Date.from(t.toInstant());
                      trends.add(tm);
                    }
                  }
                  if (Initializer.stop){ return; }
                  if (!trends.isEmpty()){
                    final boolean debugg = debug;
                    final java.util.Date now = new java.util.Date();
                    final ArrayList<Long> times = new ArrayList<Long>();
                    final Container<ArrayList<Integer>> intValues = new Container<ArrayList<Integer>>();
                    final Container<ArrayList<Double>> doubleValues = new Container<ArrayList<Double>>();
                    final Container<BitSet> boolValues = new Container<BitSet>();
                    final Container<BitSet> boolNulls = new Container<BitSet>();
                    if (sysCon==null){
                      sysCon = Initializer.getConnection();
                    }
                    for (final TrendMapping tm:trends){
                      final int cacheSize = Math.min((int)((now.getTime()-tm.start.getTime())/300000L), 65536);
                      final TrendRange rng = TrendRangeFactory.byDateRange(tm.start, now);
                      final long startTime = tm.start.getTime();
                      times.clear();
                      intValues.x = null;
                      doubleValues.x = null;
                      boolValues.x = null;
                      boolNulls.x = null;
                      try{
                        sysCon.runReadAction(tm.fieldAccess?FieldAccessFactory.newFieldAccess():FieldAccessFactory.newDisabledFieldAccess(), new ReadAction(){
                          @Override public void execute(SystemAccess sys){
                            try{
                              Location loc = sys.getTree(SystemTree.Geographic).resolve(tm.persistentIdentifier);
                              times.ensureCapacity(cacheSize);
                              if (loc.hasAspect(AnalogTrendSource.class)){
                                final AnalogTrendSource s = loc.getAspect(AnalogTrendSource.class);
                                doubleValues.x = new ArrayList<Double>(cacheSize);
                                TrendData<TrendAnalogSample> data = s.getTrendData(rng);
                                data.process(new TrendProcessor<TrendAnalogSample>(){
                                  @Override public void processData(TrendAnalogSample sample){
                                    final long time = sample.getTimeInMillis();
                                    if (time>startTime){
                                      times.add(time);
                                      doubleValues.x.add(sample.doubleValue());
                                    }
                                  }
                                  @Override public void processHole(java.util.Date start, java.util.Date end){
                                    final long time = start.getTime();
                                    if (time>startTime){
                                      times.add(time);
                                      doubleValues.x.add(null);
                                    }
                                  }
                                  @Override public void processEnd(java.util.Date time, TrendAnalogSample sample){}
                                  @Override public void processStart(java.util.Date time, TrendAnalogSample sample){}
                                });
                              }else if (loc.hasAspect(EquipmentColorTrendSource.class)){
                                final EquipmentColorTrendSource s = loc.getAspect(EquipmentColorTrendSource.class);
                                intValues.x = new ArrayList<Integer>(cacheSize);
                                TrendData<TrendEquipmentColorSample> data = s.getTrendData(rng);
                                data.process(new TrendProcessor<TrendEquipmentColorSample>(){
                                  @Override public void processData(TrendEquipmentColorSample sample){
                                    final long time = sample.getTimeInMillis();
                                    if (time>startTime){
                                      times.add(time);
                                      intValues.x.add(sample.value().getColor().getRGB());
                                    }
                                  }
                                  @Override public void processHole(java.util.Date start, java.util.Date end){
                                    final long time = start.getTime();
                                    if (time>startTime){
                                      times.add(time);
                                      intValues.x.add(null);
                                    }
                                  }
                                  @Override public void processEnd(java.util.Date time, TrendEquipmentColorSample sample){}
                                  @Override public void processStart(java.util.Date time, TrendEquipmentColorSample sample){}
                                });
                              }else if (loc.hasAspect(DigitalTrendSource.class)){
                                final DigitalTrendSource s = loc.getAspect(DigitalTrendSource.class);
                                boolValues.x = new BitSet(cacheSize);
                                boolNulls.x = new BitSet(cacheSize);
                                TrendData<TrendDigitalSample> data = s.getTrendData(rng);
                                final Container<Integer> i = new Container<Integer>(0);
                                data.process(new TrendProcessor<TrendDigitalSample>(){
                                  @Override public void processData(TrendDigitalSample sample){
                                    final long time = sample.getTimeInMillis();
                                    if (time>startTime){
                                      times.add(time);
                                      if (sample.getState()){
                                        boolValues.x.set(i.x);
                                      }
                                      ++i.x;
                                    }
                                  }
                                  @Override public void processHole(java.util.Date start, java.util.Date end){
                                    final long time = start.getTime();
                                    if (time>startTime){
                                      times.add(time);
                                      boolNulls.x.set(i.x);
                                      ++i.x;
                                    }
                                  }
                                  @Override public void processEnd(java.util.Date time, TrendDigitalSample sample){}
                                  @Override public void processStart(java.util.Date time, TrendDigitalSample sample){}
                                });
                              }
                            }catch(Throwable t){
                              if (debugg){
                                Initializer.log(t);
                              }
                            }
                          }
                        });
                      }catch(Throwable t){
                        Initializer.log(t);
                      }
                      if (Initializer.stop){ return; }
                      final int size = times.size();
                      if (size>0){
                        if (intValues.x!=null){
                          try(
                            PreparedStatement s = con.prepareStatement("INSERT INTO webctrl.trend_data VALUES ("+tm.ID+",?,NULL,?,NULL);");
                          ){
                            Integer j;
                            for (int i=0;i<size;++i){
                              s.setObject(1, Instant.ofEpochMilli(times.get(i)).atOffset(ZoneOffset.UTC));
                              j = intValues.x.get(i);
                              if (j==null){
                                s.setNull(2,Types.INTEGER);
                              }else{
                                s.setInt(2,j);
                              }
                              s.addBatch();
                            }
                            s.executeBatch();
                          }
                          con.commit();
                        }else if (doubleValues.x!=null){
                          try(
                            PreparedStatement s = con.prepareStatement("INSERT INTO webctrl.trend_data VALUES ("+tm.ID+",?,NULL,NULL,?);");
                          ){
                            Double j;
                            for (int i=0;i<size;++i){
                              s.setObject(1, Instant.ofEpochMilli(times.get(i)).atOffset(ZoneOffset.UTC));
                              j = doubleValues.x.get(i);
                              if (j==null){
                                s.setNull(2,Types.DOUBLE);
                              }else{
                                s.setDouble(2,j);
                              }
                              s.addBatch();
                            }
                            s.executeBatch();
                          }
                          con.commit();
                        }else if (boolValues.x!=null){
                          try(
                            PreparedStatement s = con.prepareStatement("INSERT INTO webctrl.trend_data VALUES ("+tm.ID+",?,?,NULL,NULL);");
                          ){
                            for (int i=0;i<size;++i){
                              s.setObject(1, Instant.ofEpochMilli(times.get(i)).atOffset(ZoneOffset.UTC));
                              if (boolNulls.x.get(i)){
                                s.setNull(2,Types.BIT);
                              }else{
                                s.setBoolean(2,boolValues.x.get(i));
                              }
                              s.addBatch();
                            }
                            s.executeBatch();
                          }
                          con.commit();
                        }
                        if (Initializer.stop){ return; }
                      }
                    }
                  }
                  int x;
                  try(
                    Statement s = con.createStatement();
                  ){
                    x = s.executeUpdate(
                      "WITH \"thresh\" AS (\n"+
                      "  SELECT\n"+
                      "    \"id\",\n"+
                      "    CURRENT_TIMESTAMP-make_interval(days=>\"retain_data\") AS \"start\"\n"+
                      "  FROM webctrl.trend_mappings\n"+
                      ") DELETE FROM webctrl.trend_data \"a\" USING \"thresh\" \"b\"\n"+
                      "WHERE \"a\".\"id\" = \"b\".\"id\" AND \"a\".\"time\" < \"b\".\"start\";"
                    );
                  }
                  if (x>0 && debug){
                    Initializer.log("Deleted "+x+" expired values from trend database.");
                  }
                  con.commit();
                }
                if (Initializer.stop){ return; }
                // Execute pending commands
                while (true){
                  final ArrayList<Command> commands = new ArrayList<Command>();
                  try(
                    Statement s = con.createStatement();
                    ResultSet r = s.executeQuery("SELECT \"id\",\"command\" FROM webctrl.pending_commands WHERE \"server_id\" = "+ID+" ORDER BY \"ordering\" ASC;");
                  ){
                    Command c;
                    while (r.next()){
                      c = new Command(r.getInt(1), r.getString(2));
                      if (!c.isEmpty()){
                        commands.add(c);
                        if (c.hasReboot()){
                          break;
                        }
                      }
                    }
                  }catch(IndexOutOfBoundsException|NullPointerException t){
                    commands.clear();
                    Initializer.log(t);
                  }
                  if (commands.size()>0){
                    try(
                      PreparedStatement s = con.prepareStatement("DELETE FROM webctrl.pending_commands WHERE \"id\"=?;");
                    ){
                      for (Command c: commands){
                        s.setInt(1,c.id);
                        s.addBatch();
                      }
                      s.executeBatch();
                    }
                    con.commit();
                    boolean cont = false;
                    for (Command c: commands){
                      if (!c.execute(true) && c.hasReboot()){
                        cont = true;
                      }
                    }
                    if (cont){
                      continue;
                    }
                  }
                  break;
                }
                if (!cleanedLogs){
                  if (Initializer.stop){ return; }
                  String expiry = settings.get("log_expiration");
                  if (expiry==null || !DownloadLicensePage.num.matcher(expiry).matches()){
                    expiry = "60";
                  }
                  int x = 0;
                  try(
                    Statement s = con.createStatement();
                  ){
                    x+=s.executeUpdate(
                      "DELETE FROM webctrl.log\n"+
                      "WHERE \"time\"+(INTERVAL '"+expiry+" days')<CURRENT_TIMESTAMP;"
                    );
                    x+=s.executeUpdate(
                      "DELETE FROM webctrl.events\n"+
                      "WHERE \"time\"+(INTERVAL '"+expiry+" days')<CURRENT_TIMESTAMP;"
                    );
                  }
                  if (x>0 && debug){
                    Initializer.log("Deleted "+x+" expired logs and events from database.");
                  }
                  cleanedLogs = true;
                  con.commit();
                }
                syncLog(con,ID);
              }finally{
                con.rollback();
              }
            }
            Initializer.log("Sync successful.");
            Initializer.status = "Sync Successful";
            if (!licenseSynced && Initializer.licenseFile!=null && ID>=0){
              String dst = settings.get("license_directory");
              if (dst!=null){
                dst+="/license-"+ID+".properties";
                try{
                  if (Files.exists(Initializer.licenseFile)){
                    try(
                      final ConnectSFTP con = new ConnectSFTP();
                    ){
                      if (con.uploadFile(Initializer.licenseFile, dst)){
                        licenseSynced = true;
                      }
                    }
                  }
                }catch(Throwable t){
                  Initializer.log(t);
                }
              }
            }
            break;
          }
        }
        success = true;
      }catch(Throwable t){
        if (!(t instanceof InterruptedException)){
          final String s = "Sync error during: "+event.name();
          Initializer.status = s;
          Initializer.log(s,true);
          Initializer.log(t);
        }
      }finally{
        if (event==Event.GENERAL){
          lastGeneralSyncSuccessful = success;
        }
        SSHProxy.deactivate();
      }
    }
  }
  private int newServer(Connection con) throws Throwable {
    int ID = -1;
    try(
      PreparedStatement s = con.prepareStatement("INSERT INTO webctrl.servers VALUES(DEFAULT,?,?,?,inet_client_addr(),CURRENT_TIMESTAMP,?,?,'') RETURNING \"id\";");
    ){
      s.setString(1,serverName);
      s.setString(2,Initializer.version);
      s.setString(3,Initializer.addonVersion);
      s.setString(4,Initializer.productName);
      s.setString(5,Initializer.cumUpdates);
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
      Initializer.log("Failed to automatically set server ID.",true);
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
        if (!con.getAutoCommit()){
          con.commit();
        }
      }catch(Throwable t){
        Initializer.logCache.addAll(cache);
        throw t;
      }
    }
  }
  private void fillCache(String query, Statement s) throws Throwable {
    try(
      ResultSet r = s.executeQuery(query);
    ){
      final TableCache cache = new TableCache();
      final ResultSetMetaData meta = r.getMetaData();
      cache.columns = meta.getColumnCount();
      final int[] types = new int[cache.columns];
      int i;
      for (i=0;i<types.length;++i){
        types[i] = meta.getColumnType(i+1);
      }
      cache.data = new ArrayList<String>(cache.columns<<6);
      cache.rows = 0;
      String ss;
      while (r.next()){
        if (Initializer.stop){ return; }
        for (i=0;i<types.length;++i){
          switch (types[i]){
            case java.sql.Types.BOOLEAN: case java.sql.Types.BIT: {
              ss = String.valueOf(r.getBoolean(i+1));
              break;
            }
            default:{
              ss = r.getString(i+1).replace("\r","");
            }
          }
          cache.data.add(ss);
        }
        ++cache.rows;
      }
      TableCache.instance = cache;
    }
  }
}