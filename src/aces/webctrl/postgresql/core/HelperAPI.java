package aces.webctrl.postgresql.core;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import java.io.*;
import java.lang.management.ManagementFactory;
import com.sun.management.OperatingSystemMXBean;
import java.nio.file.*;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.nio.charset.StandardCharsets;
import com.controlj.green.webserver.*;
import com.controlj.green.common.BroadcastNotificationHandler;
import com.controlj.green.common.CJAboutHelper.JvmInfo;
import com.controlj.green.common.CJIO;
import com.controlj.green.common.CJProduct;
import com.controlj.green.common.Feature;
import com.controlj.green.common.CJStringTokenizer;
import com.controlj.green.common.LanguageManager;
import com.controlj.green.core.main.CoreApp;
import com.controlj.green.core.main.CoreApp.ExitCode;
import com.controlj.green.core.main.CoreProduct;
import com.controlj.green.core.repactions.PopupRARequestProcessor;
import com.controlj.green.core.ui.UserSession;
import com.controlj.green.core.data.*;
import com.controlj.green.core.database.*;
import com.controlj.green.core.email.*;
import com.controlj.green.core.database.BaseDB.DatabaseInfo;
import com.controlj.green.core.download.api.TaskSet;
import com.controlj.green.core.gcp.InstanceOverride;
import com.controlj.green.core.gcp.InstanceOverrideParameter.GroupName;
import com.controlj.green.core.bacnet.discovery.DiscoveryUtility;
import com.controlj.green.datatable.util.CoreHelper;
import com.controlj.green.update.*;
import com.controlj.green.update.SystemUpdater.UpdaterInteraction;
import com.controlj.green.update.entries.UpdateInfo;
import com.controlj.green.common.launcher.MonitoredLauncher;
import com.controlj.launcher.ConfigurationDecorator;
public class HelperAPI {
  private final static long timeout = 300L;
  private HelperAPI(){}
  /**
   * Print information about the WebCTRL server to the given StringBuilder.
   */
  @SuppressWarnings("deprecation") // getFreePhysicalMemorySize() and getTotalPhysicalMemorySize()
  public static void about(final StringBuilder sb){
    final StringWriter sw = new StringWriter(512);
    final PrintWriter w = new PrintWriter(sw);
    final CJAboutInfoBackup abinfo = new CJAboutInfoBackup();//had to embed copy of this class for WebCTRL8.0
    final JvmInfo jvm = new JvmInfo();
    w.println("\n-----------------------------------------------------");
    w.println("-     PostgreSQL_Connector Settings");
    w.println("-----------------------------------------------------");
    w.println("          Connection URL: "+Config.connectionURL);
    w.println("                Username: "+Config.username);
    w.println(" Max. Random Sync Offset: "+Config.maxRandomOffset);
    w.println("    Cron Sync Expression: "+Config.cron.toString());
    w.println("-----------------------------------------------------");
    w.println("-     Resource Usage Information");
    w.println("-----------------------------------------------------");
    {
      double free, total, used;
      NumberFormat f = NumberFormat.getNumberInstance();
      f.setMinimumFractionDigits(1);
      f.setMaximumFractionDigits(1);
      w.println(" "+jvm.getJvmInfoText());
      final Runtime run = Runtime.getRuntime();
      try{
        w.println("     Processor Count: "+run.availableProcessors());
        free = 0;
        total = 0;
        boolean lin = "Linux".equalsIgnoreCase(System.getProperty("os.name"));
        if (lin){
          final Path p = Paths.get("/proc/meminfo");
          if (Files.isReadable(p)){
            String data = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("^MemTotal:\\s*+(\\d++)", Pattern.MULTILINE).matcher(data);
            total = m.find()?Long.parseLong(m.group(1))/1024.0:0.0;
            m = Pattern.compile("^MemAvailable:\\s*+(\\d++)", Pattern.MULTILINE).matcher(data);
            free = m.find()?Long.parseLong(m.group(1))/1024.0:0.0;
          }
        }
        {
          final OperatingSystemMXBean osBean = (OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
          if (free==0.0 && !lin){
            free = osBean.getFreePhysicalMemorySize()/1048576.0;
          }
          if (total==0.0){
            total = osBean.getTotalPhysicalMemorySize()/1048576.0;
          }
        }
        used = total-free;
        w.println("    System RAM usage: "+f.format(used/total*100.0)+"%");
        w.println("                      "+f.format(used)+" MB = Used");
        w.println("                      "+f.format(free)+" MB = Free");
        w.println("                      "+f.format(total)+" MB = Total");
      }catch(Throwable t){
        if (Initializer.debug()){
          Initializer.log(t);
        }
        w.println("An error was encountered while retrieving system memory statistics.");
      }
      {
        double heap = run.totalMemory()/1048576.0;
        total = run.maxMemory()/1048576.0;
        used = heap-run.freeMemory()/1048576.0;
        free = total-used;
        w.println("   WebCTRL RAM usage: "+f.format(used/total*100.0)+"%");
        w.println("                      "+f.format(used)+" MB = Used");
        w.println("                      "+f.format(heap)+" MB = Alloc");
        w.println("                      "+f.format(free)+" MB = Available");
        w.println("                      "+f.format(total)+" MB = Total");
        try{
          w.println("                      "+f.format(new ConfigurationDecorator(MonitoredLauncher.loadConfiguration()).getMaxMemory())+" MB = Set");
        }catch(Throwable t){
          if (Initializer.debug()){
            Initializer.log(t);
          }
        }
      }
      String r;
      FileStore fs;
      for (Path root: FileSystems.getDefault().getRootDirectories()){
        r = root.toString();
        try{
          fs = Files.getFileStore(root);
          free = fs.getUsableSpace()/1073741824.0;
          total = fs.getTotalSpace()/1073741824.0;
          used = total-free;
          w.println(" "+r+" Drive");
          w.println("    Capacity = "+f.format(used/total*100.0)+"%");
          w.println("        Used = "+f.format(used)+" GB");
          w.println("        Free = "+f.format(free)+" GB");
          w.println("       Total = "+f.format(total)+" GB");
        }catch(Throwable t){
          if (Initializer.debug()){
            Initializer.log(t);
          }
          w.println(" "+r+" drive could not be queried.");
        }
      }
    }
    w.println("-----------------------------------------------------");
    w.println("-     License and Product Version Information");
    w.println("-----------------------------------------------------");
    abinfo.writeLicenseAndProductInfo(w);
    w.println("-----------------------------------------------------");
    w.println("-     Database Information");
    w.println("-----------------------------------------------------");
    for (BaseDB db : CoreDatabaseServer.getAllDBs()) {
        w.println("Statistics for "+db.getName()+" database:");
        try{
          BaseDB.DatabaseInfo info = db.getInfo();
          w.println("   Product: "+info.databaseProductName+" version "+info.databaseProductVersion);
          w.println("   Driver:  "+info.driverName+" version "+info.driverVersion);
          w.println("   URL:     "+info.url);
        }catch(Throwable t){
          if (Initializer.debug()){
            Initializer.log(t);
          }
          w.println("   An error was encountered while retrieving database information.");
        }
    }
    w.println("-----------------------------------------------------");
    w.println("-     System Updates and Patch Information");
    w.println("-----------------------------------------------------");
    try{
      abinfo.writeUpdateInfo(w);
    }catch(Throwable t){
      if (Initializer.debug()){
        Initializer.log(t);
      }
      w.println("An error was encountered while retrieving patch information.");
    }
    w.println("-----------------------------------------------------");
    w.println("-     JVM Properties");
    w.println("-----------------------------------------------------");
    {
      int i,j;
      String[] jvmAtt;
      String[] jvmVal;
      String s;
      final HashSet<String> ignore = new HashSet<String>();
      ignore.add("java.class.path");
      ignore.add("jarstoskip");
      for (Object[] jvmProp : jvm.getOrderedJavaProperties()) {
        s = (String)jvmProp[0];
        if (ignore.contains(s.toLowerCase())){
          continue;
        }
        jvmAtt = CJStringTokenizer.split(s, 39);
        jvmVal = CJStringTokenizer.split((String)jvmProp[1], 135);
        j = Math.max(jvmAtt.length, jvmVal.length);
        if (j>4){
          continue;
        }
        for (i = 0; i < j; ++i) {
          if (i < jvmAtt.length) {
            if (i < jvmVal.length && jvmVal[i].length()>0){
              w.print(CJIO.formatToLength(jvmAtt[i], 40, true));
            }else{
              w.print(jvmAtt[i]);
            }
          } else {
            w.print(CJIO.formatToLength("", 40, true));
          }
          if (i < jvmVal.length) {
            w.print(jvmVal[i]);
          }
          w.println();
        }
      }
    }
    final StringBuffer buf = sw.getBuffer();
    buf.setLength(buf.length()-1);
    sb.append(Pattern.compile("(?<=[\\r\\n])[\\r\\n]++").matcher(buf.toString().replace("\r","")).replaceAll(""));
  }
  /**
   * Update daylight savings dates in the WebCTRL database and marks controllers for parameter downloads.
   */
  public static boolean updateDST(){
    try(
      OperatorLink lnk = new OperatorLink(false);
    ){
      CoreDataSession cds = lnk.getCoreDataSession();
      if (DiscoveryUtility.isOverridingTimeZone()){
        CoreDatabaseServer.setDefaultDSTentries(DiscoveryUtility.getTimeZone(cds));
      }else{
        CoreDatabaseServer.setDefaultDSTentries();
      }
      CoreHWDevice dev;
      for (CoreNode device:cds.selectNodesByCategory(4352)) {
        dev = (CoreHWDevice)device;
        CoreEquipmentNode driver = dev.getDriver();
        InstanceOverride.setValuesByGroup(dev, GroupName.DST);
        if (cds.hasModifiedNode()){
          driver.markForDownload(TaskSet.PARAM_DOWN);
          cds.commit();
        }
      }
      return true;
    }catch(Throwable t){
      Initializer.log(t);
      return false;
    }
  }
  /**
   * @return Whether the WebCTRL server is able to apply the given update file.
   */
  public static boolean canApplyUpdate(File f){
    try{
      return new SystemUpdater(UpdateManagerFactory.getSingletonInstance(), new UpdaterInteraction(){
        @Override public void reportProductWillBeUpdatedAfterRestart(File var1) {}
        @Override public void reportProductUpdatedApplied(File var1) {}
        @Override public void reportUpdateHasAlreadyBeenApplied(File var1) throws Exception {}
        @Override public void reportUpdateCannotBeAppliedToThisProductVersion() throws Exception {}
        @Override public void reportUpdateIsForDifferentProduct() throws Exception {}
        @Override public void reportFailedToReadUpdateFile(Exception var1, File var2) throws Exception {}
        @Override public void reportError(Exception var1) throws Exception {}
      }).canApply(f);
    }catch(Throwable t){
      if (Initializer.debug()){
        Initializer.log(t);
      }
      return false;
    }
  }
  /**
   * Reboot the WebCTRL server.
   * @param delay If greater than 0, this will start a new thread, wait the specified number of milliseconds, and then reboot the WebCTRL server.
   *              If less than or equal to 0, this will reboot the WebCTRL server in the current thread without delay.
   */
  public static void reboot(final long delay){
    if (delay>0){
      new Thread(){
        @Override public void run(){
          try{
            Thread.sleep(delay);
          }catch(Throwable t){}
          CoreApp.shutdown(ExitCode.SHUTDOWN_AND_RESTART);
        }
      }.start();
    }else{
      CoreApp.shutdown(ExitCode.SHUTDOWN_AND_RESTART);
    }
  }
  /**
   * Like the 'notify' manual command, this method sends a message to all logged-in WebCTRL operators.
   * @return {@code true} on success; {@code false} if an error was encountered
   */
  public static boolean notify(String message){
    try{
      for (final UserSession session:UserSession.getAllInteractiveUserSessions()){
        if (session instanceof BroadcastNotificationHandler) {
          ((BroadcastNotificationHandler)session).broadcastMessage(message);
        }
      }
      PopupRARequestProcessor.sendMessageToAll(message);
      return true;
    }catch(Throwable t){
      Initializer.log(t);
    }
    return false;
  }
  private final static Pattern emailSepPattern = Pattern.compile("(?:\\s*+;++)++\\s*+");
  /**
   * Send an email.
   * @return {@code true} if the email was sent successfully or if email is not configured, or {@code false} if an exception was encountered while attempting to send the email.
   */
  public static boolean sendEmail(String recipients, String subject, String message){
    if (recipients==null || subject==null || message==null || recipients.isBlank() || subject.isBlank() || message.isBlank()){
      return true;
    }
    final String[] recip = emailSepPattern.split(recipients);
    try{
      EmailParametersBuilder pb = EmailServiceFactory.createParametersBuilder();
      pb.withSubject(subject);
      pb.withToRecipients(recip);
      pb.withMessageContents(message);
      pb.withMessageMimeType("text/plain");
      EmailServiceFactory.getService().sendEmail(pb.build());
      return true;
    }catch(Throwable t){
      Initializer.log(t);
      return false;
    }
  }
  private final static Pattern sepPattern = Pattern.compile("[/\\\\]++");
  /**
   * Resolve relative filepaths.
   */
  public static Path resolve(String path) throws InvalidPathException {
    path = sepPattern.matcher(Utility.expandEnvironmentVariables(path).trim()).replaceAll("/");
    Path p;
    int i;
    if (path.startsWith("./")){
      p = Initializer.systemDir;
      i = 2;
    }else if (path.startsWith("/")){
      p = Initializer.installDir;
      i = 1;
    }else{
      return Paths.get(path.replace('/',File.separatorChar));
    }
    final StringBuilder sb = new StringBuilder();
    char c;
    String s;
    int len = path.length();
    for (;i<len;++i){
      c = path.charAt(i);
      if (c=='/'){
        s = sb.toString().trim();
        sb.setLength(0);
        if (s.length()>0){
          if (s.equals("..")){
            p = p.getParent();
          }else{
            p = p.resolve(s);
          }
        }
      }else{
        sb.append(c);
      }
    }
    if (sb.length()>0){
      s = sb.toString().trim();
      if (s.length()>0){
        if (s.equals("..")){
          p = p.getParent();
        }else{
          p = p.resolve(s);
        }
      }
    }
    return p;
  }
  /**
   * Terminates sessions corresponding to the given set of usernames.
   * @return whether all relevant sessions were closed.
   */
  public static boolean logout(Set<String> usernames){
    try{
      String n;
      for (final UserSession session:UserSession.getAllUserSessions()){
        n = session.getOperator().getLoginName();
        if (n!=null && usernames.contains(n.toLowerCase())){
          session.close();
        }
      }
      return true;
    }catch(Throwable t){
      Initializer.log(t);
    }
    return false;
  }
  /**
   * @return a collection of all local WebCTRL operators where usernames are mapped to display names, or {@code null} if an error occurs.
   */
  public static Map<String,String> getLocalOperators(){
    try{
      return new CoreHelper().getOperatorList();
    }catch(Throwable t){
      Initializer.log(t);
      return null;
    }
  }
  /**
   * @return a list of installed addons on this WebCTRL server, or {@code null} if an error occurs.
   */
  public static ArrayList<Addon> getAddons(){
    try{
      final TomcatServer server = TomcatServerSingleton.get();
      if (server==null){
        return null;
      }
      final Set<AddOn> set = server.scanForAddOns();
      final ArrayList<Addon> list = new ArrayList<Addon>(set.size());
      Addon y;
      for (AddOn x:set){
        if (x!=null){
          y = new Addon();
          y.name = x.getName();
          y.displayName = x.getDisplayName();
          y.description = x.getDescription();
          y.vendor = x.getVendor();
          y.version = x.getVersion();
          y.state = x.getState().name();
          list.add(y);
        }
      }
      return list;
    }catch(Throwable t){
      Initializer.log(t);
      return null;
    }
  }
  /**
   * @return the add-ons directory for the server, or {@code null} if an error occurs.
   */
  public static File getAddonsDirectory(){
    try{
      TomcatServer server = TomcatServerSingleton.get();
      return server==null?null:server.getAddOnsDir();
    }catch(Throwable t){
      Initializer.log(t);
      return null;
    }
  }
  /**
   * Deploys an add-on located by the given file.
   * @param f is ised to locate the add-on.
   * @return {@code true} on success; {@code false} on failure.
   */
  public static boolean deployAddon(File f){
    try{
      TomcatServer server = TomcatServerSingleton.get();
      if (server==null){
        return false;
      }
      server.deployAddOn(f);
      return true;
    }catch(Throwable t){
      Initializer.log(t);
      return false;
    }
  }
  /**
   * Enables an add-on with the given name.
   * @param name is used to identify the add-on.
   * @return {@code true} on success; {@code false} on failure.
   */
  public static boolean enableAddon(String name){
    if (name==null){
      return false;
    }
    try{
      TomcatServer server = TomcatServerSingleton.get();
      if (server==null){
        return false;
      }
      AddOn addon = null;
      for (AddOn x:server.scanForAddOns()){
        if (x!=null && name.equals(x.getName())){
          addon = x;
          break;
        }
      }
      if (addon==null){
        return false;
      }
      server.enableAddOn(addon);
      return true;
    }catch(Throwable t){
      Initializer.log(t);
      return false;
    }
  }
  /**
   * Disables an add-on with the given name.
   * @param name is used to identify the add-on.
   * @return {@code true} on success; {@code false} on failure.
   */
  public static boolean disableAddon(String name){
    if (name==null){
      return false;
    }
    try{
      TomcatServer server = TomcatServerSingleton.get();
      if (server==null){
        return false;
      }
      AddOn addon = null;
      for (AddOn x:server.scanForAddOns()){
        if (x!=null && name.equals(x.getName())){
          addon = x;
          break;
        }
      }
      if (addon==null){
        return true;
      }
      server.disableAddOn(addon);
      return true;
    }catch(Throwable t){
      Initializer.log(t);
      return false;
    }
  }
  /**
   * Removes an add-on with the given name.
   * @param name is used to identify the add-on.
   * @param removeData specifies whether to remove data associated to the add-on.
   * @return {@code true} on success; {@code false} on failure.
   */
  public static boolean removeAddon(String name, boolean removeData){
    if (name==null){
      return false;
    }
    try{
      TomcatServer server = TomcatServerSingleton.get();
      if (server==null){
        return false;
      }
      AddOn addon = null;
      for (AddOn x:server.scanForAddOns()){
        if (x!=null && name.equals(x.getName())){
          addon = x;
          break;
        }
      }
      if (addon==null){
        return false;
      }
      server.removeAddOn(addon, removeData);
      return true;
    }catch(Throwable t){
      Initializer.log(t);
      return false;
    }
  }
  /**
   * Removes all addons in the given set of names.
   * @param addonNames is a map of addon names to search for where the boolean value represents whether to remove data.
   * @return {@code true} on success; {@code false} on failure.
   */
  public static boolean removeAddons(HashMap<String,Boolean> addonNames){
    try{
      TomcatServer server = TomcatServerSingleton.get();
      if (server==null){
        return false;
      }
      String s;
      Boolean b;
      for (AddOn x:server.scanForAddOns()){
        if (x==null){
          continue;
        }
        s = x.getName();
        if (s!=null && (b=addonNames.get(s.toLowerCase()))!=null){
          s+=" v"+x.getVersion();
          server.removeAddOn(x, b);
          Initializer.log("Uninstalled "+s);
        }
      }
      return true;
    }catch(Throwable t){
      Initializer.log(t);
      return false;
    }
  }
  /**
   * Downloads whitelisted addons from an FTP server.
   */
  public static boolean downloadAddons(ArrayList<AddonDownload> whitelist){
    try{
      TomcatServer server = TomcatServerSingleton.get();
      if (server==null){
        return false;
      }
      {
        int i,j;
        final int l = whitelist.size();
        AddonDownload y;
        for (AddOn x:server.scanForAddOns()){
          if (x==null){
            continue;
          }
          for (i=0;i<l;++i){
            y = whitelist.get(i);
            if (y!=null && y.getReferenceName().equalsIgnoreCase(x.getName())){
              j = y.version==null?0:Utility.compareVersions(x.getVersion(),y.version);
              if (j==0 || y.keepNewer && j>0){
                whitelist.set(i,null);
                if (!y.optional){
                  final WebApp.State s = x.getState();
                  if (s!=WebApp.State.RUNNING && s!=WebApp.State.STARTING && s!=WebApp.State.STARTUP_ERROR){
                    try{
                      server.enableAddOn(x);
                    }catch(Throwable t){
                      Thread.sleep(timeout);
                      server.deployAddOn(Initializer.addonsDir.resolve(x.getName()+".addon").toFile());
                    }
                    Initializer.log("Enabled "+y.displayName+" v"+x.getVersion());
                  }
                }
              }else{
                y.addon = x;
              }
              break;
            }
          }
        }
        for (i=0;i<l;++i){
          y = whitelist.get(i);
          if (y!=null && y.optional && y.addon==null){
            whitelist.set(i,null);
          }
        }
      }
      boolean exists = false;
      for (AddonDownload x:whitelist){
        if (x!=null){
          x.file = Initializer.addonsDir.resolve(x.getReferenceName()+".addon");
          exists = true;
        }
      }
      boolean ret = true;
      if (exists){
        try(
          final ConnectSFTP con = new ConnectSFTP();
        ){
          if (!con.isOpen()){
            return false;
          }
          for (AddonDownload x:whitelist){
            if (x!=null){
              if (Initializer.stop){ return false; }
              if (con.downloadFile(x.path, Initializer.tmpAddonFile)){
                try{
                  Thread.sleep(timeout);
                  if (x.addon!=null){
                    server.removeAddOn(x.addon, x.removeData);
                    Thread.sleep(timeout);
                  }
                  if (Files.deleteIfExists(x.file)){
                    Thread.sleep(timeout);
                  }
                  Files.move(Initializer.tmpAddonFile, x.file, StandardCopyOption.REPLACE_EXISTING);
                  Thread.sleep(timeout);
                  if (!enableAddon(x.getReferenceName())){
                    Thread.sleep(timeout);
                    server.deployAddOn(x.file.toFile());
                  }
                  if (x.version==null){
                    Initializer.log((x.addon==null?"Installed ":"Updated ")+x.displayName);
                  }else{
                    Initializer.log((x.addon==null?"Installed ":"Updated ")+x.displayName+" v"+x.version);
                  }
                }catch(Throwable t){
                  ret = false;
                  Initializer.log(t);
                }
              }else{
                ret = false;
                Initializer.log("Failed to download \""+x.path+"\" from FTP server.",true);
              }
            }
          }
        }
      }
      return ret;
    }catch(Throwable t){
      Initializer.log(t);
      return false;
    }
  }
  public static boolean selfUpdate(){
    try{
      final TomcatServer server = TomcatServerSingleton.get();
      final String downloadPath = Sync.settings.get("download_path");
      if (server==null || downloadPath==null){
        return false;
      }
      try(
        final ConnectSFTP con = new ConnectSFTP();
      ){
        if (!con.isOpen() || !con.downloadFile(downloadPath, Initializer.tmpAddonFile)){
          return false;
        }
      }
      Thread.sleep(timeout);
      Files.move(Initializer.tmpAddonFile, Initializer.addonsDir.resolve(Initializer.getName()+".update"), StandardCopyOption.REPLACE_EXISTING);
      final Path addon = Initializer.addonsDir.resolve(Initializer.AUTO_UPDATE_ADDON+".addon");
      if (!Utility.extractResource("aces/webctrl/postgresql/resources/"+Initializer.AUTO_UPDATE_ADDON+".addon", addon) || !Files.exists(addon)){
        return false;
      }
      Thread.sleep(timeout);
      AddOn y = null;
      for (AddOn x:server.scanForAddOns()){
        if (x!=null && Initializer.AUTO_UPDATE_ADDON.equalsIgnoreCase(x.getName())){
          y = x;
          break;
        }
      }
      if (y==null){
        server.deployAddOn(addon.toFile());
      }else{
        try{
          server.enableAddOn(y);
        }catch(Throwable t){
          Thread.sleep(timeout);
          server.deployAddOn(addon.toFile());
        }
      }
      for (AddOn x:server.scanForAddOns()){
        if (x!=null && Initializer.AUTO_UPDATE_ADDON.equalsIgnoreCase(x.getName())){
          final WebApp.State s = x.getState();
          if (s==WebApp.State.RUNNING || s==WebApp.State.STARTING){
            return true;
          }
          break;
        }
      }
      return false;
    }catch(Throwable t){
      Initializer.log(t);
      return false;
    }
  }
  private static class CJAboutInfoBackup {
    private final String language = LanguageManager.getSystemLocale().toString();
    public CJAboutInfoBackup() {
    }
    private String label(String label) {
      return CJIO.formatToLength(label, 20, false);
    }
    private void writeIfNotEmpty(PrintWriter out, String s) {
      if (!s.isBlank()) {
        out.println(s);
      }
    }
    public void writeLicenseAndProductInfo(PrintWriter out) {
      if (!CoreProduct.isEndUser()) {
        if (CoreProduct.isDeveloper()) {
          out.println(this.label("Developer Copy: ") + CoreProduct.getVendorName("en"));
        } else if (CoreProduct.isDealer()) {
          out.println(this.label("Dealer Copy: ") + CoreProduct.getVendorName("en"));
        } else if (CoreProduct.isOEM()) {
          out.println(this.label("OEM Copy: ") + CoreProduct.getVendorName("en"));
        }
      }
      out.println(this.label("Version: ") + CoreProduct.getDistProductName() + " " + CoreProduct.getVersion());
      out.println(this.label("Build: ") + CoreProduct.getBuildVersion() + " " + CoreProduct.getBuildDescription());
      this.writeIfNotEmpty(out, this.label("") + CoreProduct.getCopyright());
      if (CoreProduct.isRegistered()) {
        if (CoreProduct.doesShowLicenseInfo()) {
          out.println(this.label("Licensed to: ") + CoreProduct.getOwnerName(this.language) + " " + CoreProduct.getOwnerAffiliation(this.language));
          out.println(
          this.label("For use at: ")
          + CoreProduct.getLocationName(this.language)
          + " "
          + Utility.coalesce(CoreProduct.getLocationAddress1(this.language),"")
          + " "
          + Utility.coalesce(CoreProduct.getLocationAddress2(this.language),"")
          + " "
          + Utility.coalesce(CoreProduct.getLocationAddress3(this.language),"")
          );
          Date expirationDate = CJProduct.getExpirationDate();
          if (expirationDate != null) {
            out.println(this.label("Expires: ") + SimpleDateFormat.getDateInstance().format(expirationDate));
          }
        }
        if (CoreProduct.doesShowPointLimits()) {
          if (CoreProduct.getMaxHWDevices() > 0) {
            out.println(this.label("Max Device Limit: ") + CoreProduct.getMaxHWDevices());
          }
          int pointLimit = CoreProduct.getPointLimit();
          out.println(this.label("Total Points: ") + (pointLimit == -1 ? "unlimited" : pointLimit));
        }
        if (Feature.AboutFeatures.isSupported()) {
          out.println(this.label("Enabled Features: ") + CoreProduct.getEnabledFeatures(this.language));
          out.println(this.label("Licensed Features: ") + CoreProduct.getAvailableFeatures(this.language));
        }
      } else if (CoreProduct.doesShowLicenseInfo()) {
        out.println("*** Unregistered Copy ***");
      }
      out.println(this.label("Serial Number: ") + CoreProduct.getSerialNumber());
      out.println(this.label("Issue Number: ") + CoreProduct.getIssueNumber());
      out.println(this.label("Java Version: ") + System.getProperty("java.vm.vendor") + " " + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
      out.println(this.label("Server OS: ") + System.getProperty("os.name") + " " + System.getProperty("os.version"));
      try {
        DatabaseInfo info = CoreDatabaseServer.getCoreDB().getInfo();
        out.println(this.label("Database: ") + info.databaseProductName + " " + info.databaseProductVersion);
      } catch (DatabaseException var2) {
        out.println(this.label("Database: "));
      }
      out.println();
    }
    public void writeUpdateInfo(PrintWriter out) throws Exception {
      UpdateManager updMgr = UpdateManagerFactory.getSingletonInstance();
      if (!updMgr.haveAnyUpdatesBeenApplied()) {
        this.writeHeader(out, "Updates");
        out.println("No updates have been applied");
      } else {
        for (UpdateType updateType : UpdateType.values()) {
          if (updateType != UpdateType.ServicePack) {
            List<UpdateInfo> updatesByType = updMgr.getUpdateInfo().getSortedUpdateInfoOfType(updateType);
            String name = updateType.name();
            if (updateType == UpdateType.Patch) {
              List<UpdateInfo> cumulativeUpdates = updatesByType.stream()
              .filter(updateInfo -> updateInfo.getFileName().endsWith("umulative.update"))
              .collect(Collectors.toList());
              List<UpdateInfo> patchUpdates = updatesByType.stream()
              .filter(updateInfo -> !updateInfo.getFileName().endsWith("umulative.update"))
              .collect(Collectors.toList());
              this.writeUpdatesForType(out, cumulativeUpdates, "Cumulative");
              this.writeUpdatesForType(out, patchUpdates, "Patch");
            } else {
              this.writeUpdatesForType(out, updatesByType, name);
            }
          }
        }
      }
    }
    private void writeUpdatesForType(PrintWriter out, List<UpdateInfo> updatesByType, String name) {
      if (updatesByType.size() > 0) {
        this.writeHeader(out, "Updates of type: " + name);
        out.println();
        for (UpdateInfo nextUpdInfo : updatesByType) {
          String notesText = nextUpdInfo.getNotes();
          if ("null".equalsIgnoreCase(notesText)) {
            notesText = "No notes available.";
          }
          out.println(nextUpdInfo.getFileName());
          //out.println("Created: " + nextUpdInfo.getCreatedDate());
          //out.println("Applied: " + nextUpdInfo.getDateApplied());
          //out.println("Notes  : " + notesText);
          out.println();
        }
      }
    }
    private void writeHeader(PrintWriter out, String text) {
      int extra = 50 - (text.length() + 2);
      int left = extra / 2;
      int right = extra - left;
      while (left-- > 0) {
        out.print('=');
      }
      out.print(' ');
      out.print(text);
      out.print(' ');
      while (right-- > 0) {
        out.print('=');
      }
      out.println();
    }
  }
}