package aces.webctrl.postgresql.web;
import aces.webctrl.postgresql.core.*;
import javax.servlet.http.*;
public class ProxyPage extends ServletBase {
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
        .replace("__STATUS__", SSHProxy.useProxy()?"Active":"Inactive")
      );
    }else{
      switch (type){
        case "save":{
          final String host = req.getParameter("host");
          final String dst_host = req.getParameter("dst_host");
          final String username = req.getParameter("username");
          final String knownhosts = req.getParameter("knownhosts");
          final String key = req.getParameter("key");
          final String _port = req.getParameter("port");
          final String _src_port = req.getParameter("src_port");
          final String _dst_port = req.getParameter("dst_port");
          boolean n = false;
          n|=host==null;
          n|=dst_host==null;
          n|=username==null;
          n|=knownhosts==null;
          n|=key==null;
          n|=_port==null;
          n|=_src_port==null;
          n|=_dst_port==null;
          if (n){
            res.sendError(400, "Missing required parameters.");
            return;
          }
          try{
            int port = Integer.parseInt(_port);
            int src_port = Integer.parseInt(_src_port);
            int dst_port = Integer.parseInt(_dst_port);
            if (port<1 || port>65535 || src_port<1 || src_port>65535 || dst_port<1 || dst_port>65535){
              res.sendError(400, "Invalid port number.");
              return;
            }
            if (!SSHProxy.set(host, dst_host, username, knownhosts, key, port, src_port, dst_port)){
              res.sendError(500, "Failed to set proxy.");
              return;
            }
          }catch(NumberFormatException e){
            res.sendError(400, "Invalid port number.");
            return;
          }
          break;
        }
        case "delete":{
          SSHProxy.delete();
          break;
        }
        default:{
          res.sendError(400, "Unrecognized type parameter.");
        }
      }
    }
  }
}