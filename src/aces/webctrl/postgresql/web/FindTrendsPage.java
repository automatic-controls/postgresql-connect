package aces.webctrl.postgresql.web;
import aces.webctrl.postgresql.core.*;
import com.controlj.green.addonsupport.access.*;
import com.controlj.green.addonsupport.access.aspect.*;
import com.controlj.green.core.data.CoreNode;
import com.controlj.green.directaccess.CoreNodeLocationInternal;
import com.controlj.green.core.pagebuilders.Icon;
import com.controlj.green.addonsupport.access.location.impl.virtual.VirtualLocation;
import com.controlj.green.addonsupport.access.location.impl.virtual.VirtualLocationDefinition;
import javax.servlet.http.*;
import java.util.*;
public class FindTrendsPage extends ServletBase {
  private volatile static Boolean hasIconAPI = null;
  private volatile static LocationIcon icons = null;
  @Override public void exec(final HttpServletRequest req, final HttpServletResponse res) throws Throwable {
    if (!checkWhitelist(req)){
      res.sendError(403, "Insufficient permissions.");
      return;
    }
    final String id = req.getParameter("id");
    if (id==null){
      res.setContentType("text/html");
      res.getWriter().print(getHTML(req));
    }else{
      final StringBuilder sb = getChildren(id, null);
      res.setContentType("application/json");
      res.getWriter().print(sb);
    }
  }
  public static StringBuilder getChildren(final String locationIdentifier, StringBuilder sb) throws Throwable {
    if (hasIconAPI==null){
      hasIconAPI = Utility.compareVersions(Initializer.simpleVersion, "8.5")>=0;
    }
    if (hasIconAPI && icons==null){
      icons = LocationIconFactory.newLocationIcon();
    }
    final boolean interimIcons = !hasIconAPI && Utility.compareVersions(Initializer.simpleVersion, "8")>=0;
    final ArrayList<Loc> arr = new ArrayList<Loc>();
    Initializer.getConnection().runReadAction(FieldAccessFactory.newDisabledFieldAccess(), new ReadAction(){
      public void execute(SystemAccess sys){
        try{
          Loc ll;
          boolean include;
          TrendSource asp;
          for (Location l:(locationIdentifier.equalsIgnoreCase("root")?sys.getGeoRoot():sys.getTree(SystemTree.Geographic).resolve(locationIdentifier)).getChildren()){
            ll = new Loc();
            ll.type = l.getType();
            include = ll.type==LocationType.Equipment || ll.type==LocationType.Area || ll.type==LocationType.System || ll.type==LocationType.Directory;
            ll.trendSource = l.hasAspect(TrendSource.class);
            if (ll.trendSource){
              asp = l.getAspect(TrendSource.class);
              ll.trendType = asp.getType();
              ll.trendSource = (ll.trendType==TrendSource.Type.Analog || ll.trendType==TrendSource.Type.Digital || ll.trendType==TrendSource.Type.EquipmentColor) && asp.isEnabled();
            }
            include |= ll.trendSource;
            if (include){
              ll.displayName = l.getDisplayName();
              ll.ID = l.getPersistentLookupString(true);
              if (hasIconAPI){
                ll.iconURL = icons.getLocationIconUrl(l);
              }else if (interimIcons){
                ll.iconURL = getIcon(l);
              }
              arr.add(ll);
            }
          }
        }catch(Throwable t){
          arr.clear();
          if (Initializer.debug()){
            Initializer.log(t);
          }
        }
      }
    });
    arr.sort(null);
    {
      int len = 16+(arr.size()<<5);
      for (Loc l:arr){
        l.ID = Utility.escapeJSON(l.ID);
        l.displayName = Utility.escapeJSON(l.displayName);
        len+=l.ID.length();
        len+=l.displayName.length();
        if (l.iconURL!=null){
          l.iconURL = Utility.escapeJSON(l.iconURL);
          len+=l.iconURL.length();
        }
        if (l.trendSource){
          len+=16;
        }
      }
      if (sb==null){
        sb = new StringBuilder(len);
      }else{
        sb.ensureCapacity(sb.length()+len);
      }
    }
    boolean first = true;
    sb.append('[');
    for (Loc l:arr){
      if (first){
        first = false;
      }else{
        sb.append(',');
      }
      sb.append('{');
      sb.append("\n\"id\":\"").append(l.ID).append('"');
      sb.append(",\n\"name\":\"").append(l.displayName).append('"');
      sb.append(",\n\"trend\":").append(l.trendSource);
      if (l.trendType!=null){
        sb.append(",\n\"type\":\"").append(l.trendType.name()).append('"');
      }
      if (l.iconURL!=null){
        sb.append(",\n\"icon\":\"").append(l.iconURL).append('"');
      }
      sb.append("\n}");
    }
    sb.append(']');
    return sb;
  }
  private static String getIcon(Location location){
    String iconUrl = null;
    try{
      if (location instanceof CoreNodeLocationInternal) {
        CoreNode node = ((CoreNodeLocationInternal)location).getNode();
        iconUrl = Icon.getIconUrl(node);
      } else if (location instanceof VirtualLocation) {
        VirtualLocationDefinition virtualLocationDefinition = ((VirtualLocation)location).getVirtualLocationDefinition();
        iconUrl = virtualLocationDefinition.getIconUrl();
      } 
    }catch(Throwable t){}
    if (iconUrl == null)
      iconUrl = Icon.DEFAULT; 
    return iconUrl;
  };
}
class Loc implements Comparable<Loc> {
  public volatile String ID;
  public volatile String displayName;
  public volatile LocationType type;
  public volatile TrendSource.Type trendType = null;
  public volatile boolean trendSource;
  public volatile String iconURL = null;
  @Override public int compareTo(Loc l){
    return type.compareTo(l.type);
  }
}