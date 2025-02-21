package aces.webctrl.postgresql.core;
import java.nio.file.*;
import java.sql.*;
import javax.servlet.*;
import java.util.*;
import java.util.regex.*;
import java.util.concurrent.*;
import com.controlj.green.addonsupport.*;
import com.controlj.green.addonsupport.access.*;
import com.controlj.green.common.CJProduct;
import com.controlj.green.common.CJProductDirectories;
import com.controlj.green.update.*;
import com.controlj.green.update.entries.*;
public class Initializer implements ServletContextListener {
  /** Leave this on when you want to have a preset configuration built-in to the add-on. */
  private final static boolean EMBEDDED_CONNECTION = true;
  /** Name of the addon used for auto udpates */
  public final static String AUTO_UPDATE_ADDON = "AutoUpdater";
  /** The version of this addon */
  public volatile static String addonVersion;
  /** Contains basic information about this addon */
  public volatile static AddOnInfo info = null;
  /** The name of this addon */
  private volatile static String name;
  /** Prefix used for constructing relative URL paths */
  private volatile static String prefix;
  /** Path to the private directory for this addon */
  private volatile static Path root;
  /** Path to the root SSL certificate for the PostgreSQL database */
  public volatile static Path pgsslroot;
  /** Path to SSL key file for the PostgreSQL database */
  public volatile static Path pgsslkey;
  /** Path to the SSL key file to use for SFTP */
  public volatile static Path sftpkey;
  /** Path to a temporary file used when downloading addons */
  public volatile static Path tmpAddonFile;
  /** Path to the directory containing addons */
  public volatile static Path addonsDir;
  /** Path to the WebCTRL system license file */
  public volatile static Path licenseFile = null;
  /** Path to WebCTRL's installation directory */
  public volatile static Path installDir = null;
  /** Path to WebCTRL's active system directory */
  public volatile static Path systemDir = null;
  /** Contains the version of this WebCTRL server */
  public volatile static String version = null;
  /** Contains the truncated version of this WebCTRL server */
  public volatile static String simpleVersion = null;
  /** Contains the product.name of this WebCTRL server */
  public volatile static String productName = null;
  /** Contains an identifier for the latest applied cumulative update */
  public volatile static String cumUpdates = null;
  /** Logger for this addon */
  private volatile static FileLogger logger;
  /** Status message to display on the main page */
  public volatile static String status = "Initializing";
  /** The main processing thread */
  private volatile static Thread mainThread = null;
  /** Whether the hostname is valid */
  public volatile static boolean hostValid = true;
  /** Becomes true when the servlet context is destroyed */
  public volatile static boolean stop = false;
  /** Used to initiate immediate manual syncs. */
  private final static Object syncNotifier = new Object();
  /** Whether to initiate an immediate sync. */
  private volatile static boolean syncNow = true;
  /** Specifies how long to wait in between querying for something new to do. */
  private final static long timeout = 300000L;
  /** Specifies the next epoch milliseconds value to auto-save data at. */
  private volatile static long nextSave = 0;
  /** Stores log messages before sending them to the database. */
  public final static ConcurrentLinkedQueue<LogMessage> logCache = new ConcurrentLinkedQueue<LogMessage>();
  /** Root system connection to the database. */
  private volatile static SystemConnection con = null;
  /**
   * Entry point of this add-on.
   */
  @Override public void contextInitialized(ServletContextEvent sce){
    info = AddOnInfo.getAddOnInfo();
    addonVersion = info.getVersionString();
    name = info.getName();
    prefix = '/'+name+'/';
    root = info.getPrivateDir().toPath();
    tmpAddonFile = root.resolve("tmp.addon");
    {
      final ServerVersion sv = info.getServerVersion();
      version = sv.getBuildNumber();
      simpleVersion = sv.getVersionNumber();
    }
    productName = CJProduct.getDistName();
    logger = info.getDateStampLogger();
    addonsDir = HelperAPI.getAddonsDirectory().toPath();
    try{
      installDir = CJProductDirectories.getBaseDir().toPath();
      systemDir = root.getParent().getParent().getParent();
      licenseFile = CJProductDirectories.getProgramDataDir().toPath().resolve("licenses").resolve("license.properties");
      if (!Files.exists(licenseFile)){
        final Path p = CJProductDirectories.getPropertiesDir().toPath().resolve("license.properties");
        if (Files.exists(p)){
          licenseFile = p;
        }
      }
    }catch(Throwable t){
      try{
        licenseFile = CJProductDirectories.getPropertiesDir().toPath().resolve("license.properties");
      }catch(Throwable tt){
        log(tt);
      }
    }
    {
      int x = -1;
      try{
        String s;
        int y;
        final UpdateManager mgr = UpdateManagerFactory.getSingletonInstance();
        if (mgr.haveAnyUpdatesBeenApplied()){
          final Pattern p = Pattern.compile("(?:-\\d++_)?+WS\\d++(\\.\\d++)++$", Pattern.CASE_INSENSITIVE);
          final Pattern p2 = Pattern.compile("\\D");
          for (UpdateInfo info: mgr.getUpdateInfo().getSortedUpdateInfoOfType(UpdateType.Patch)){
            s = info.getFileName();
            if (s.endsWith("_Cumulative.update")){
              s = p.matcher(s.replace("_Cumulative.update", "")).replaceAll("");
              s = p2.matcher(s).replaceAll("");
              try{
                y = Integer.parseInt(s);
                if (y>x){
                  x = y;
                }
              }catch(NumberFormatException e){}
            }
          }
        }
      }catch(Throwable t){
        log(t);
      }
      cumUpdates = x==-1?"":String.valueOf(x);
    }
    Config.init(root.resolve("config.dat"));
    HostnameVerifier.init(root.resolve("hostname.dat"));
    SSHProxy.init(root.resolve("sshproxy.dat"), root.resolve("sshproxy.key"));
    pgsslroot = root.resolve("pgsslroot.cer");
    pgsslkey = root.resolve("pgsslkey.pfx");
    sftpkey = root.resolve("id_rsa");
    if (EMBEDDED_CONNECTION){
      try{
        if (!Files.exists(pgsslroot)){
          Utility.extractResource("aces/webctrl/postgresql/resources/pgsslroot.cer", pgsslroot);
        }
        if (!Files.exists(pgsslkey)){
          Utility.extractResource("aces/webctrl/postgresql/resources/pgsslkey.pfx", pgsslkey);
        }
        if (!Files.exists(Config.file)){
          Utility.extractResource("aces/webctrl/postgresql/resources/config.dat", Config.file);
        }
      }catch(Throwable t){
        log(t);
      }
    }
    Config.load();
    TableCache.init();
    try{
      Class.forName("org.postgresql.Driver");
    }catch(Throwable t){
      log(t);
    }
    mainThread = new Thread(){
      public void run(){
        try{
          if (Files.exists(addonsDir.resolve(Initializer.AUTO_UPDATE_ADDON+".addon"))){
            Sync.delayUpdate = true;
          }
          final Path certSigner = addonsDir.resolve("ACES.cer");
          if (!Files.exists(certSigner)){
            Utility.extractResource("aces/webctrl/postgresql/resources/ACES.cer", certSigner);
          }
        }catch(Throwable t){
          log(t);
        }
        if (stop){
          return;
        }
        try{
          Thread.sleep(10000L);
        }catch(Throwable t){}
        long time, x;
        boolean b;
        while (!stop){
          try{
            while (!stop){
              time = System.currentTimeMillis();
              if (time>=nextSave){
                Config.save();
                nextSave = time+86400000L;
                if (stop){ break; }
              }
              if (syncNow || (x=Config.cron.getNext())!=-1 && time>=x){
                HelperAPI.removeAddon(AUTO_UPDATE_ADDON, true);
                b = Sync.lastGeneralSyncSuccessful;
                status = "Syncing...";
                new Sync(Event.GENERAL);
                if (b && !Sync.lastGeneralSyncSuccessful){
                  Config.cron.setNext(System.currentTimeMillis()+300000L);
                }else{
                  Config.cron.reset();
                }
                syncNow = false;
                if (stop){ break; }
              }
              x = Config.cron.getNext();
              x = x==-1?timeout:Math.min(timeout,x-System.currentTimeMillis());
              if (x>0){
                synchronized (syncNotifier){
                  if (!syncNow){
                    syncNotifier.wait(x);
                  }
                }
              }
            }
          }catch(InterruptedException e){}
        }
      }
    };
    status = "Initialized";
    log("Initialized successfully (v"+addonVersion+").");
    hostValid = HostnameVerifier.verify();
    stop|=!hostValid;
    if (!hostValid){
      log("Hostname verification failed.", true);
      status = "Hostname verification failed.";
      mainThread = null;
      return;
    }
    mainThread.start();
  }
  /**
   * Kills the primary processing thread and releases all resources.
   */
  @Override public void contextDestroyed(ServletContextEvent sce){
    stop = true;
    if (mainThread==null){
      Config.save();
    }else{
      mainThread.interrupt();
      synchronized (syncNotifier){
        syncNotifier.notifyAll();
      }
      Config.save();
      //Wait for the primary processing thread to terminate.
      while (true){
        try{
          mainThread.join();
          break;
        }catch(InterruptedException e){}
      }
    }
    log("Execution terminated.");
    new Sync(Event.SHUTDOWN);
    TunnelSSH.close();
    // We deregister the PostgreSQL driver so that Tomcat does not complain
    final ClassLoader cl = Thread.currentThread().getContextClassLoader();
    final Enumeration<Driver> drivers = DriverManager.getDrivers();
    Driver driver;
    while (drivers.hasMoreElements()) {
      driver = drivers.nextElement();
      if (driver.getClass().getClassLoader()==cl) {
        try{
          DriverManager.deregisterDriver(driver);
        }catch(Throwable t){
          log(t);
        }
      }
    }
  }
  /**
   * @return whether debug mode is enabled.
   */
  public static boolean debug(){
    return "true".equalsIgnoreCase(Sync.settings.get("debug"));
  }
  /**
   * Tells the processing thread to invoke a synchronization event ASAP.
   */
  public static void syncNow(){
    syncNow = true;
    synchronized (syncNotifier){
      syncNotifier.notifyAll();
    }
  }
  /**
   * @return the root system connection used by this application.
   */
  public static SystemConnection getConnection(){
    if (con==null){
      con = DirectAccess.getDirectAccess().getRootSystemConnection();
    }
    return con;
  }
  /**
   * @return the name of this application.
   */
  public static String getName(){
    return name;
  }
  /**
   * @return the prefix used for constructing relative URL paths.
   */
  public static String getPrefix(){
    return prefix;
  }
  /** Utility variable for checking the log message cache size every so often */
  private volatile static int logCounter = 0;
  /**
   * Ensures the log cache does not grow too large
   */
  private static void checkLogCache(){
    if (++logCounter==128){
      logCounter = 0;
      int s;
      if ((s=logCache.size())>256){
        for (;s>128;--s){
          if (logCache.poll()==null){
            break;
          }
        }
      }
    }
  }
  /**
   * Logs a message.
   */
  public synchronized static void log(String str){
    logCache.add(new LogMessage(str));
    logger.println(str);
    checkLogCache();
  }
  /**
   * Logs a message.
   */
  public synchronized static void log(String str, boolean error){
    logCache.add(new LogMessage(str, error));
    logger.println(str);
    checkLogCache();
  }
  /**
   * Logs an error.
   */
  public synchronized static void log(Throwable t){
    logCache.add(new LogMessage(t));
    logger.println(t);
    checkLogCache();
  }
}