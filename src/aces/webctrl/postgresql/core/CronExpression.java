package aces.webctrl.postgresql.core;
import java.util.*;
import java.time.*;
import java.time.format.*;
import org.springframework.scheduling.support.*;
public class CronExpression {
  public final static DateTimeFormatter format = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());
  private volatile String expr = null;
  private volatile CronSequenceGenerator gen = null;
  private volatile long next = -1L;
  public CronExpression(){}
  public CronExpression(String expr){
    set(expr);
  }
  /**
   * Sets the underlying text which defines this Cron expression.
   * @return whether the given {@code String} was successfully parsed.
   */
  public boolean set(String expr){
    if (expr.equals(this.expr)){
      return true;
    }else{
      this.expr = expr;
      try{
        gen = new CronSequenceGenerator(expr);
        return true;
      }catch(Throwable t){
        gen = null;
        return false;
      }finally{
        reset();
      }
    }
  }
  /**
   * Recomputes the next scheduled instant for this Cron expression.
   */
  public void reset(){
    CronSequenceGenerator gen = this.gen;
    if (gen==null){
      next = -1;
    }else{
      try{
        next = gen.next(new Date()).getTime();
      }catch(Throwable t){
        next = -1;
      }
    }
  }
  /**
   * @return the number of milliseconds from 1970-01-01T00:00:00Z representing the next scheduled instant of this Cron expression.
   */
  public long getNext(){
    return next;
  }
  /**
   * @return a formatted datetime {@code String} representing the next scheduled instant of this Cron expression.
   */
  public String getNextString(String def){
    final long x = next;
    return x==-1?def:format(x);
  }
  /**
   * @return the underlying text which defines this Cron expression, or {@code null} if none exists.
   */
  @Override public String toString(){
    return expr;
  }
  /**
   * @param epochMilli the number of milliseconds from 1970-01-01T00:00:00Z.
   * @return a formatted datetime {@code String} representing the specified instant in time.
   */
  public static String format(long epochMilli){
    return format.format(Instant.ofEpochMilli(epochMilli));
  }
}
