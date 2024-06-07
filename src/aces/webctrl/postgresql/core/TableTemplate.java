package aces.webctrl.postgresql.core;
import java.util.function.*;
public class TableTemplate {
  public volatile String name = null;
  public volatile String displayName = null;
  public volatile String query = null;
  public volatile String keyColumn = null;
  public volatile String otherColumns = null;
  public volatile BiFunction<Integer,String,String> conversion = null;
  public volatile String header = null;
  public volatile boolean create = true;
  public volatile boolean singleServer = false;
  public TableTemplate(String name, String displayName){
    this.name = name;
    this.displayName = displayName;
  }
}