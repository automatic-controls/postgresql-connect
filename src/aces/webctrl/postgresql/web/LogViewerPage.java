package aces.webctrl.postgresql.web;
import aces.webctrl.postgresql.core.*;
import java.util.*;
import javax.servlet.http.*;
public class LogViewerPage extends ServletBase {
  @Override public void exec(final HttpServletRequest req, final HttpServletResponse res) throws Throwable {
    if (!checkWhitelist(req)){
      res.sendError(403, "Insufficient permissions.");
      return;
    }
    final TableCache cache = TableCache.submit(TableCache.tables.get("log").query);
    if (cache!=null){
      res.setContentType("text/html");
      res.getWriter().print(getHTML(req)
        .replace("__LOGS__", cache.toJSON(null))
      );
      return;
    }
    final StringBuilder sb = new StringBuilder(2048);
    final ArrayList<LogMessage> list = new ArrayList<LogMessage>(Initializer.logCache);
    final int len = list.size();
    LogMessage l;
    boolean first = true;
    sb.append("[\n");
    for (int i=len-1;i>=0;--i){
      l = list.get(i);
      if (first){
        first = false;
      }else{
        sb.append(",\n");
      }
      sb.append('[');
      sb.append('"').append(Utility.escapeJSON(Utility.timestampFormat.format(l.getTimestamp()))).append("\",");
      sb.append('"').append(l.isError()).append("\",");
      sb.append('"').append(Utility.escapeJSON(l.getMessage())).append('"');
      sb.append(']');
    }
    sb.append("\n]");
    res.setContentType("text/html");
    res.getWriter().print(getHTML(req)
      .replace("__LOGS__", sb.toString())
    );
  }
}