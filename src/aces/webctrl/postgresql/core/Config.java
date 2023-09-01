package aces.webctrl.postgresql.core;
import java.nio.*;
import java.nio.file.*;
import java.nio.channels.*;
public class Config {
  private volatile static Path file = null;
  public volatile static String connectionURL = null;
  public volatile static String username = null;
  public volatile static String password = null;
  public volatile static int ID;
  public final static CronExpression cron = new CronExpression();
  static { revertDefaults(); }
  private static void revertDefaults(){
    ID = -1;
    //connectionURL = "pgsql-analytics.postgres.database.azure.com:5432/analytics";
    //username = "webctrl@pgsql-analytics";
    //password = "@1C5E8S5!";
    connectionURL = "";
    username = "";
    password = "";
    cron.set("0 0 * * * *");
  }
  private Config(){}
  /**
   * Invoke this whenever {@code connectionURL} is changed.
   */
  public static void reset(){
    ID = -1;
    Sync.versionCompatible = false;
    Sync.started = false;
  }
  public static boolean init(Path file){
    Config.file = file;
    return load();
  }
  public static boolean load(){
    if (file==null){
      return false;
    }
    try{
      if (Files.exists(file)){
        byte[] arr;
        synchronized(Config.class){
          arr = Files.readAllBytes(file);
        }
        try{
          final SerializationStream s = new SerializationStream(arr);
          ID = s.readInt();
          /*final String version =*/ s.readString();
          connectionURL = s.readString();
          username = s.readString();
          password = s.readString();
          cron.set(s.readString());
          if (!s.end()){
            Initializer.log("Configuration file corrupted. Parameters reverted to defaults.");
            revertDefaults();
          }
        }catch(Throwable t){
          Initializer.log("Configuration file corrupted. Parameters reverted to defaults.");
          Initializer.log(t);
          revertDefaults();
        }
      }
      return true;
    }catch(Throwable t){
      Initializer.log(t);
      return false;
    }
  }
  public static boolean save(){
    if (file==null){
      return false;
    }
    try{
      String cronExpr = cron.toString();
      if (cronExpr==null){ cronExpr = ""; }
      int l = 4;
      l+=Initializer.addonVersion.length();
      l+=connectionURL.length();
      l+=username.length();
      l+=password.length();
      l+=cronExpr.length();
      l<<=1;
      l+=4;
      final SerializationStream s = new SerializationStream(l, true);
      s.write(ID);
      s.write(Initializer.addonVersion);
      s.write(connectionURL);
      s.write(username);
      s.write(password);
      s.write(cronExpr);
      final ByteBuffer buf = s.getBuffer();
      synchronized(Config.class){
        try(
          FileChannel out = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        ){
          while (buf.hasRemaining()){
            out.write(buf);
          }
        }
      }
      return true;
    }catch(Throwable t){
      Initializer.log(t);
      return false;
    }
  }
}