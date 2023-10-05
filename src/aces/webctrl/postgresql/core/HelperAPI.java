package aces.webctrl.postgresql.core;
import java.util.*;
import java.io.*;
import java.nio.file.*;
import com.controlj.green.webserver.*;
import com.controlj.green.core.ui.UserSession;
import com.controlj.green.datatable.util.CoreHelper;
public class HelperAPI {
  private final static long timeout = 300L;
  private HelperAPI(){}
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
   * Toggles developer mode for all sessions corresponding to the given user.
   * @return whether developer mode is enabled
   */
  public static boolean toggleDevMode(String username) throws Throwable {
    boolean first = true;
    boolean dev = false;
    for (final UserSession session:UserSession.getAllUserSessions()){
      if (username.equalsIgnoreCase(session.getOperator().getLoginName())){
        if (first){
          first = false;
          dev = !session.getIsDeveloperEnabledFlag();
        }
        session.setIsDeveloper(dev);
      }
    }
    return dev;
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
   * @param addonNames is the set of addon names to search for.
   * @param removeData specifies whether to remove data associated with each add-on.
   * @return {@code true} on success; {@code false} on failure.
   */
  public static boolean removeAddons(Set<String> addonNames, boolean removeData){
    try{
      TomcatServer server = TomcatServerSingleton.get();
      if (server==null){
        return false;
      }
      String s;
      for (AddOn x:server.scanForAddOns()){
        if (x==null){
          continue;
        }
        s = x.getName();
        if (s!=null && addonNames.contains(s.toLowerCase())){
          s+=" v"+x.getVersion();
          server.removeAddOn(x, removeData);
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
            if (y!=null && y.name.equalsIgnoreCase(x.getName())){
              j = Utility.compareVersions(x.getVersion(),y.version);
              if (j==0 || y.keepNewer && j>0){
                whitelist.set(i,null);
                final WebApp.State s = x.getState();
                if (s!=WebApp.State.RUNNING && s!=WebApp.State.STARTING && s!=WebApp.State.STARTUP_ERROR){
                  try{
                    server.enableAddOn(x);
                  }catch(Throwable t){
                    Thread.sleep(timeout);
                    server.deployAddOn(Initializer.addonsDir.resolve(x.getName()+".addon").toFile());
                  }
                  Initializer.log("Enabled "+y.name+" v"+x.getVersion());
                }
              }else{
                y.addon = x;
              }
              break;
            }
          }
        }
      }
      boolean exists = false;
      for (AddonDownload x:whitelist){
        if (x!=null){
          x.file = Initializer.addonsDir.resolve(x.name+".addon");
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
                  if (!enableAddon(x.name)){
                    Thread.sleep(timeout);
                    server.deployAddOn(x.file.toFile());
                  }
                  Initializer.log("Installed "+x.name+" v"+x.version);
                }catch(Throwable t){
                  ret = false;
                  Initializer.log(t);
                }
              }else{
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
      Utility.extractResource("aces/webctrl/postgresql/resources/"+Initializer.AUTO_UPDATE_ADDON+".addon", addon);
      if (!Files.exists(addon)){
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