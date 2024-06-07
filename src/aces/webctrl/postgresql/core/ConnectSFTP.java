package aces.webctrl.postgresql.core;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import com.jcraft.jsch.*;
public class ConnectSFTP implements AutoCloseable {
  private volatile Session jschSession = null;
  private volatile ChannelSftp jschChannel = null;
  public ConnectSFTP(){
    final String host = Sync.settings.get("ftp_host");
    final String port = Sync.settings.get("ftp_port");
    final String username = Sync.settings.get("ftp_username");
    final String key = Sync.settings.get("ftp_key");
    if (host==null || port==null || username==null || key==null){
      Initializer.log("Failed to connect to SFTP server because connection settings were not provided.",true);
      return;
    }
    try{
      final ByteBuffer buf = ByteBuffer.wrap(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      synchronized(ConnectSFTP.class){
        try(
          FileChannel out = FileChannel.open(Initializer.sftpkey, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        ){
          while (buf.hasRemaining()){
            out.write(buf);
          }
        }
      }
      connect(host, Integer.parseInt(port), username);
    }catch(Throwable e){
      Initializer.log(e);
      return;
    }
  }
  public ConnectSFTP(String host, int port, String username){
    connect(host, port, username);
  }
  private void connect(String host, int port, String username){
    try{
      final JSch jsch = new JSch();
      {
        final String knownHosts = Sync.settings.get("ftp_known_hosts");
        if (knownHosts==null){
          Initializer.log("Failed to connect to SFTP server because ftp_known_hosts is undefined.",true);
          return;
        }
        jsch.setKnownHosts(new ByteArrayInputStream(knownHosts.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      }
      jsch.addIdentity(Initializer.sftpkey.toString());
      jschSession = jsch.getSession(username, host, port);
      jschSession.setTimeout(10000);
      jschSession.setConfig("StrictHostKeyChecking", "yes");
      jschSession.connect(10000);
      jschChannel = (ChannelSftp)jschSession.openChannel("sftp");
      jschChannel.connect(10000);
    }catch(Throwable t){
      Initializer.log(t);
      close();
      return;
    }
  }
  public boolean overwriteFile(InputStream src, String dst){
    if (jschChannel==null){
      return false;
    }
    try{
      jschChannel.put(src, dst, new SftpProgressMonitor(){
        @Override public void init(int op, String src, String dest, long max){}
        @Override public boolean count(long count){
          return !Initializer.stop;
        }
        @Override public void end(){}
      }, ChannelSftp.OVERWRITE);
      return !Initializer.stop;
    }catch(Throwable t){
      Initializer.log(t);
      return false;
    }
  }
  public boolean uploadFile(Path src, String dst){
    if (jschChannel==null){
      return false;
    }
    try{
      try(
        BufferedInputStream s = new BufferedInputStream(Files.newInputStream(src));
      ){
        if (overwriteFile(s, dst)){
          return true;
        }
      }
      return false;
    }catch(Throwable t){
      Initializer.log(t);
      return false;
    }
  }
  public boolean retrieveFile(String path, OutputStream out){
    if (jschChannel==null){
      return false;
    }
    try{
      jschChannel.get(path, out, new SftpProgressMonitor(){
        @Override public void init(int op, String src, String dest, long max){}
        @Override public boolean count(long count){
          return !Initializer.stop;
        }
        @Override public void end(){}
      });
      return !Initializer.stop;
    }catch(Throwable t){
      Initializer.log(t);
      return false;
    }
  }
  public boolean downloadFile(String path, Path out){
    if (jschChannel==null){
      return false;
    }
    try{
      try(
        BufferedOutputStream s = new BufferedOutputStream(Files.newOutputStream(out));
      ){
        if (retrieveFile(path,s)){
          return true;
        }
      }
      Files.deleteIfExists(out);
      return false;
    }catch(Throwable t){
      Initializer.log(t);
      return false;
    }
  }
  public boolean isOpen(){
    return jschChannel!=null && jschChannel.isConnected();
  }
  @Override public void close(){
    try{
      if (jschChannel!=null){
        jschChannel.disconnect();
        jschChannel = null;
      }
    }catch(Throwable t){
      Initializer.log(t);
      jschChannel = null;
    }
    try{
      if (jschSession!=null){
        jschSession.disconnect();
        jschSession = null;
      }
    }catch(Throwable t){
      Initializer.log(t);
      jschSession = null;
    }
  }
}