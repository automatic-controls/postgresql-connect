package aces.webctrl.postgresql.core;
import java.nio.file.*;
import com.controlj.green.webserver.AddOn;
import java.util.regex.*;
public class AddonDownload {
  public String displayName;
  public String version;
  public String path;
  public boolean removeData;
  public boolean keepNewer;
  public boolean optional;
  public Path file = null;
  public AddOn addon = null;
  private String refname = null;
  private final static Pattern filenameParser = Pattern.compile("[/\\\\]([^/\\\\]+)\\.addon$");
  public String getReferenceName(){
    if (refname!=null){
      return refname;
    }
    final Matcher m = filenameParser.matcher(path);
    if (m.find()){
      refname = m.group(1);
    }
    if (refname==null){
      if (displayName==null){
        refname = "addon";
      }else{
        refname = displayName;
      }
    }
    return refname;
  }
}