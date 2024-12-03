package aces.webctrl.postgresql.core;
import java.nio.*;
import java.nio.file.*;
import java.nio.channels.*;
public class Config {
  public volatile static Path file = null;
  public volatile static String connectionURL = null;
  public volatile static String username = null;
  public volatile static String password = null;
  public volatile static String keystorePassword = null;
  public volatile static int ID;
  public final static CronExpression cron = new CronExpression();
  public volatile static long maxRandomOffset;
  static { revertDefaults(); }
  private static void revertDefaults(){
    ID = -1;
    connectionURL = "";
    username = "";
    password = "";
    keystorePassword = "";
    maxRandomOffset = 0L;
    cron.set("0 0 * * * *");
  }
  private Config(){}
  /**
   * Resets the link ID for the database tables
   */
  public static void reset(){
    synchronized (Sync.class){
      new Sync(Event.SHUTDOWN);
      ID = -1;
      Sync.versionCompatible = false;
      Sync.started = false;
    }
    Initializer.log("Server ID has been reset.");
  }
  public static void init(Path file){
    Config.file = file;
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
          //final String version = 
            s.readString();
          connectionURL = s.readString();
          username = s.readString();
          password = s.readString();
          maxRandomOffset = s.readLong();
          cron.set(s.readString());
          keystorePassword = s.readString();
          if (!s.end()){
            Initializer.log("Configuration file corrupted. Parameters reverted to defaults.",true);
            revertDefaults();
          }
        }catch(Throwable t){
          Initializer.log("Configuration file corrupted. Parameters reverted to defaults.",true);
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
      l+=keystorePassword.length();
      l<<=1;
      l+=4;
      final SerializationStream s = new SerializationStream(l, true);
      s.write(ID);
      s.write(Initializer.addonVersion);
      s.write(connectionURL);
      s.write(username);
      s.write(password);
      s.write(maxRandomOffset);
      s.write(cronExpr);
      s.write(keystorePassword);
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
      if (Initializer.debug()){
        Initializer.log("Data file saved.");
      }
      return true;
    }catch(Throwable t){
      Initializer.log(t);
      return false;
    }
  }
}