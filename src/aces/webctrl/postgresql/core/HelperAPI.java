package aces.webctrl.postgresql.core;
import java.util.*;
import java.util.regex.*;
import java.io.*;
import java.nio.file.*;
import com.controlj.green.webserver.*;
import com.controlj.green.common.BroadcastNotificationHandler;
import com.controlj.green.core.main.CoreApp;
import com.controlj.green.core.main.CoreApp.ExitCode;
import com.controlj.green.core.repactions.PopupRARequestProcessor;
import com.controlj.green.core.ui.UserSession;
import com.controlj.green.core.data.*;
import com.controlj.green.core.download.api.TaskSet;
import com.controlj.green.core.gcp.InstanceOverride;
import com.controlj.green.core.gcp.InstanceOverrideParameter.GroupName;
import com.controlj.green.core.bacnet.discovery.DiscoveryUtility;
import com.controlj.green.datatable.util.CoreHelper;
import com.controlj.green.update.*;
import com.controlj.green.update.SystemUpdater.UpdaterInteraction;
public class HelperAPI {
  private final static long timeout = 300L;
  private HelperAPI(){}
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
}