package aces.webctrl.postgresql.core;
import java.util.*;
import java.util.function.*;
import java.time.*;
public class TableCache {
  public final static HashMap<String,TableTemplate> tables = new HashMap<String,TableTemplate>();
  public static void init(){
    final BiFunction<Integer,String,String> basic = new BiFunction<Integer,String,String>(){
      @Override public String apply(Integer i, String s){
        return Utility.escapePostgreSQL(s);
      }
    };
    String timezone = OffsetDateTime.now().getOffset().getId();
    {
      char c = timezone.charAt(0);
      if (c=='-'){
        timezone = '+'+timezone.substring(1);
      }else if (c=='+'){
        timezone = '-'+timezone.substring(1);
      }
    }
    TableTemplate x;
    {
      //servers
      x = new TableTemplate("servers", "Servers");
      x.keyColumn = "id";
      x.otherColumns = "\"name\",\"notes\"";
      x.query = "SELECT \"id\", \"name\", \"version\", \"addon_version\", host(\"ip_address\"), "+
        "'background-color:'|| CASE WHEN \"last_sync\"+(INTERVAL '4 HOURS')>CURRENT_TIMESTAMP THEN 'darkgreen' ELSE 'darkred' END ||'|'||DATE_TRUNC('seconds', \"last_sync\" AT TIME ZONE '"+timezone+"')::TEXT, "+
        "'<a target=\"_blank\" href=\""+Initializer.getPrefix()+"DownloadLicense?id='||\"id\"||'\" download=\"license-'||\"id\"||'.properties\">'||REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(\"product_name\",'&','&amp;'),'\"','&quot;'),'''','&apos;'),'<','&lt;'),'>','&gt;')||'</a>', "+
        "\"cum_updates\", \"notes\" FROM webctrl.servers ORDER BY \"last_sync\"+(INTERVAL '4 HOURS')>CURRENT_TIMESTAMP DESC, STRING_TO_ARRAY(REGEXP_REPLACE(REPLACE(\"version\",'-','.'),'[^\\d\\.]','','g'), '.')::int[] DESC, \"cum_updates\" DESC, \"name\" ASC;";
      x.conversion = basic;
      x.header = Utility.format(
        "[\"ID\",\"Name\",\"WebCTRL Version\",\"Add-On Version\",\"IP Address\",\"Last Sync\",\"License\",\"Cumulative Update\",\"Notes\"],[\"Internal ID which uniquely identifies the server.\",\"User-friendly display name for the server.\",\"Full version string for the WebCTRL server.\",\"Installed version of the PostgreSQL_Connect add-on.\",\"External IP address of the server as viewed by the PostgreSQL database.\",\"Timestamp of the last successful synchronization. If synced within the last 24 hours, the background color is green.\",\"Click to download the WebCTRL license.\",\"Latest applied cumulative update.\",\"Notes pertaining to this server.\"],[\"$0\",\"$1\",\"$2\",\"$3\",\"$4\",\"$5\",\"$6\",\"$7\",\"\"]",
        "<READONLY>",
        "^.+$",
        "<READONLY>",
        "<READONLY>",
        "<READONLY>",
        "<READONLY><STYLE>",
        "<READONLY><HTML>",
        "<READONLY>"
      );
      x.create = false;
      tables.put(x.name,x);
    }
    {
      //addon_blacklist
      x = new TableTemplate("addon_blacklist", "Add-On Blacklist");
      x.keyColumn = "name";
      x.otherColumns = "\"min_webctrl_version\",\"max_webctrl_version\",\"clear_data\"";
      x.query = "SELECT * FROM webctrl.addon_blacklist ORDER BY \"name\";";
      x.conversion = new BiFunction<Integer,String,String>(){
        @Override public String apply(Integer i, String s){
          if (i==3){
            return s.equals("1") || s.equalsIgnoreCase("true") ? "TRUE":"FALSE";
          }else{
            return Utility.escapePostgreSQL(s);
          }
        }
      };
      x.header = Utility.format(
        "[\"Name\",\"Min. Version\",\"Max. Version\",\"Clear Data\"],[\"Reference name to uniquely identity the add-on.\",\"If the WebCTRL version is less than this value, the add-on will not be removed.\",\"If the WebCTRL version is greater than this value, the add-on will not be removed.\",\"Whether to delete data upon add-on removal.\"],[\"$0\",\"$1\",\"$2\",\"$3\"]",
        "^.+$",
        Utility.escapeJSON("^$|^\\d+(\\.\\d+)*$"),
        Utility.escapeJSON("^$|^\\d+(\\.\\d+)*$"),
        Utility.escapeJSON("^true$|^false$")
      );
      tables.put(x.name,x);
    }
    {
      //addon_whitelist
      x = new TableTemplate("addon_whitelist", "Add-On Whitelist");
      x.keyColumn = "name";
      x.otherColumns = "\"version\",\"keep_newer\",\"download_path\",\"min_webctrl_version\",\"max_webctrl_version\",\"clear_data\",\"optional\",\"description\"";
      x.query = "SELECT\n"+
        "  \"a\".\"name\",\n"+
        "  \"a\".\"version\",\n"+
        "  CASE\n"+
        "    WHEN \"b\".\"download_path\" IS NOT NULL THEN '<button onclick=\"sendAJAX(this, ''InstallAddon'', ''Attempting install...'', 20000, '''||\"a\".\"download_path\"||''')\">Install</button>'\n"+
        "    ELSE 'N/A'\n"+
        "  END,\n"+
        "  \"a\".\"keep_newer\",\n"+
        "  \"a\".\"download_path\",\n"+
        "  \"a\".\"min_webctrl_version\",\n"+
        "  \"a\".\"max_webctrl_version\",\n"+
        "  \"a\".\"clear_data\",\n"+
        "  \"a\".\"optional\",\n"+
        "  \"a\".\"description\"\n"+
        "FROM webctrl.addon_whitelist \"a\" LEFT JOIN (\n"+
        "  SELECT DISTINCT\n"+
        "    \"a\".\"download_path\"\n"+
        "  FROM (\n"+
        "    SELECT\n"+
        "      \"download_path\",\n"+
        "      SUBSTRING(LOWER(\"download_path\") from '[/\\\\]([^/\\\\]+)\\.addon$') AS \"name\"\n"+
        "    FROM webctrl.addon_whitelist\n"+
        "    WHERE \"optional\"\n"+
        "  ) \"a\" LEFT JOIN (\n"+
        "    SELECT LOWER(\"name\") AS \"name\" FROM webctrl.addons\n"+
        "    WHERE \"server_id\" = $ID\n"+
        "  ) \"b\"\n"+
        "  ON \"a\".\"name\" = \"b\".\"name\"\n"+
        "  WHERE \"b\".\"name\" IS NULL\n"+
        ") \"b\"\n"+
        "ON \"a\".\"download_path\" = \"b\".\"download_path\"\n"+
        "ORDER BY \"a\".\"name\";";
      x.conversion = new BiFunction<Integer,String,String>(){
        @Override public String apply(Integer i, String s){
          if (i==2 || i==6 || i==7){
            return s.equals("1") || s.equalsIgnoreCase("true") ? "TRUE":"FALSE";
          }else{
            return Utility.escapePostgreSQL(s);
          }
        }
      };
      x.header = Utility.format(
        "[\"Name\",\"Version\",\"Install\",\"Keep Newer\",\"Download Path\",\"Min Version\",\"Max Version\",\"Clear Data\",\"Optional\",\"Description\"],[\"Display name for the add-on.\",\"Version of the add-on stored on the FTP server. Please strip non-numeric characters out of the version string before inserting it here. For example, 'v0.1.0-beta' should turn into '0.1.0'.\",\"Click to install this optional add-on.\",\"Whether to allow newer versions of the add-on to be installed. If 'false', then newer versions will be downgraded.\",\"FTP server path used to download the add-on. For example: '/webctrl/addons/test.addon'\",\"If the WebCTRL version is less than this value, the add-on will not be installed.\",\"If the WebCTRL version is greater than this value, the add-on will not be installed.\",\"Whether to delete data upon add-on removal.\",\"If 'true', existing add-on installions will be upgraded to the version stored here, but the add-on will not be installed on servers that do not already have it.\",\"Descriptive details for this add-on.\"],[\"$0\",\"$1\",\"<READONLY><HTML>\",\"$2\",\"$3\",\"$4\",\"$5\",\"$6\",\"$7\",\"\"]",
        "^.+$",
        Utility.escapeJSON("^\\d+(\\.\\d+)*$"),
        Utility.escapeJSON("^true$|^false$"),
        Utility.escapeJSON("^[^\\\\&<>'\"]+$"),
        Utility.escapeJSON("^$|^\\d+(\\.\\d+)*$"),
        Utility.escapeJSON("^$|^\\d+(\\.\\d+)*$"),
        Utility.escapeJSON("^true$|^false$"),
        Utility.escapeJSON("^true$|^false$")
      );
      tables.put(x.name,x);
    }
    {
      //operator_blacklist
      x = new TableTemplate("operator_blacklist", "Operator Blacklist");
      x.keyColumn = "username";
      x.query =
        "SELECT\n"+
        "  \"x\".\"username\",\n"+
        "  COALESCE(\"y\".\"exceptions\", 0) AS \"exceptions\"\n"+
        "FROM webctrl.operator_blacklist \"x\" LEFT JOIN (\n"+
        "  SELECT \"username\", COUNT(*) AS \"exceptions\"\n"+
        "  FROM webctrl.operator_blacklist_exceptions\n"+
        "  GROUP BY \"username\"\n"+
        ") \"y\" ON \"x\".\"username\" = \"y\".\"username\"\n"+
        "ORDER BY \"x\".\"username\";";
      x.header = "[\"Username\",\"Exceptions\"],[\"The username to be blacklisted.\",\"Counts the number of WebCTRL servers which have an exception allowing an operator with this username to exist.\"],[\"^.+$\",\"<READONLY>0\"]";
      x.conversion = basic;
      tables.put(x.name,x);
    }
    {
      //operator_blacklist_exceptions
      x = new TableTemplate("operator_blacklist_exceptions", "Operator Blacklist Exceptions");
      x.keyColumn = "username";
      x.query = "SELECT \"username\" FROM webctrl.operator_blacklist_exceptions WHERE \"server_id\" = $ID ORDER BY \"username\";";
      x.header = "[\"Username\"],[\"Specifies a blacklisted username which should be allowed to exist on this WebCTRL server.\"],[\"^.+$\"]";
      x.conversion = basic;
      x.singleServer = true;
      tables.put(x.name,x);
    }
    {
      //operator_whitelist
      x = new TableTemplate("operator_whitelist", "Operator Whitelist");
      x.keyColumn = "username";
      x.otherColumns = "\"display_name\",\"password\",\"lvl5_auto_logout\",\"lvl5_auto_collapse\"";
      x.query = "SELECT * FROM webctrl.operator_whitelist ORDER BY \"username\";";
      x.header = Utility.format(
        "[\"Username\",\"Display Name\",\"Password Hash\",\"Session Timeout\",\"Auto-Collapse Trees\"],[\"Username to uniquely identify this user.\",\"User-friendly display name.\",\"A password hash for the user. You can enter a plaintext password into this field, and it will be automatically hashed when you submit the form.\",\"Specifies how many seconds to wait before automatically logging this user out. 0 disables automatic logoff. -1 uses the system default.\",\"This pertains to the geographic and network trees. If 'true', previously expanded nodes will collapse when you try to expand an unrelated node. If 'false', you can have as many nodes expanded at the same time as you like.\"],[\"$0\",\"$1\",\"$2\",\"$3\",\"$4\"]",
        "^.+$","^.+$","",
        Utility.escapeJSON("^-1$|^\\d+$"),
        Utility.escapeJSON("^true$|^false$")
      );
      x.conversion = new BiFunction<Integer,String,String>(){
        @Override public String apply(Integer i, String s){
          if (i==4){
            return s.equals("1") || s.equalsIgnoreCase("true") ? "TRUE":"FALSE";
          }else if (i==3){
            try{
              return String.valueOf(Integer.parseInt(s));
            }catch(NumberFormatException e){
              return "-1";
            }
          }else{
            return Utility.escapePostgreSQL(s);
          }
        }
      };
      tables.put(x.name,x);
    }
    {
      //settings
      x = new TableTemplate("settings", "Settings");
      x.keyColumn = "name";
      x.otherColumns = "\"value\"";
      x.query = "SELECT * FROM webctrl.settings ORDER BY \"name\";";
      x.header = "[\"Key\",\"Value\"],[\"\",\"\"],[\"^.+$\",\"\"]";
      x.conversion = basic;
      tables.put(x.name,x);
    }
    {
      //log
      x = new TableTemplate("log", "Log");
      x.query = "SELECT DATE_TRUNC('seconds', \"time\" AT TIME ZONE '"+timezone+"')::TEXT, \"error\", \"message\" FROM webctrl.log WHERE \"server_id\" = $ID AND \"time\"+(INTERVAL '2 days')>CURRENT_TIMESTAMP ORDER BY \"time\" DESC;";
      x.header = "[\"Timestamp\",\"Error\",\"Message\"]";
      tables.put(x.name,x);
    }
    {
      //trend_mappings
      x = new TableTemplate("trend_mappings", "Trend Mappings");
      x.keyColumn = "id";
      x.otherColumns = "\"server_id\",\"name\",\"persistent_identifier\",\"retain_data\",\"field_access\"";
      x.query =
        "SELECT\n"+
        "  \"x\".\"id\",\n"+
        "  \"x\".\"server_id\",\n"+
        "  COALESCE(\"z\".\"name\", 'N/A') AS \"server_name\",\n"+
        "  \"x\".\"name\",\n"+
        "  \"x\".\"persistent_identifier\",\n"+
        "  \"x\".\"retain_data\",\n"+
        "  \"x\".\"field_access\",\n"+
        "  COALESCE(\"y\".\"sample_count\", 0) AS \"sample_count\",\n"+
        "  COALESCE(DATE_TRUNC('seconds', \"y\".\"first_sample\" AT TIME ZONE '"+timezone+"')::TEXT, 'N/A') AS \"first_sample\",\n"+
        "  COALESCE(DATE_TRUNC('seconds', \"y\".\"last_sample\" AT TIME ZONE '"+timezone+"')::TEXT, 'N/A') AS \"last_sample\"\n"+
        "FROM webctrl.trend_mappings \"x\"\n"+
        "LEFT JOIN (\n"+
        "  SELECT\n"+
        "    \"id\",\n"+
        "    MIN(\"time\") AS \"first_sample\",\n"+
        "    MAX(\"time\") AS \"last_sample\",\n"+
        "    COUNT(*) AS \"sample_count\"\n"+
        "  FROM webctrl.trend_data\n"+
        "  GROUP BY \"id\"\n"+
        ") \"y\"\n"+
        "ON \"x\".\"id\" = \"y\".\"id\"\n"+
        "LEFT JOIN (\n"+
        "  SELECT \"id\", \"name\" FROM webctrl.servers\n"+
        ") \"z\"\n"+
        "ON \"x\".\"server_id\" = \"z\".\"id\"\n"+
        "ORDER BY \"x\".\"server_id\";";
      x.header = "[\"Trend ID\",\"Server ID\",\"Server Name\",\"Name\",\"Persistent Identifier\",\"Retain Data\",\"Field Access\",\"Sample Count\",\"First Sample\",\"Last Sample\"],[\"Unique identifier for this trend mapping.\",\"Unique identifier for the WebCTRL server.\",\"User-friendly name of the WebCTRL server.\",\"User-friendly name to identity the trend mapping.\",\"Unique identifier for the microblock value to be trended. Use the 'Find Trends' page to retrieve this.\",\"Specifies how many days of historical data should be kept in the database.\",\"Whether to use field access when gathering trend data. If field access is disabled, then data collection will be faster, but it may not include the most up-to-date samples available.\",\"Number of samples stored in the database.\",\"Timestamp of the oldest sample.\",\"Timestamp of the most recent sample.\"],"+
        "[\"<READONLY>N/A\",\"^\\\\d*$\",\"<READONLY>N/A\",\"^.+$\",\"^.+$\",\"^\\\\d+$\",\"^true$|^false$\",\"<READONLY>0\",\"<READONLY>N/A\",\"<READONLY>N/A\"]";
      x.conversion = new BiFunction<Integer,String,String>(){
        @Override public String apply(Integer i, String s){
          if (i==0){
            return s.equalsIgnoreCase("N/A")?"DEFAULT":String.valueOf(Integer.parseInt(s));
          }else if (i==1){
            return String.valueOf(s.isEmpty()?Config.ID:Integer.parseInt(s));
          }else if (i==4){
            return String.valueOf(Integer.parseInt(s));
          }else if (i==5){
            return s.equals("1") || s.equalsIgnoreCase("true") ? "TRUE":"FALSE";
          }else{
            return Utility.escapePostgreSQL(s);
          }
        }
      };
      tables.put(x.name,x);
    }
    {
      //pending_commands
      x = new TableTemplate("pending_commands", "Pending Commands");
      x.keyColumn = "id";
      x.otherColumns = "\"server_id\",\"ordering\",\"command\"";
      x.query =
        "SELECT\n"+
        "  \"x\".\"id\",\n"+
        "  \"x\".\"server_id\",\n"+
        "  COALESCE(\"y\".\"name\", 'N/A') AS \"server_name\",\n"+
        "  \"x\".\"ordering\",\n"+
        "  \"x\".\"command\"\n"+
        "FROM webctrl.pending_commands \"x\"\n"+
        "LEFT JOIN (\n"+
        "  SELECT \"id\", \"name\" FROM webctrl.servers\n"+
        ") \"y\"\n"+
        "ON \"x\".\"server_id\" = \"y\".\"id\"\n"+
        "ORDER BY \"x\".\"server_id\", \"x\".\"ordering\";";
      x.conversion = new BiFunction<Integer,String,String>(){
        @Override public String apply(Integer i, String s){
          if (i==0){
            return s.equalsIgnoreCase("N/A")?"DEFAULT":String.valueOf(Integer.parseInt(s));
          }else if (i==1){
            return String.valueOf(s.isEmpty()?Config.ID:Integer.parseInt(s));
          }else if (i==2){
            return String.valueOf(s.isEmpty()?1:Integer.parseInt(s));
          }else{
            return Utility.escapePostgreSQL(s);
          }
        }
      };
      x.header = "[\"Command ID\",\"Server ID\",\"Server Name\",\"Ordering\",\"Command\"],[\"Unique identifier for this command.\",\"Unique identifier for the WebCTRL server.\",\"User-friendly name of the WebCTRL server.\",\"Commands are executed in the ascending order specified by this column.\",\"The command(s) to execute. Multiple commands can be separated by newlines for fail-fast semantics.\"],"+
        "[\"<READONLY>N/A\",\"^\\\\d*$\",\"<READONLY>N/A\",\"^\\\\d*$\",\"^.+$\"]";
      tables.put(x.name,x);
    }
    {
      //tunnels
      x = new TableTemplate("tunnels", "SSH Tunnels");
      x.keyColumn = "id";
      x.otherColumns = "\"server_id\",\"src_port\",\"dst_port\",\"desc\"";
      x.query =
        "SELECT\n"+
        "  \"x\".\"id\",\n"+
        "  \"x\".\"server_id\",\n"+
        "  COALESCE(\"y\".\"name\", 'N/A') AS \"server_name\",\n"+
        "  \"x\".\"src_port\",\n"+
        "  \"x\".\"dst_port\",\n"+
        "  \"x\".\"desc\"\n"+
        "FROM webctrl.tunnels \"x\"\n"+
        "LEFT JOIN (\n"+
        "  SELECT \"id\", \"name\" FROM webctrl.servers\n"+
        ") \"y\"\n"+
        "ON \"x\".\"server_id\" = \"y\".\"id\"\n"+
        "ORDER BY \"x\".\"src_port\";";
      x.conversion = new BiFunction<Integer,String,String>(){
        @Override public String apply(Integer i, String s){
          if (i==0){
            return s.equalsIgnoreCase("N/A")?"DEFAULT":String.valueOf(Integer.parseInt(s));
          }else if (i==1){
            return String.valueOf(s.isEmpty()?Config.ID:Integer.parseInt(s));
          }else if (i==2 || i==3){
            int x = s.isEmpty()?1:Integer.parseInt(s);
            if (x<1){
              x = 1;
            }else if (x>65535){
              x = 65535;
            }
            return String.valueOf(x);
          }else{
            return Utility.escapePostgreSQL(s);
          }
        }
      };
      x.header = "[\"Tunnel ID\",\"Server ID\",\"Server Name\",\"Source Port\",\"Destination Port\",\"Description\"],[\"Unique identifier for this command.\",\"Unique identifier for the WebCTRL server.\",\"User-friendly name of the WebCTRL server.\",\"Listening port to open on the SSH server (TCP only).\",\"Destination port to forward to on the WebCTRL server.\",\"Brief description of the tunnel's purpose.\"],"+
        "[\"<READONLY>N/A\",\"^\\\\d*$\",\"<READONLY>N/A\",\"^[1-9][0-9]{0,3}|[1-5][0-9]{4}|6[0-4][0-9]{3}|65[0-4][0-9]{2}|655[0-2][0-9]|6553[0-5]$\",\"^[1-9][0-9]{0,3}|[1-5][0-9]{4}|6[0-4][0-9]{3}|65[0-4][0-9]{2}|655[0-2][0-9]|6553[0-5]$\",\"\"]";
      tables.put(x.name,x);
    }
  }
  volatile static TableCache instance = null;
  public volatile int rows;
  public volatile int columns;
  public ArrayList<String> data;
  public synchronized static TableCache submit(String query){
    instance = null;
    new Sync(Event.SELECT_TABLE, query);
    final TableCache c = instance;
    instance = null;
    return c;
  }
  public synchronized static TableCache update(ArrayList<String> queries){
    final String[] arr = new String[queries.size()];
    for (int i=0;i<arr.length;++i){
      arr[i] = queries.get(i);
    }
    instance = null;
    new Sync(Event.UPDATE_TABLE, arr);
    final TableCache c = instance;
    instance = null;
    return c;
  }
  public String toJSON(final String header){
    final int size = data.size();
    int cap = (size<<2)+16;
    if (header!=null){
      cap+=header.length();
    }
    String s;
    int i;
    for (i=0;i<size;++i){
      s = data.get(i);
      if (s!=null){
        s = Utility.escapeJSON(s);
        cap+=s.length();
      }else{
        s = "";
      }
      data.set(i,s);
    }
    final StringBuilder sb = new StringBuilder(cap);
    sb.append("[\n");
    if (header==null){
      i = 0;
    }else{
      sb.append(header);
      i = -1;
    }
    for (String t:data){
      if (i==-1){
        sb.append(",\n");
        ++i;
      }
      if (i==0){
        sb.append('[');
      }
      sb.append('"').append(t).append('"');
      ++i;
      if (i==columns){
        i = -1;
        sb.append(']');
      }else{
        sb.append(',');
      }
    }
    sb.append("\n]");
    return sb.toString();
  }
}