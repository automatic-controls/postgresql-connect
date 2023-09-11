package aces.webctrl.postgresql.core;
import java.time.*;
public class LogMessage {
  private volatile String message;
  private volatile OffsetDateTime stamp;
  private volatile boolean err;
  public LogMessage(String message){
    stamp = OffsetDateTime.now();
    this.message = message;
    err = false;
  }
  public LogMessage(Throwable t){
    stamp = OffsetDateTime.now();
    message = Utility.getStackTrace(t);
    err = true;
  }
  public LogMessage(OffsetDateTime stamp, boolean err, String message){
    this.stamp = stamp;
    this.err = err;
    this.message = message;
  }
  public String getMessage(){
    return message;
  }
  public OffsetDateTime getTimestamp(){
    return stamp;
  }
  public boolean isError(){
    return err;
  }
}