package aces.webctrl.postgresql.core;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import com.jcraft.jsch.*;
public class SSHProxy {
  private volatile static Path file = null;
  private volatile static Path key = null;
  private volatile static boolean useProxy = false;
  private volatile static String host = null;
  private volatile static String dst_host = null;
  private volatile static String username = null;
  private volatile static String knownhosts = null;
  private volatile static int port = 0;
  private volatile static int src_port = 0;
  private volatile static int dst_port = 0;
  private volatile static Session session = null;
  public synchronized static void init(Path file, Path key){
    SSHProxy.file = file;
    SSHProxy.key = key;
    load();
  }
  public synchronized static void delete(){
    deactivate();
    useProxy = false;
    host = null;
    dst_host = null;
    username = null;
    knownhosts = null;
    port = 0;
    src_port = 0;
    dst_port = 0;
    try{
      Files.deleteIfExists(file);
      Files.deleteIfExists(key);
    }catch(Throwable t){
      if (Initializer.debug()){
        Initializer.log(t);
      }
    }
  }
  public synchronized static boolean set(String host, String dst_host, String username, String knownhosts, String key, int port, int src_port, int dst_port){
    SSHProxy.host = host;
    SSHProxy.dst_host = dst_host;
    SSHProxy.username = username;
    SSHProxy.knownhosts = knownhosts;
    SSHProxy.port = port;
    SSHProxy.src_port = src_port;
    SSHProxy.dst_port = dst_port;
    try{
      ByteBuffer buf = ByteBuffer.wrap(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      try(
        FileChannel out = FileChannel.open(SSHProxy.key, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
      ){
        while (buf.hasRemaining()){
          out.write(buf);
        }
      }
      buf = null;
      int l = 0;
      l+=host.length();
      l+=dst_host.length();
      l+=username.length();
      l+=knownhosts.length();
      l<<=1;
      l+=32;
      final SerializationStream s = new SerializationStream(l, true);
      s.write(host);
      s.write(dst_host);
      s.write(username);
      s.write(knownhosts);
      s.write(port);
      s.write(src_port);
      s.write(dst_port);
      buf = s.getBuffer();
      try(
        FileChannel out = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
      ){
        while (buf.hasRemaining()){
          out.write(buf);
        }
      }
    }catch(Throwable t){
      Initializer.log(t);
      delete();
      return false;
    }
    useProxy = true;
    return true;
  }
  public synchronized static void load(){
    try{
      if (file==null || key==null || !Files.exists(file) || !Files.exists(key)){
        useProxy = false;
        return;
      }
      final SerializationStream s = new SerializationStream(Files.readAllBytes(file));
      host = s.readString();
      dst_host = s.readString();
      username = s.readString();
      knownhosts = s.readString();
      port = s.readInt();
      src_port = s.readInt();
      dst_port = s.readInt();
      if (!s.end()){
        Initializer.log("Proxy corrupted.",true);
        delete();
      }
      useProxy = true;
    }catch(Throwable t){
      useProxy = false;
      Initializer.log(t);
    }
  }
  public synchronized static void activate(){
    if (!useProxy){
      return;
    }
    try{
      final JSch jsch = new JSch();
      jsch.setKnownHosts(new ByteArrayInputStream(knownhosts.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      jsch.addIdentity(key.toString());
      session = jsch.getSession(username, host, port);
      session.setTimeout(10000);
      session.setConfig("StrictHostKeyChecking", "yes");
      session.connect(10000);
      session.setPortForwardingL(src_port, dst_host, dst_port);
    }catch(Throwable t){
      deactivate();
      Initializer.log(t);
    }
  }
  public synchronized static void deactivate(){
    if (!useProxy || session==null){
      return;
    }
    try{
      session.disconnect();
    }catch(Throwable t){
      Initializer.log(t);
    }
    session = null;
  }
  public synchronized static boolean isActive(){
    if (session!=null){
      if (session.isConnected()){
        return true;
      }else{
        try{
          session.disconnect();
        }catch(Throwable t){
          Initializer.log(t);
        }
        session = null;
      }
    }
    return false;
  }
  public static boolean useProxy(){
    return useProxy;
  }
}