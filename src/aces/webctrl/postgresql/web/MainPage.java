package aces.webctrl.postgresql.web;
import aces.webctrl.postgresql.core.*;
import com.controlj.green.core.data.*;
import javax.servlet.http.*;
import javax.servlet.annotation.MultipartConfig;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
/**
 * Details:
 * <p> Readonly Field: Initializer.status, Config.cron.getNextString("N/A"), Config.ID
 * <p> Editable Fields: Config.connectionURL, Config.username, Config.password, Config.cron.toString(), Config.keystorePassword
 * <p> Buttons: Save, Sync Now, Reset ID, Test SFTP, Toggle Developer Mode, Download Setup SQL, Upload Client Keystore, Upload Root Certificate
 * <p> Link: Documentation
 */
@MultipartConfig
public class MainPage extends ServletBase {
  @Override public void exec(final HttpServletRequest req, final HttpServletResponse res) throws Throwable {
    final String reqUsername = getUsername(req);
    if (!checkWhitelist(reqUsername)){
      res.sendError(403, "Insufficient permissions.");
      return;
    }
    final String type = req.getParameter("type");
    if (type==null){
      res.setContentType("text/html");
      res.getWriter().print(getHTML(req)
        .replace("__CONNECTION_URL__", Utility.escapeJS(Config.connectionURL))
        .replace("__USERNAME__", Utility.escapeJS(Config.username))
        .replace("__ID__", String.valueOf(Config.ID))
        .replace("__CRON__", Utility.escapeJS(Config.cron.toString()))
        .replace("__RANDOM_OFFSET__", String.valueOf(Config.maxRandomOffset))
        .replace("__CRON_DISPLAY__", Utility.escapeJS(Config.cron.getNextString("N/A")))
        .replace("__STATUS__", Utility.escapeJS(Initializer.status))
      );
    }else{
      switch (type){
        case "save":{
          final String connectionURL = Config.connectionURL;
          final String username = Config.username;
          final String password = Config.password;
          final String keystorePassword = Config.keystorePassword;
          Config.connectionURL = Utility.coalesce(req.getParameter("connectionURL"), "");
          Config.username = Utility.coalesce(req.getParameter("username"), "");
          {
            final String p = Utility.coalesce(req.getParameter("password"), "");
            if (!p.isEmpty()){
              Config.password = p;
            }
          }
          {
            final String p = Utility.coalesce(req.getParameter("keyPassword"), "");
            if (!p.isEmpty()){
              Config.keystorePassword = p;
            }
          }
          try{
            Config.maxRandomOffset = Long.parseLong(Utility.coalesce(req.getParameter("offset"),"0"));
          }catch(NumberFormatException e){
            Config.maxRandomOffset = 0;
          }
          Config.cron.set(Utility.coalesce(req.getParameter("cron"), ""));
          Config.save();
          if (!connectionURL.equals(Config.connectionURL) || !username.equals(Config.username) || !password.equals(Config.password) || !keystorePassword.equals(Config.keystorePassword)){
            Initializer.status = "Initialized";
            if (!connectionURL.equals(Config.connectionURL)){
              Sync.licenseSynced = false;
            }
          }
          // Note - We do not "break" at the end of this case. Proceed to the refresh case.
        }
        case "refresh":{
          res.setContentType("text/plain");
          res.getWriter().print(Initializer.status+';'+Config.ID+';'+Config.cron.getNextString("N/A"));
          break;
        }
        case "uploadCertificate": case "uploadKeystore":{
          final boolean debug = "true".equalsIgnoreCase(Sync.settings.get("debug"));
          final Part filePart = req.getPart("file");
          if (filePart==null || filePart.getSize()>8388608){
            if (debug){
              Initializer.log(filePart==null?new NullPointerException("File upload is missing."):new OutOfMemoryError("File upload is too large."));
            }
            res.setStatus(400);
            return;
          }
          ByteBuffer buf = ByteBuffer.allocate(8192);
          boolean go = true;
          try(
            ReadableByteChannel in = Channels.newChannel(filePart.getInputStream());
            FileChannel out = FileChannel.open(type.equals("uploadCertificate")?Initializer.pgsslroot:Initializer.pgsslkey, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);        
          ){
            do {
              do {
                go = in.read(buf)!=-1;
              } while (go && buf.hasRemaining());
              buf.flip();
              while (buf.hasRemaining()){
                out.write(buf);
              }
              buf.clear();
            } while (go);
          }
          break;
        }
        case "syncNow":{
          Initializer.syncNow();
          break;
        }
        case "resetNow":{
          Config.reset();
          Config.save();
          break;
        }
        case "testSFTP":{
          boolean success;
          try(
            ConnectSFTP sftp = new ConnectSFTP();
          ){
            success = sftp.isOpen();
          }
          res.setContentType("text/plain");
          res.getWriter().print(success?"1":"0");
          break;
        }
        case "downloadSQL":{
          res.setContentType("application/octet-stream");
          res.setHeader("Content-Disposition","attachment;filename=\"Setup.sql\"");
          String s = null;
          Throwable err = null;
          try{
            s = Utility.loadResourceAsString("aces/webctrl/postgresql/resources/Setup.sql");
          }catch(Throwable t){
            err = t;
          }
          final PrintWriter w = res.getWriter();
          if (err==null){
            w.println(s);
            final StringBuilder sb = new StringBuilder(2048);
            try(
              OperatorLink link = new OperatorLink(true);
            ){
              boolean prefix = false;
              final OperatorData data = new OperatorData();
              final HashSet<String> groupAdmins = new HashSet<String>();
              for (CoreNode grp:link.getNode("/trees/config/operator_groups").getChildren()){
                for (CoreNode role:grp.getChild("roles").getChildren()){
                  if (role.getCoreNodeAttribute(CoreNode.TARGET).getReferenceName().equals("administrator")){
                    for (CoreNode op:grp.getChild("members").getChildren()){
                      groupAdmins.add(op.getCoreNodeAttribute(CoreNode.TARGET).getAttribute(CoreNode.KEY));
                    }
                    break;
                  }
                }
              }
              for (CoreNode op:link.getOperators()){
                if (groupAdmins.contains(op.getAttribute(CoreNode.KEY)) || link.hasAdminRole(op)){
                  data.read(op);
                  if (prefix){
                    sb.append(',').append(System.lineSeparator());
                  }else{
                    prefix = true;
                  }
                  sb.append('(');
                  sb.append(Utility.escapePostgreSQL(data.username)).append(',');
                  sb.append(Utility.escapePostgreSQL(data.display_name)).append(',');
                  sb.append(Utility.escapePostgreSQL(data.password)).append(',');
                  sb.append(data.lvl5_auto_logout).append(',');
                  sb.append(data.lvl5_auto_collapse);
                  sb.append(')');
                }
              }
            }catch(Throwable t){
              Initializer.log(t);
              sb.setLength(0);
              sb.append("-- An error occurred while generating the operator list");
            }
            w.print(sb.toString());
            w.print(';');
          }else{
            err.printStackTrace(w);
          }
          break;
        }
        default:{
          res.sendError(400, "Unrecognized type parameter.");
        }
      }
    }
  }
}