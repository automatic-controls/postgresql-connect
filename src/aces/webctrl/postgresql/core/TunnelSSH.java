package aces.webctrl.postgresql.core;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import com.jcraft.jsch.*;
import java.util.*;
import java.util.regex.*;
public class TunnelSSH {
  public volatile static String lastConnectionString = null;
  private volatile static Session session = null;
  public final static HashMap<Integer, Tunnel> tunnels = new HashMap<>();
  public final static HashMap<Integer, Tunnel> persistentTunnels = new HashMap<>();
  public synchronized static void checkTunnelExpiry(){
    if (tunnels.isEmpty() || !isOpen()){
      return;
    }
    final long now = System.currentTimeMillis();
    Tunnel tun;
    for (Iterator<Map.Entry<Integer, Tunnel>> it = tunnels.entrySet().iterator(); it.hasNext();){
      tun = it.next().getValue();
      if (tun.expiry>0 && tun.expiry<now){
        it.remove();
        try{
          session.delPortForwardingR(tun.listenPort);
        }catch(Throwable t){
          Initializer.log(t);
        }
        if (Initializer.debug()){
          Initializer.log("Tunnel on port "+tun.listenPort+" has expired.");
        }
      }
    }
    if (tunnels.isEmpty() && persistentTunnels.isEmpty()){
      close();
    }
  }
  public synchronized static boolean open(int listenPort, int targetPort, long timeout){
    try{
      if (tunnels.containsKey(listenPort) || persistentTunnels.containsKey(listenPort)){
        Initializer.log("A tunnel on port "+listenPort+" already exists.",true);
        return false;
      }
      if (!isOpen()){
        connect();
        if (!isOpen()){
          return false;
        }
      }
      session.setPortForwardingR("*", listenPort, "127.0.0.1", targetPort);
      tunnels.put(listenPort, new Tunnel(listenPort, targetPort, timeout));
      return true;
    }catch(Throwable t){
      Initializer.log(t);
    }
    return false;
  }
  public synchronized static void closeTunnel(int listenPort){
    if (tunnels.containsKey(listenPort) && isOpen()){
      if (tunnels.size()==1 && persistentTunnels.isEmpty()){
        close();
      }else{
        tunnels.remove(listenPort);
        try{
          session.delPortForwardingR(listenPort);
        }catch(Throwable t){
          Initializer.log(t);
        }
      }
    }
  }
  public synchronized static boolean refreshPersistentTunnels(HashMap<Integer,Tunnel> tuns){
    if (tuns==null || tuns.isEmpty()){
      closePersistent();
      return true;
    }
    boolean createAll = false;
    if (!isOpen()){
      connect();
      if (!isOpen()){
        return false;
      }
      createAll = true;
    }
    boolean ret = true;
    Tunnel t1,t2;
    // Delete existing transient tunnels that match a new persistent tunnel
    for (Iterator<Map.Entry<Integer, Tunnel>> it = tunnels.entrySet().iterator(); it.hasNext();){
      t1 = it.next().getValue();
      if (tuns.containsKey(t1.listenPort)){
        it.remove();
        if (!createAll){
          try{
            session.delPortForwardingR(t1.listenPort);
          }catch(Throwable t){
            Initializer.log(t);
            ret = false;
          }
        }
      }
    }
    // Delete existing persistent tunnels that no longer exist
    for (Iterator<Map.Entry<Integer, Tunnel>> it = persistentTunnels.entrySet().iterator(); it.hasNext();){
      t1 = it.next().getValue();
      t2 = tuns.get(t1.listenPort);
      if (t2==null || t1.targetPort!=t2.targetPort){
        it.remove();
        if (!createAll){
          try{
            session.delPortForwardingR(t1.listenPort);
          }catch(Throwable t){
            Initializer.log(t);
            ret = false;
          }
        }
      }
    }
    // Create new persistent tunnels
    for (Tunnel tun : tuns.values()){
      if (createAll || !persistentTunnels.containsKey(tun.listenPort)){
        try{
          session.setPortForwardingR("*", tun.listenPort, "127.0.0.1", tun.targetPort);
          persistentTunnels.put(tun.listenPort, tun);
        }catch(Throwable t){
          Initializer.log(t);
          ret = false;
        }
      }
    }
    return ret;
  }
  public synchronized static void checkConnectionChanges(){
    if (tunnels.size()>0 && isOpen()){
      final String secondary_ids = Sync.settings.get("ftp_port_secondary_ids");
      final String host = Sync.settings.get("ftp_host");
      final String port = secondary_ids==null || !Pattern.compile("(?:^|;)"+Config.ID+"(?:$|;)", Pattern.MULTILINE).matcher(secondary_ids).find() ?
        Sync.settings.get("ftp_port") : Sync.settings.get("ftp_port_secondary");
      final String username = Sync.settings.get("ftp_username");
      if (!(host+":"+port+"@"+username).equals(lastConnectionString)){
        close();
      }
    }
  }
  private synchronized static void connect(){
    final String secondary_ids = Sync.settings.get("ftp_port_secondary_ids");
    final String host = Sync.settings.get("ftp_host");
    final String port = secondary_ids==null || !Pattern.compile("(?:^|;)"+Config.ID+"(?:$|;)", Pattern.MULTILINE).matcher(secondary_ids).find() ?
      Sync.settings.get("ftp_port") : Sync.settings.get("ftp_port_secondary");
    final String username = Sync.settings.get("ftp_username");
    final String key = Sync.settings.get("ftp_key");
    if (host==null || port==null || username==null || key==null){
      Initializer.log("Failed to connect to SFTP server because connection settings were not provided.",true);
      return;
    }
    try{
      final int _port = Integer.parseInt(port);
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
        session = jsch.getSession(username, host, _port);
        session.setTimeout(10000);
        session.setConfig("StrictHostKeyChecking", "yes");
        session.connect(10000);
        lastConnectionString = host+":"+port+"@"+username;
      }catch(Throwable e){
        close();
        Initializer.log(e);
        return;
      }
    }catch(Throwable e){
      Initializer.log(e);
      return;
    }
  }
  public synchronized static boolean isOpen(){
    if (session!=null){
      if (session.isConnected()){
        return true;
      }else{
        tunnels.clear();
        persistentTunnels.clear();
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
  public synchronized static void closeTransient(){
    if (persistentTunnels.isEmpty()){
      close();
    }else if (isOpen()){
      for (Tunnel tun : tunnels.values()){
        try{
          session.delPortForwardingR(tun.listenPort);
        }catch(Throwable t){
          Initializer.log(t);
        }
      }
      tunnels.clear();
    }
  }
  public synchronized static void closePersistent(){
    if (tunnels.isEmpty()){
      close();
    }else if (isOpen()){
      for (Tunnel tun : persistentTunnels.values()){
        try{
          session.delPortForwardingR(tun.listenPort);
        }catch(Throwable t){
          Initializer.log(t);
        }
      }
      persistentTunnels.clear();
    }
  }
  public synchronized static void close(){
    try{
      if (session!=null){
        tunnels.clear();
        persistentTunnels.clear();
        session.disconnect();
        session = null;
      }
    }catch(Throwable t){
      Initializer.log(t);
      session = null;
    }
  }
  public static class Tunnel {
    public volatile int listenPort;
    public volatile int targetPort;
    public volatile long expiry;
    public Tunnel(int listenPort, int targetPort, long timeout){
      this.listenPort = listenPort;
      this.targetPort = targetPort;
      this.expiry = timeout<=0?0:timeout+System.currentTimeMillis();
    }
    @Override public boolean equals(Object o){
      if (o==null || !(o instanceof Tunnel)){
        return false;
      }
      Tunnel t = (Tunnel)o;
      return listenPort==t.listenPort && targetPort==t.targetPort;
    }
  }
}