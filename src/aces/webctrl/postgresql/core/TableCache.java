package aces.webctrl.postgresql.core;
import java.util.*;
import java.util.function.*;
import java.time.*;
public class TableCache {
  public final static HashMap<String,TableTemplate> tables = new HashMap<String,TableTemplate>();
  static {
    final BiFunction<Integer,String,String> basic = new BiFunction<Integer,String,String>(){
      @Override public String apply(Integer i, String s){
        return Utility.escapePostgreSQL(s);
      }
    };
    TableTemplate x;
    {
      //addon_blacklist
      x = new TableTemplate("addon_blacklist", "Add-On Blacklist");
      x.keyColumn = "name";
      x.otherColumns = "\"min_webctrl_version\",\"max_webctrl_version\"";
      x.query = "SELECT * FROM webctrl.addon_blacklist ORDER BY \"name\";";
      x.conversion = basic;
      x.header = Utility.format(
        "[\"Name\",\"Minimum WebCTRL Version\",\"Maximum WebCTRL Version\"],[\"$0\",\"$1\",\"$2\"]",
        "^.+$",
        Utility.escapeJSON("^$|^\\d+(\\.\\d+)*$"),
        Utility.escapeJSON("^$|^\\d+(\\.\\d+)*$")
      );
      tables.put(x.name,x);
    }
    {
      //addon_whitelist
      x = new TableTemplate("addon_whitelist", "Add-On Whitelist");
      x.keyColumn = "name";
      x.otherColumns = "\"version\",\"keep_newer\",\"download_path\",\"min_webctrl_version\",\"max_webctrl_version\",\"clear_data\"";
      x.query = "SELECT * FROM webctrl.addon_whitelist ORDER BY \"name\";";
      x.conversion = new BiFunction<Integer,String,String>(){
        @Override public String apply(Integer i, String s){
          if (i==2 || i==6){
            return s.equals("1") || s.equalsIgnoreCase("true") ? "TRUE":"FALSE";
          }else{
            return Utility.escapePostgreSQL(s);
          }
        }
      };
      x.header = Utility.format(
        "[\"Name\",\"Version\",\"Keep Newer\",\"Download Path\",\"Minimum WebCTRL Version\",\"Maximum WebCTRL Version\",\"Clear Data\"],[\"$0\",\"$1\",\"$2\",\"$3\",\"$4\",\"$5\",\"$6\"]",
        "^.+$",
        Utility.escapeJSON("^\\d+(\\.\\d+)*$"),
        Utility.escapeJSON("^true$|^false$"),
        "^.+$",
        Utility.escapeJSON("^$|^\\d+(\\.\\d+)*$"),
        Utility.escapeJSON("^$|^\\d+(\\.\\d+)*$"),
        Utility.escapeJSON("^true$|^false$")
      );
      tables.put(x.name,x);
    }
    {
      //operator_blacklist
      x = new TableTemplate("operator_blacklist", "Operator Blacklist");
      x.keyColumn = "username";
      x.query = "SELECT * FROM webctrl.operator_blacklist ORDER BY \"username\";";
      x.header = "[\"Username\"],[\"^.+$\"]";
      tables.put(x.name,x);
    }
    {
      //operator_whitelist
      x = new TableTemplate("operator_whitelist", "Operator Whitelist");
      x.keyColumn = "username";
      x.otherColumns = "\"display_name\",\"password\",\"lvl5_auto_logout\",\"lvl5_auto_collapse\"";
      x.query = "SELECT * FROM webctrl.operator_whitelist ORDER BY \"username\";";
      x.header = Utility.format(
        "[\"Username\",\"Display Name\",\"Password\",\"Session Timeout (seconds)\",\"Auto-Collapse Trees\"],[\"$0\",\"$1\",\"$2\",\"$3\",\"$4\"]",
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
      x.header = "[\"Key\",\"Value\"],[\"^.+$\",\"\"]";
      x.conversion = basic;
      tables.put(x.name,x);
    }
    {
      //log
      String timezone = OffsetDateTime.now().getOffset().getId();
      {
        char c = timezone.charAt(0);
        if (c=='-'){
          timezone = '+'+timezone.substring(1);
        }else if (c=='+'){
          timezone = '-'+timezone.substring(1);
        }
      }
      x = new TableTemplate("log", "Log");
      x.query = "SELECT DATE_TRUNC('seconds', \"time\" AT TIME ZONE '"+timezone+"')::TEXT, \"error\", \"message\" FROM webctrl.log WHERE \"server_id\" = $ID AND \"time\"+(INTERVAL '2 days')>CURRENT_TIMESTAMP ORDER BY \"time\" DESC;";
      x.header = "[\"Timestamp\",\"Error\",\"Message\"]";
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