package aces.webctrl.postgresql.core;
public class Container<T> {
  public volatile T x;
  public Container(){
    x = null;
  }
  public Container(T x){
    this.x = x;
  }
}