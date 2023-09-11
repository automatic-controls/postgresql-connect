package aces.webctrl.postgresql.web;
import aces.webctrl.postgresql.core.*;
import javax.servlet.http.*;
import com.controlj.green.core.data.*;
import java.util.*;
public class LocalOperatorsPage extends ServletBase {
  @Override public void exec(final HttpServletRequest req, final HttpServletResponse res) throws Throwable {
    if (!checkWhitelist(req)){
      res.sendError(403, "Insufficient permissions.");
      return;
    }
    final ArrayList<OperatorData> ops = new ArrayList<OperatorData>(64);
    try(
      OperatorLink link = new OperatorLink(true);
    ){
      for (CoreNode op:link.getOperators()){
        if (!Sync.operatorWhitelist.contains(op.getAttribute(CoreNode.KEY).toLowerCase())){
          ops.add(new OperatorData(op));
        }
      }
    }catch(Throwable t){
      Initializer.log(t);
      res.sendError(500, "An error occurred while retrieving local operators.");
      return;
    }
    final StringBuilder sb = new StringBuilder(2048);
    sb.append("[\n");
    boolean first = true;
    for (OperatorData op:ops){
      if (first){
        first = false;
      }else{
        sb.append(",\n");
      }
      sb.append('[');
      sb.append('"').append(Utility.escapeJSON(op.username)).append("\",");
      sb.append('"').append(Utility.escapeJSON(op.display_name)).append("\",");
      sb.append('"').append(Utility.escapeJSON(op.password)).append("\",");
      sb.append('"').append(op.lvl5_auto_logout).append("\",");
      sb.append('"').append(op.lvl5_auto_collapse).append('"');
      sb.append(']');
    }
    sb.append("\n]");
    res.setContentType("text/html");
    res.getWriter().print(getHTML(req)
      .replace("__OPERATORS__", sb.toString())
    );
  }
}