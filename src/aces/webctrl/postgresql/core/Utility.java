/*
  BSD 3-Clause License
  Copyright (c) 2022, Automatic Controls Equipment Systems, Inc.
  Contributors: Cameron Vogt (@cvogt729)
*/
package aces.webctrl.postgresql.core;
import java.util.regex.*;
import java.io.*;
import java.nio.file.*;
public class Utility {
  private final static Pattern lineEnding = Pattern.compile("\\r?+\\n");
  /**
   * Writes all bytes from the specified resource to the output file.
   */
  public static void extractResource(String name, Path out) throws Throwable {
    try(
      InputStream s = Utility.class.getClassLoader().getResourceAsStream(name);
      OutputStream t = Files.newOutputStream(out);
    ){
      int read;
      byte[] buffer = new byte[8192];
      while ((read = s.read(buffer, 0, 8192)) >= 0) {
        t.write(buffer, 0, read);
      }
    }
  }
  /**
   * Loads all bytes from the given resource and convert to a {@code UTF-8} string.
   * @return the {@code UTF-8} string representing the given resource.
   */
  public static String loadResourceAsString(String name) throws Throwable {
    java.util.ArrayList<byte[]> list = new java.util.ArrayList<byte[]>();
    int len = 0;
    byte[] buf;
    int read;
    try(
      InputStream s = Utility.class.getClassLoader().getResourceAsStream(name);
    ){
      while (true){
        buf = new byte[8192];
        read = s.read(buf);
        if (read==-1){
          break;
        }
        len+=read;
        list.add(buf);
        if (read!=buf.length){
          break;
        }
      }
    }
    byte[] arr = new byte[len];
    int i = 0;
    for (byte[] bytes:list){
      read = Math.min(bytes.length,len);
      len-=read;
      System.arraycopy(bytes, 0, arr, i, read);
      i+=read;
    }
    return lineEnding.matcher(new String(arr, java.nio.charset.StandardCharsets.UTF_8)).replaceAll(System.lineSeparator());
  }
  /**
   * Encodes a string to be parsed as a list.
   * Intended to be used to encode AJAX responses.
   * Escapes semi-colons and backslashes using the backslash character.
   */
  public static String encodeAJAX(String str){
    int len = str.length();
    StringBuilder sb = new StringBuilder(len+16);
    char c;
    for (int i=0;i<len;++i){
      c = str.charAt(i);
      if (c=='\\' || c==';'){
        sb.append('\\');
      }
      sb.append(c);
    }
    return sb.toString();
  }
  /**
   * Escapes a {@code String} for usage in HTML attribute values.
   * @param str is the {@code String} to escape.
   * @return the escaped {@code String}.
   */
  public static String escapeHTML(String str){
    if (str==null){
      return "";
    }
    int len = str.length();
    StringBuilder sb = new StringBuilder(len+16);
    char c;
    int j;
    for (int i=0;i<len;++i){
      c = str.charAt(i);
      j = c;
      if (j>=32 && j<127){
        switch (c){
          case '&':{
            sb.append("&amp;");
            break;
          }
          case '"':{
            sb.append("&quot;");
            break;
          }
          case '\'':{
            sb.append("&apos;");
            break;
          }
          case '<':{
            sb.append("&lt;");
            break;
          }
          case '>':{
            sb.append("&gt;");
            break;
          }
          default:{
            sb.append(c);
          }
        }
      }else if (j<1114111 && (j<=55296 || j>57343)){
        sb.append("&#").append(Integer.toString(j)).append(";");
      }
    }
    return sb.toString();
  }
  /**
   * Intended to escape strings for use in Javascript.
   * Escapes backslashes, single quotes, and double quotes.
   * Replaces new-line characters with the corresponding escape sequences.
   */
  public static String escapeJS(String str){
    if (str==null){
      return "";
    }
    int len = str.length();
    StringBuilder sb = new StringBuilder(len+16);
    char c;
    for (int i=0;i<len;++i){
      c = str.charAt(i);
      switch (c){
        case '\\': case '\'': case '"': {
          sb.append('\\').append(c);
          break;
        }
        case '\n': {
          sb.append("\\n");
          break;
        }
        case '\t': {
          sb.append("\\t");
          break;
        }
        case '\r': {
          sb.append("\\r");
          break;
        }
        case '\b': {
          sb.append("\\b");
          break;
        }
        case '\f': {
          sb.append("\\f");
          break;
        }
        default: {
          sb.append(c);
        }
      }
    }
    return sb.toString();
  }
  /**
   * Intended to escape strings for use in PostgreSQL.
   * <p>Example:
   * <pre>
   * assert escapePostgreSQL("My cat's name is:\nMidnight").equals("E'My cat\\'s name is:\\nMidnight'");
   * </pre>
   */
  public static String escapePostgreSQL(String str){
    if (str==null){
      return "E''";
    }
    int len = str.length();
    StringBuilder sb = new StringBuilder(len+16);
    sb.append("E'");
    char c;
    for (int i=0;i<len;++i){
      c = str.charAt(i);
      switch (c){
        case '\\': case '\'': {
          sb.append('\\').append(c);
          break;
        }
        case '\n': {
          sb.append("\\n");
          break;
        }
        case '\t': {
          sb.append("\\t");
          break;
        }
        case '\r': {
          sb.append("\\r");
          break;
        }
        case '\b': {
          sb.append("\\b");
          break;
        }
        case '\f': {
          sb.append("\\f");
          break;
        }
        default: {
          sb.append(c);
        }
      }
    }
    sb.append('\'');
    return sb.toString();
  }
  /**
   * Reverses the order and XORs each character with 4.
   * The array is modified in-place, so no copies are made.
   * For convenience, the given array is returned.
   */
  public static char[] obfuscate(char[] arr){
    char c;
    for (int i=0,j=arr.length-1;i<=j;++i,--j){
      if (i==j){
        arr[i]^=4;
      }else{
        c = (char)(arr[j]^4);
        arr[j] = (char)(arr[i]^4);
        arr[i] = c;
      }
    }
    return arr;
  }
  /**
   * Converts a character array into a byte array.
   */
  public static byte[] toBytes(char[] arr){
    return java.nio.charset.StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(arr)).array();
  }
  /**
   * @return the first non-null element, or null if every parameter is null.
   */
  public static String coalesce(String... args){
    for (int i=0;i<args.length;++i){
      if (args[i]!=null){
        return args[i];
      }
    }
    return null;
  }
  private final static Pattern versionFilter1 = Pattern.compile("[^0-9\\.]");
  private final static Pattern versionFilter2 = Pattern.compile("(?<=^|\\.)0+(?=[^0])");
  private final static Pattern versionFilter3 = Pattern.compile("(?<=\\.)\\.++");
  private final static Pattern versionFilter4 = Pattern.compile("^\\.|\\.$");
  private final static Pattern versionFilter5 = Pattern.compile("(?:\\.0|^0)++$");
  private final static Pattern versionSplitter = Pattern.compile("\\.");
  /**
   * Compares two version strings to determine which is newer.
   * @return {@code 0} if both strings represent the same version.
   * {@code 1} if {@code a} is newer than {@code b}.
   * {@code -1} if {@code b} is newer than {@code a}.
   */
  public static int compareVersions(String a, String b){
    if (a==b){
      return 0;
    }else if (a==null){
      return -1;
    }else if (b==null){
      return 1;
    }
    a = versionFilter1.matcher(a).replaceAll("");
    a = versionFilter2.matcher(a).replaceAll("");
    a = versionFilter3.matcher(a).replaceAll("");
    a = versionFilter4.matcher(a).replaceAll("");
    a = versionFilter5.matcher(a).replaceAll("");
    b = versionFilter1.matcher(b).replaceAll("");
    b = versionFilter2.matcher(b).replaceAll("");
    b = versionFilter3.matcher(b).replaceAll("");
    b = versionFilter4.matcher(b).replaceAll("");
    b = versionFilter5.matcher(b).replaceAll("");
    if (a.equals(b)){
      return 0;
    }
    final String[] aa = versionSplitter.split(a);
    final String[] bb = versionSplitter.split(b);
    final int l = Math.min(aa.length, bb.length);
    try{
      for (int i=0;i<l;++i){
        if (!aa[i].equals(bb[i])){
          return Integer.parseInt(aa[i])>Integer.parseInt(bb[i])?1:-1;
        }
      }
      if (aa.length==bb.length){
        return 0;
      }
      return aa.length>bb.length?1:-1;
    }catch(NumberFormatException e){
      //This should only happen when a version number exceeds the range of Integer (never going to happen for our use cases, so we don't care)
      return 0;
    }
  }
}