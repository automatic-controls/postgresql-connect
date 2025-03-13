package aces.webctrl.postgresql.web;
//import aces.webctrl.postgresql.core.*;
import com.controlj.green.addonsupport.access.*;
import com.controlj.green.addonsupport.web.menus.*;
public class SystemMenuEditor implements SystemMenuProvider {
  //private volatile static String JS = null;
  @Override public void updateMenu(Operator op, Menu menu){
    /*try{
      final String username = op.getLoginName().toLowerCase();
      if (Sync.lastGeneralSyncSuccessful && Sync.operatorWhitelist.containsKey(username)){
        if (JS==null){
          try{
            JS = Utility.loadResourceAsString("aces/webctrl/postgresql/resources/SaveOperator.js").replace("__PREFIX__", Initializer.getPrefix());
          }catch(Throwable t){
            JS = "alert('Failed to load procedure.');";
          }
        }
        String refname;
        try(
          OperatorLink link = new OperatorLink(true);
        ){
          refname = link.getOperator(username).getReferenceName();
        }
        final String JS_ = JS.replace("__USERNAME__", Utility.escapeJS(username)).replace("__REF_NAME__", Utility.escapeJS(refname));
        menu.addMenuEntry(MenuEntryFactory
          .newEntry("aces.webctrl.postgresql.SaveOperator")
          .display("Sync Operator Data")
          .action(new Action(){
            @Override public String getJavaScript(){
              return JS_;
            }
          })
          .create()
        );
      }
    }catch(Throwable t){
      Initializer.log(t);
    }*/
  }
}