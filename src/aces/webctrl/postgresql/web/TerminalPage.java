package aces.webctrl.postgresql.web;
import aces.webctrl.postgresql.core.*;
import javax.servlet.http.*;
public class TerminalPage extends ServletBase {
  @Override public void exec(final HttpServletRequest req, final HttpServletResponse res) throws Throwable {
    final String reqUsername = getUsername(req);
    if (!checkWhitelist(reqUsername)){
      res.sendError(403, "Insufficient permissions.");
      return;
    }
    final String type = req.getParameter("type");
    if (type==null){
      res.setContentType("text/html");
      res.getWriter().print(getHTML(req));
    }else{
      switch (type){
        case "exec":{
          final String cmd = req.getParameter("cmd");
          if (cmd==null){
            res.sendError(400, "Missing required parameters.");
            return;
          }
          final Command command = new Command(0, cmd);
          final boolean ret = command.execute(false);
          final String output = "--"+(ret?"Success":"Failure")+(command.lastExecExitCode==0?"":":"+command.lastExecExitCode)+"--\n"+command.getStringBuilder().toString();
          res.setContentType("text/plain");
          res.getWriter().print(output);
          break;
        }
        default:{
          res.sendError(400, "Unrecognized type parameter.");
        }
      }
    }
  }
}