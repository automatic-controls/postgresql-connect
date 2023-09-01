package aces.webctrl.postgresql.web;
import aces.webctrl.postgresql.core.*;
import javax.servlet.http.*;
/**
 * Handles AJAX requests to save an operator to the database.
 */
public class SaveOperator extends ServletBase {
  @Override public void exec(final HttpServletRequest req, final HttpServletResponse res) throws Throwable {
    final String username = req.getParameter("username");
    final String refname = req.getParameter("refname");
    String ret = "An error has occurred.";
    try{
      if (username==null || refname==null){
        ret = "Username unspecified.";
      }else if (!Sync.lastGeneralSyncSuccessful){
        ret = "Disconnected from database.";
      }else if (Sync.operatorWhitelist.contains(username)){
        final String reqUsername = getUsername(req);
        String reqRefname = null;
        try(
          OperatorLink link = new OperatorLink(true);
        ){
          reqRefname = link.getOperator(reqUsername).getReferenceName();
        }
        if (!refname.equals(reqRefname)){
          ret = "Insufficient permissions.";
        }else if (new Sync(Event.SAVE_OPERATOR, username, refname).success){
          ret = "Database successfully updated.";
        }else{
          ret = "Failed to update database.";
        }
      }else{
        ret = "Insufficient permissions.";
      }
    }catch(Throwable t){
      Initializer.log(t);
    }
    res.setContentType("text/plain");
    res.getWriter().print(ret);
  }
}