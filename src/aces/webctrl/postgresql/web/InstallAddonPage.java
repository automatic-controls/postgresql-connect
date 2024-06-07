package aces.webctrl.postgresql.web;
import aces.webctrl.postgresql.core.*;
import javax.servlet.http.*;
import java.util.*;
import java.util.regex.*;
/**
 * Used to install optional WebCTRL add-ons.
 */
public class InstallAddonPage extends ServletBase {
  public final static Pattern num = Pattern.compile("[1-9]\\d*");
  @Override public void exec(final HttpServletRequest req, final HttpServletResponse res) throws Throwable {
    if (!checkWhitelist(req)){
      res.sendError(403, "Insufficient permissions.");
      return;
    }
    final String path = req.getParameter("param");
    if (path==null){
      res.sendError(400, "Parameter missing.");
      return;
    }
    final AddonDownload ad = new AddonDownload();
    ad.version = null;
    ad.path = path;
    ad.optional = false;
    ad.keepNewer = true;
    ad.removeData = false;
    ad.displayName = null;
    ad.displayName = ad.getReferenceName();
    final ArrayList<AddonDownload> arr = new ArrayList<AddonDownload>(1);
    arr.add(ad);
    res.setContentType("text/plain");
    if (HelperAPI.downloadAddons(arr)){
      res.getWriter().print("Installed.");
    }else{
      res.setStatus(502);
      res.getWriter().print("An error has occured.");
    }
  }
}