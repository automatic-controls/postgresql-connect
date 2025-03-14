package aces.webctrl.postgresql.core;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import java.util.stream.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.channels.*;
import com.controlj.green.common.launcher.MonitoredLauncher;
import com.controlj.green.common.policy.PolicyUtils;
import com.controlj.green.common.policy.PolicyUtils.KeyType;
import com.controlj.launcher.ConfigurationDecorator;
public class Command {
  private final static HashMap<String,Cmd> commandMap = new HashMap<>();
  static {
    commandMap.put("exec", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length>2){
          long timeout;
          try{
            timeout = Long.parseLong(tokens[1]);
            if (timeout<0){
              c.sb.append("\n'exec' timeout must be a positive number.");
              return false;
            }
          }catch(NumberFormatException e){
            c.sb.append("\n'exec' failed to parse number from expected value.");
            return false;
          }
          HashSet<Integer> allowedExitCodes = new HashSet<>();
          if (tokens[2].equals("*")){
            allowedExitCodes = null;
          }else{
            for (String code: tokens[2].split(",")){
              try{
                allowedExitCodes.add(Integer.parseInt(code.trim()));
              }catch(NumberFormatException e){}
            }
          }
          final String[] cmd = new String[tokens.length-3];
          System.arraycopy(tokens, 3, cmd, 0, cmd.length);
          final ProcessBuilder pb = new ProcessBuilder(cmd);
          pb.directory(Initializer.systemDir.toFile());
          pb.redirectErrorStream(true);
          final Process p = pb.start();
          try{
            c.sb.append('\n');
            final long lim = System.currentTimeMillis()+timeout;
            try (
              final BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            ){
              int ch = 0;
              do {
                if (Initializer.stop){
                  break;
                }
                if (p.isAlive()){
                  while (reader.ready()){
                    ch = reader.read();
                    if (ch==-1){
                      break;
                    }
                    c.sb.append((char)ch);
                  }
                  if (ch==-1){
                    break;
                  }
                  Thread.sleep(500L);
                }else{
                  while (reader.ready()){
                    ch = reader.read();
                    if (ch==-1){
                      break;
                    }
                    c.sb.append((char)ch);
                  }
                  break;
                }
              } while (System.currentTimeMillis()<lim);
            }
          }finally{
            if (p.isAlive()){
              try{
                p.destroy();
                if (!p.waitFor(2000L, TimeUnit.MILLISECONDS)){
                  p.destroyForcibly();
                  p.waitFor(2000L, TimeUnit.MILLISECONDS);
                }
              }catch(InterruptedException e){
                p.destroyForcibly();
              }
              if (Initializer.stop){
                c.sb.append("\nCommand was terminated due to system shutdown.");
              }else{
                c.sb.append("\nCommand timed out.");
              }
              return false;
            }
          }
          try{
            int code = p.exitValue();
            c.lastExecExitCode = code;
            return allowedExitCodes==null || allowedExitCodes.contains(code);
          }catch(Throwable t){
            c.sb.append("\n'exec' failed to retrieve exit value.");
            return false;
          }
        }else{
          c.sb.append("\n'exec' requires at least three arguments.");
          return false;
        }
      }
    });
    commandMap.put("reboot", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==1){
          HelperAPI.reboot(5000L);
          return true;
        }else{
          c.sb.append("\n'reboot' does not accept any arguments.");
          return false;
        }
      }
    });
    commandMap.put("uptime", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==1){
          long uptime = com.controlj.green.core.main.Core.getSystemUptime();
          int days = (int)(uptime/86400000L);
          uptime-=(long)days*86400000L;
          int hours = (int)(uptime/3600000L);
          uptime-=(long)hours*3600000L;
          int minutes = (int)(uptime/60000L);
          //uptime-=(long)minutes*60000L;
          c.sb.append("\nWebCTRL has been online for "+days+" days, "+hours+" hours, and "+minutes+" minutes.");
          return true;
        }else{
          c.sb.append("\n'uptime' does not accept any arguments.");
          return false;
        }
      }
    });
    commandMap.put("about", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==1){
          HelperAPI.about(c.sb);
          return true;
        }else{
          c.sb.append("\n'about' does not accept any arguments.");
          return false;
        }
      }
    });
    commandMap.put("listtunnels", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==1){
          c.sb.append('\n');
          TunnelSSH.listTunnels(c.sb);
          return true;
        }else{
          c.sb.append("\n'listtunnels' does not accept any arguments.");
          return false;
        }
      }
    });
    commandMap.put("opentunnel", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==3 || tokens.length==4){
          try{
            final int listenPort = Integer.parseInt(tokens[1]);
            final int targetPort = Integer.parseInt(tokens[2]);
            final long timeout = tokens.length==3?0:Long.parseLong(tokens[3]);
            if (TunnelSSH.open(listenPort, targetPort, timeout)){
              if (Initializer.debug()){
                Initializer.log("Tunnel opened from port "+listenPort+" to "+targetPort+(timeout>0?" with a timeout of "+(timeout/60000L)+" minutes.":"."));
              }
              return true;
            }else{
              c.sb.append("\n'opentunnel' encountered an error.");
            }
          }catch(NumberFormatException t){
            c.sb.append("\n'opentunnel' failed to parse number from expected value.");
          }
        }else{
          c.sb.append("\n'opentunnel' accepts either 2 or 3 arguments.");
        }
        return false;
      }
    });
    commandMap.put("closetunnel", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==1){
          TunnelSSH.closeTransient();
          if (Initializer.debug()){
            Initializer.log("All transient tunnels closed.");
          }
          return true;
        }else if (tokens.length==2){
          try{
            TunnelSSH.closeTunnel(Integer.parseInt(tokens[1]));
            if (Initializer.debug()){
              Initializer.log("Tunnel on port "+tokens[1]+" has been closed.");
            }
            return true;
          }catch(NumberFormatException e){
            c.sb.append("\n'closetunnel' failed to parse number from expected value.");
          }
        }else{
          c.sb.append("\n'closetunnel' accepts at most one argument.");
        }
        return false;
      }
    });
    commandMap.put("decrypt", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==2){
          String p;
          try{
            try {
              p = PolicyUtils.AESdecrypt(tokens[1], KeyType.DBPROPERTIES);
            } catch (Throwable t) {
              p = PolicyUtils.decode(tokens[1]);
              if (Initializer.debug()){
                Initializer.log(t);
              }
            }
          } catch (Throwable t) {
            p = "";
            if (Initializer.debug()){
              Initializer.log(t);
            }
          }
          c.sb.append('\n').append(p);
          return true;
        }else{
          c.sb.append("\n'decrypt' accepts exactly one argument.");
          return false;
        }
      }
    });
    commandMap.put("updatedst", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==1){
          return HelperAPI.updateDST();
        }else{
          c.sb.append("\n'updateDST' does not accept any arguments.");
          return false;
        }
      }
    });
    commandMap.put("sleep", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==2){
          try{
            Thread.sleep(Math.min(Long.parseLong(tokens[1]), 3600000L));
          }catch(NumberFormatException e){
            c.sb.append("\n'sleep' failed to parse number from expected value.");
            return false;
          }
          return true;
        }else{
          c.sb.append("\n'sleep' accepts exactly one argument.");
          return false;
        }
      }
    });
    commandMap.put("email", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==4){
          if (HelperAPI.sendEmail(tokens[1], tokens[2], tokens[3])){
            return true;
          }else{
            c.sb.append("\n'email' failed to send message.");
          }
        }else{
          c.sb.append("\n'email' accepts exactly three arguments.");
        }
        return false;
      }
    });
    commandMap.put("log", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==2){
          c.sb.append('\n').append(Utility.expandEnvironmentVariables(tokens[1]));
          return true;
        }else{
          c.sb.append("\n'log' accepts exactly one argument.");
          return false;
        }
      }
    });
    commandMap.put("notify", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==2){
          if (HelperAPI.notify(tokens[1])){
            return true;
          }else{
            c.sb.append("\n'notify' command encountered an error.");
          }
        }else{
          c.sb.append("\n'notify' accepts exactly one argument.");
        }
        return false;
      }
    });
    commandMap.put("mkdir", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==2){
          try{
            Files.createDirectories(HelperAPI.resolve(tokens[1]));
            return true;
          }catch(InvalidPathException t){
            c.sb.append("\n'mkdir' could not resolve path.");
            Initializer.log(t);
          }catch(IOException t){
            c.sb.append("\n'mkdir' failed to create directory.");
            Initializer.log(t);
          }
        }else{
          c.sb.append("\n'mkdir' accepts exactly one argument.");
        }
        return false;
      }
    });
    commandMap.put("rmdir", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==2){
          try{
            final Path p = HelperAPI.resolve(tokens[1]);
            if (Files.isDirectory(p)){
              Utility.deleteTree(p);
              return true;
            }else{
              c.sb.append("\n'rmdir' may only delete folders.");
            }
          }catch(InvalidPathException t){
            c.sb.append("\n'rmdir' could not resolve path.");
            Initializer.log(t);
          }catch(IOException t){
            c.sb.append("\n'rmdir' failed to delete directory.");
            Initializer.log(t);
          }
        }else{
          c.sb.append("\n'rmdir' accepts exactly one argument.");
        }
        return false;
      }
    });
    commandMap.put("ls", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==2){
          try{
            final Path p = HelperAPI.resolve(tokens[1]);
            if (Files.exists(p)){
              if (Files.isDirectory(p)){
                TreeSet<String> files = new TreeSet<>();
                TreeSet<String> folders = new TreeSet<>();
                try(
                  Stream<Path> s = Files.list(p);
                ){
                  Iterator<Path> i = s.iterator();
                  Path pp;
                  String name;
                  while (i.hasNext()){
                    pp = i.next();
                    name = pp.getFileName().toString();
                    if (Files.isDirectory(pp)){
                      folders.add(name);
                    }else{
                      files.add(name);
                    }
                  }
                }
                if (!folders.isEmpty()){
                  c.sb.append("\nFolders:");
                  for (String f: folders){
                    c.sb.append("\n  ").append(f);
                  }
                }
                if (!files.isEmpty()){
                  c.sb.append("\nFiles:");
                  for (String f: files){
                    c.sb.append("\n  ").append(f);
                  }
                }
                return true;
              }else{
                c.sb.append("\n'ls' cannot be used on files.");
              }
            }else{
              c.sb.append("\n'ls' could not resolve path.");
            }
          }catch(InvalidPathException t){
            c.sb.append("\n'ls' could not resolve path.");
            Initializer.log(t);
          }catch(IOException t){
            c.sb.append("\n'ls' encountered an error.");
            Initializer.log(t);
          }
        }else{
          c.sb.append("\n'ls' accepts exactly one argument.");
        }
        return false;
      }
    });
    commandMap.put("rm", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==2){
          try{
            final Path p = HelperAPI.resolve(tokens[1]);
            if (Files.isDirectory(p)){
              c.sb.append("\n'rm' cannot be used to delete folders.");
            }else{
              Utility.deleteTree(p);
              return true;
            }
          }catch(InvalidPathException t){
            c.sb.append("\n'rm' could not resolve path.");
            Initializer.log(t);
          }catch(IOException t){
            c.sb.append("\n'rm' failed to delete file.");
            Initializer.log(t);
          }
        }else{
          c.sb.append("\n'rm' accepts exactly one argument.");
        }
        return false;
      }
    });
    commandMap.put("cp", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==3){
          try{
            Utility.copy(HelperAPI.resolve(tokens[1]), HelperAPI.resolve(tokens[2]));
            return true;
          }catch(InvalidPathException t){
            c.sb.append("\n'cp' could not resolve path.");
            Initializer.log(t);
          }catch(IOException t){
            c.sb.append("\n'cp' failed to copy file(s).");
            Initializer.log(t);
          }
        }else{
          c.sb.append("\n'cp' accepts exactly two arguments.");
        }
        return false;
      }
    });
    commandMap.put("mv", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==3){
          try{
            Files.move(HelperAPI.resolve(tokens[1]), HelperAPI.resolve(tokens[2]), StandardCopyOption.REPLACE_EXISTING);
            return true;
          }catch(InvalidPathException t){
            c.sb.append("\n'mv' could not resolve path.");
            Initializer.log(t);
          }catch(IOException t){
            c.sb.append("\n'mv' failed to move file(s).");
            Initializer.log(t);
          }
        }else{
          c.sb.append("\n'mv' accepts exactly two arguments.");
        }
        return false;
      }
    });
    commandMap.put("cat", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==2){
          try{
            final Path p = HelperAPI.resolve(tokens[1]);
            if (Files.isRegularFile(p)){
              final String s = new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
              c.sb.append('\n').append(s);
              return true;
            }else{
              c.sb.append("\n'cat' may only be used on files.");
            }
          }catch(InvalidPathException t){
            c.sb.append("\n'cat' could not resolve path.");
            Initializer.log(t);
          }catch(IOException t){
            c.sb.append("\n'cat' failed to read file.");
            Initializer.log(t);
          }
        }else{
          c.sb.append("\n'cat' accepts exactly one argument.");
        }
        return false;
      }
    });
    commandMap.put("exists", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==2){
          try{
            return Files.exists(HelperAPI.resolve(tokens[1]));
          }catch(InvalidPathException t){
            c.sb.append("\n'exists' could not resolve path.");
            Initializer.log(t);
          }
        }else{
          c.sb.append("\n'exists' accepts exactly one argument.");
        }
        return false;
      }
    });
    commandMap.put("!exists", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==2){
          try{
            return Files.notExists(HelperAPI.resolve(tokens[1]));
          }catch(InvalidPathException t){
            c.sb.append("\n'!exists' could not resolve path.");
            Initializer.log(t);
          }
        }else{
          c.sb.append("\n'!exists' accepts exactly one argument.");
        }
        return false;
      }
    });
    commandMap.put("set", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==3){
          final String key = tokens[1];
          final String value = tokens[2];
          switch (key.toLowerCase()){
            case "connectionurl":{
              if (!value.equals(Config.connectionURL)){
                Config.connectionURL = value;
                Sync.licenseSynced = false;
                TunnelSSH.close();
                Initializer.status = "Initialized";
                c.saveConfig = true;
              }
              break;
            }
            case "username":{
              if (!value.equals(Config.username)){
                Config.username = value;
                Initializer.status = "Initialized";
                c.saveConfig = true;
              }
              break;
            }
            case "password":{
              if (!value.equals(Config.password)){
                Config.password = value;
                Initializer.status = "Initialized";
                c.saveConfig = true;
              }
              break;
            }
            case "keystorepassword":{
              if (!value.equals(Config.keystorePassword)){
                Config.keystorePassword = value;
                Initializer.status = "Initialized";
                c.saveConfig = true;
              }
              break;
            }
            case "maxrandomoffset":{
              try{
                Config.maxRandomOffset = Long.parseLong(value);
                c.saveConfig = true;
              }catch(NumberFormatException e){
                c.sb.append("\n'set' failed to parse number from expected value.");
                return false;
              }
              break;
            }
            case "cronsync":{
              c.saveConfig = true;
              if (!Config.cron.set(value)){
                c.sb.append("\n'set' failed to parse cron expression.");
                return false;
              }
              break;
            }
            case "ram":{
              try{
                final int ram = Integer.parseInt(value);
                if (ram<512 || ram>131072){
                  c.sb.append("\n'set' ram value is out-of-range.");
                  return false;
                }
                final ConfigurationDecorator config = new ConfigurationDecorator(MonitoredLauncher.loadConfiguration());
                if (config.getMaxMemory()!=ram){
                  config.setMaxMemory(ram);
                  MonitoredLauncher.saveConfiguration(config.getConfiguration());
                }
              }catch(NumberFormatException e){
                c.sb.append("\n'set' failed to parse number from expected value.");
                return false;
              }catch(Throwable t){
                c.sb.append("\n'set' encountered error while configuring RAM.");
                Initializer.log(t);
                return false;
              }
              break;
            }
            default:{
              c.sb.append("\n'set' does not recognize key: "+key);
              return false;
            }
          }
          return true;
        }else{
          c.sb.append("\n'set' accepts exactly two arguments.");
        }
        return false;
      }
    });
    commandMap.put("regex", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==3 || tokens.length==4){
          try{
            final Path p = HelperAPI.resolve(tokens[1]);
            final Pattern find = Pattern.compile(tokens[2], Pattern.MULTILINE|Pattern.DOTALL);
            final String replace = tokens.length==4?tokens[3]:null;
            String data = new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
            if (replace==null){
              final Matcher m = find.matcher(data);
              data = null;
              c.sb.append("\nRegex Matches:");
              int i,j;
              boolean first;
              while (m.find()){
                i = m.groupCount();
                if (i==0){
                  c.sb.append('\n').append(m.group(0));
                }else if (i==1){
                  c.sb.append('\n').append(m.group(1));
                }else{
                  c.sb.append("\n(");
                  first = true;
                  for (j=1;j<=i;++j){
                    if (first){
                      first = false;
                    }else{
                      c.sb.append(',');
                    }
                    c.sb.append(m.group(j));
                  }
                  c.sb.append(')');
                }
              }
            }else{
              data = find.matcher(data).replaceAll(replace);
              final ByteBuffer buf = ByteBuffer.wrap(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
              data = null;
              try(
                FileChannel out = FileChannel.open(p, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
              ){
                while (buf.hasRemaining()){
                  out.write(buf);
                }
              }
            }
            return true;
          }catch(InvalidPathException t){
            c.sb.append("\n'regex' could not resolve path.");
            Initializer.log(t);
          }catch(PatternSyntaxException t){
            c.sb.append("\n'regex' could not construct a Pattern from the given expression.");
            Initializer.log(t);
          }catch(IOException t){
            c.sb.append("\n'regex' failed to manipulate file.");
            Initializer.log(t);
          }
        }else{
          c.sb.append("\n'regex' accepts either 2 or 3 arguments.");
        }
        return false;
      }
    });
    commandMap.put("download", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==3){
          try{
            final String src = tokens[1];
            final Path dst = HelperAPI.resolve(tokens[2]);
            if (c.sftp==null){
              c.sftp = new ConnectSFTP();
            }
            if (c.sftp.downloadFile(src, dst)){
              return true;
            }
            c.sb.append("\n'download' encountered an error.");
          }catch(InvalidPathException t){
            c.sb.append("\n'download' could not resolve path.");
            Initializer.log(t);
          }
        }else{
          c.sb.append("\n'download' accepts exactly two arguments.");
        }
        return false;
      }
    });
    commandMap.put("upload", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==3){
          try{
            final Path src = HelperAPI.resolve(tokens[1]);
            final String dst = tokens[2];
            if (c.sftp==null){
              c.sftp = new ConnectSFTP();
            }
            if (c.sftp.uploadFile(src, dst)){
              return true;
            }
            c.sb.append("\n'upload' encountered an error.");
          }catch(InvalidPathException t){
            c.sb.append("\n'upload' could not resolve path.");
            Initializer.log(t);
          }
        }else{
          c.sb.append("\n'upload' accepts exactly two arguments.");
        }
        return false;
      }
    });
    commandMap.put("canapplyupdate", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==2){
          try{
            return HelperAPI.canApplyUpdate(HelperAPI.resolve(tokens[1]).toFile());
          }catch(InvalidPathException t){
            c.sb.append("\n'canApplyUpdate' could not resolve path.");
            Initializer.log(t);
          }
        }else{
          c.sb.append("\n'canApplyUpdate' accepts exactly one argument.");
        }
        return false;
      }
    });
    commandMap.put("!canapplyupdate", new Cmd(){
      @Override public boolean exec(Command c, String[] tokens) throws Throwable {
        if (tokens.length==2){
          try{
            return !HelperAPI.canApplyUpdate(HelperAPI.resolve(tokens[1]).toFile());
          }catch(InvalidPathException t){
            c.sb.append("\n'!canApplyUpdate' could not resolve path.");
            Initializer.log(t);
          }
        }else{
          c.sb.append("\n'!canApplyUpdate' accepts exactly one argument.");
        }
        return false;
      }
    });
  }
  public volatile int id;
  private volatile String cmd;
  private final static Pattern lineSplitter = Pattern.compile("[\\n\\r]++");
  private final ArrayList<String[]> lines = new ArrayList<String[]>();
  private volatile boolean reboot = false;
  private volatile StringBuilder sb;
  private volatile boolean saveConfig = false;
  private volatile ConnectSFTP sftp = null;
  public volatile int lastExecExitCode = 0;
  public Command(int id, String cmd){
    this.id = id;
    this.cmd = cmd;
    String line;
    final Iterator<String> i = lineSplitter.splitAsStream(cmd).iterator();
    while (i.hasNext()){
      line = i.next().trim();
      if (line.length()>0){
        final String[] arr = Utility.tokenize(line);
        if (arr.length>0 && !arr[0].startsWith("//")){
          lines.add(arr);
          if (arr.length==1 && arr[0].equalsIgnoreCase("reboot")){
            reboot = true;
            break;
          }
        }
      }
    }
  }
  public boolean isEmpty(){
    return lines.isEmpty();
  }
  public boolean hasReboot(){
    return reboot;
  }
  private final static Pattern passwordKiller = Pattern.compile("^(set\\s++(?:keystore)?+password\\s++).*?$", Pattern.MULTILINE|Pattern.CASE_INSENSITIVE);
  public boolean execute(boolean log){
    if (log){
      Initializer.log("Executing Command:\n"+passwordKiller.matcher(cmd).replaceAll("$1*"));
    }
    sb = new StringBuilder();
    try{
      try{
        for (String[] tokens: lines){
          if (!execute(tokens)){
            return false;
          }
        }
        return true;
      }finally{
        if (log){
          if (sb.length()>0){
            Initializer.log("Command Output:"+sb.toString());
          }
          sb = null;
        }
        if (saveConfig){
          Config.save();
        }
        if (sftp!=null){
          sftp.close();
        }
      }
    }catch(Throwable t){
      Initializer.log(t);
      return false;
    }
  }
  private boolean execute(final String[] tokens) throws Throwable {
    final Cmd command = commandMap.get(tokens[0].toLowerCase());
    if (command==null){
      sb.append("\nUnknown command: "+tokens[0]);
      return false;
    }
    return command.exec(this, tokens);
  }
  public StringBuilder getStringBuilder(){
    return sb;
  }
  private static interface Cmd {
    public boolean exec(Command c, String[] tokens) throws Throwable;
  }
}