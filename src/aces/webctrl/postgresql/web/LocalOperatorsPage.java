package aces.webctrl.postgresql.web;
import aces.webctrl.postgresql.core.*;
import com.controlj.green.core.data.*;
import javax.servlet.http.*;
import org.json.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import javax.servlet.annotation.MultipartConfig;
@MultipartConfig
public class LocalOperatorsPage extends ServletBase {
  @Override public void exec(final HttpServletRequest req, final HttpServletResponse res) throws Throwable {
    final String reqUser = getUsername(req);
    if (!checkWhitelist(reqUser)){
      res.sendError(403, "Insufficient permissions.");
      return;
    }
    final String cmd = req.getParameter("cmd");
    if (cmd==null){
      final ArrayList<OperatorData> ops = new ArrayList<OperatorData>(64);
      try(
        OperatorLink link = new OperatorLink(true);
      ){
        for (CoreNode op:link.getOperators()){
          if (!Sync.operatorWhitelist.containsKey(op.getAttribute(CoreNode.KEY).toLowerCase())){
            ops.add(new OperatorData(op));
          }
        }
      }catch(Throwable t){
        Initializer.log(t);
        res.sendError(500, "An error occurred while retrieving local operators.");
        return;
      }
      ops.sort(null);
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
    }else if (cmd.equals("import")){
      String json;
      {
        final Part filePart = req.getPart("file");
        if (filePart==null || filePart.getSize()>1048576){
          res.setStatus(400);
          return;
        }
        json = new String(Utility.readAllBytes(filePart.getInputStream()), StandardCharsets.UTF_8);
      }
      final HashMap<String,OperatorData> ops = new HashMap<>(64);
      try{
        OperatorData op;
        final JSONArray arr = new JSONArray(json);
        JSONObject jo;
        for (Object o: arr){
          if (o instanceof JSONObject){
            jo = (JSONObject)o;
            op = new OperatorData();
            op.username = jo.getString("username").toLowerCase();
            if (!Sync.operatorWhitelist.containsKey(op.username)){
              op.display_name = jo.getString("display_name");
              op.password = jo.getString("password");
              op.lvl5_auto_logout = jo.getInt("session_timeout");
              op.lvl5_auto_collapse = jo.getBoolean("auto_collapse");
              ops.put(op.username,op);
            }
          }
        }
      }catch(JSONException|NumberFormatException e){
        res.setStatus(400);
        return;
      }
      if (!ops.isEmpty()){
        try(
          OperatorLink link = new OperatorLink(false);
        ){
          OperatorData data;
          String opname;
          for (CoreNode op:link.getOperators()){
            opname = op.getAttribute(CoreNode.KEY).toLowerCase();
            if ((data = ops.remove(opname))!=null){
              data.write(link, op, false);
              Initializer.log(reqUser+" imported operator: "+opname);
            }
          }
          CoreNode op, pref;
          for (OperatorData d:ops.values()){
            op = link.createOperator(d.username, d.display_name, d.password, true);
            pref = op.getChild("preferences");
            pref.getChild("lvl5_auto_collapse").setBooleanAttribute(CoreNode.VALUE, d.lvl5_auto_collapse);
            pref.getChild("lvl5_auto_logout").setIntAttribute(CoreNode.VALUE, d.lvl5_auto_logout);
            Initializer.log(reqUser+" imported new operator: "+d.username);
          }
          link.commit();
        }
      }
    }else{
      res.setStatus(400);
    }
  }
}