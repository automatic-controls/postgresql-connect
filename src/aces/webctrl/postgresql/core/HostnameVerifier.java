package aces.webctrl.postgresql.core;
import java.nio.*;
import java.nio.file.*;
import java.nio.channels.*;
public class HostnameVerifier {
  public volatile static Path file = null;
  public static void init(Path file){
    HostnameVerifier.file = file;
  }
  public static boolean verify(){
    if (file==null){
      return true;
    }
    final String hostname = Utility.getHostName();
    try{
      synchronized (HostnameVerifier.class){
        if (Files.exists(file)){
          return hostname.equals(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8));
        }else{
          final ByteBuffer buf = ByteBuffer.wrap(hostname.getBytes(java.nio.charset.StandardCharsets.UTF_8));
          try(
            FileChannel out = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
          ){
            while (buf.hasRemaining()){
              out.write(buf);
            }
          }
          return true;
        }
      }
    }catch(Throwable t){
      Initializer.log(t);
      return false;
    }
  }
}