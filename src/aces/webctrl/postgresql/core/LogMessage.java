package aces.webctrl.postgresql.core;
import java.time.*;
import java.io.*;
public class LogMessage {
  private volatile String message;
  private final OffsetDateTime stamp = OffsetDateTime.now();
  private volatile boolean err;
  public LogMessage(String message){
    this.message = message;
    err = false;
  }
  public LogMessage(Throwable t){
    StringWriter w = new StringWriter(64);
    t.printStackTrace(new PrintWriter(w));
    message = w.toString();
    err = true;
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