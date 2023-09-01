package aces.webctrl.postgresql.core;
import java.nio.file.*;
import com.controlj.green.webserver.AddOn;
public class AddonDownload {
  public String name;
  public String version;
  public String path;
  public boolean removeData;
  public boolean keepNewer;
  public Path file = null;
  public AddOn addon = null;
}