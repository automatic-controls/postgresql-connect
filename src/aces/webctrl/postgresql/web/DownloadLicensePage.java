package aces.webctrl.postgresql.web;
import aces.webctrl.postgresql.core.*;
import javax.servlet.http.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.regex.*;
/**
 * Used to download WebCTRL licenses.
 */
public class DownloadLicensePage extends ServletBase {
  public final static Pattern num = Pattern.compile("[1-9]\\d*");
  @Override public void exec(final HttpServletRequest req, final HttpServletResponse res) throws Throwable {
    if (!checkWhitelist(req)){
      res.sendError(403, "Insufficient permissions.");
      return;
    }
    final String id = req.getParameter("id");
    if (id==null){
      res.sendError(400, "ID parameter missing.");
      return;
    }
    if (!num.matcher(id).matches()){
      res.sendError(400, "ID parameter must be a positive integer.");
      return;
    }
    String dst = Sync.settings.get("license_directory");
    if (dst==null){
      res.sendError(404, "License directory is not configured.");
      return;
    }
    dst+="/license-"+id+".properties";
    try{
      final ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
      try(
        final ConnectSFTP con = new ConnectSFTP();
      ){
        con.retrieveFile(dst, out);
      }
      final ByteBuffer buf = ByteBuffer.wrap(out.toByteArray());
      res.setContentType("application/octet-stream");
      res.setHeader("Content-Disposition","attachment;filename=\"license-"+id+".properties\"");
      try(
        WritableByteChannel ch = Channels.newChannel(res.getOutputStream());
      ){
        while (buf.hasRemaining()){
          ch.write(buf);
        }
      }
    }catch(Throwable t){
      if ("true".equalsIgnoreCase(Sync.settings.get("debug"))){
        Initializer.log(t);
      }
      res.sendError(404, "An error has occurred. This could mean the license file does not exist, or the SFTP server is misconfigured.");
    }
  }
}