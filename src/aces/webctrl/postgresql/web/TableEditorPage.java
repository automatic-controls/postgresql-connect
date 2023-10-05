package aces.webctrl.postgresql.web;
import aces.webctrl.postgresql.core.*;
import javax.servlet.http.*;
import java.util.*;
public class TableEditorPage extends ServletBase {
  @Override public void exec(final HttpServletRequest req, final HttpServletResponse res) throws Throwable {
    if (!checkWhitelist(req)){
      res.sendError(403, "Insufficient permissions.");
      return;
    }
    String table = req.getParameter("table");
    if (table==null){
      res.sendError(400, "Table not specified.");
      return;
    }
    table = table.toLowerCase();
    final TableTemplate tbl = TableCache.tables.get(table);
    if (tbl==null || tbl.keyColumn==null){
      res.sendError(404, "Table not found.");
      return;
    }
    final String action = req.getParameter("action");
    if ("SAVE".equalsIgnoreCase(action)){
      String data = req.getParameter("data");
      if (data==null){
        return;
      }
      final ArrayList<String> queries = new ArrayList<String>(16);
      queries.add(tbl.query);
      try{
        final ArrayList<String> arr = Utility.decodeList(data);
        data = null;
        final int cols = Integer.parseInt(arr.get(0));
        final int size = arr.size();
        char c;
        boolean first;
        final StringBuilder sb = new StringBuilder(64);
        for (int i=1,j;i<size;++i){
          c = Character.toUpperCase(arr.get(i).charAt(0));
          final String key = tbl.conversion.apply(0,arr.get(++i));
          if (c=='D'){
            queries.add("DELETE FROM webctrl."+table+" WHERE \""+tbl.keyColumn+"\" = "+key+';');
            continue;
          }else if (c!='C' && c!='U'){
            res.sendError(404, "Malformed data body.");
            return;
          }
          if (tbl.otherColumns==null){
            sb.append(key);
          }else{
            if (c=='C'){
              sb.append(key).append(',');
            }
            first = true;
            for (j=1;j<cols;++j){
              if (first){
                first = false;
              }else{
                sb.append(',');
              }
              sb.append(tbl.conversion.apply(j,arr.get(++i)));
            }
          }
          if (c=='C'){
            queries.add("INSERT INTO webctrl."+table+" VALUES ("+sb.toString()+");");
          }else{
            if (tbl.otherColumns==null){
              res.sendError(404, "Malformed data body.");
              return;
            }
            queries.add("UPDATE webctrl."+table+" SET ("+tbl.otherColumns+") = ROW("+sb.toString()+") WHERE \""+tbl.keyColumn+"\" = "+key+';');
          }
          sb.setLength(0);
        }
      }catch(NumberFormatException e){
        res.sendError(404, "Could not parse integer.");
      }catch(IndexOutOfBoundsException e){
        res.sendError(404, "Index out of bounds.");
      }
      sendCache(TableCache.update(queries), tbl.header, res);
    }else if ("LOAD".equalsIgnoreCase(action)){
      sendCache(TableCache.submit(tbl.query), tbl.header, res);
    }else{
      res.setContentType("text/html");
      res.getWriter().print(getHTML(req)
        .replace("__TABLE__", Utility.escapeJS(tbl.name))
        .replace("__TABLE_DISPLAY_NAME__", Utility.escapeJS(tbl.displayName))
      );
    }
  }
  private static void sendCache(final TableCache cache, final String header, final HttpServletResponse res) throws Throwable {
    if (cache==null){
      res.sendError(502, "Failed to load table.");
      return;
    }
    res.setContentType("application/json");
    res.getWriter().print(cache.toJSON(header));
  }
}