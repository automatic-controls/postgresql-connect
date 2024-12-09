/*
  BSD 3-Clause License
  Copyright (c) 2022, Automatic Controls Equipment Systems, Inc.
  Contributors: Cameron Vogt (@cvogt729)
*/
package aces.webctrl.postgresql.core;
import java.util.*;
import java.util.regex.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.time.format.*;
public class Utility {
  /**
   * Used to convert between time variables and user-friendly strings.
   */
  public final static DateTimeFormatter timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
  private final static Pattern lineEnding = Pattern.compile("\\r?+\\n");
  private final static Pattern formatter = Pattern.compile("\\$(\\d)");
  /**
   * Split a string into tokens.
   */
  public static String[] tokenize(String s){
    final int len = s.length();
    int i,j,k;
    char c;
    boolean esc = false;
    boolean quote = false;
    for (i=0,j=0,k=0;i<len;++i){
      if (esc){
        ++j;
        esc = false;
      }else{
        c = s.charAt(i);
        if (c=='^'){
          esc = true;
        }else if (quote){
          if (c=='"'){
            quote = false;
            j = 0;
            ++k;
          }else{
            ++j;
          }
        }else if (c=='"'){
          quote = true;
        }else if (c==' '){
          if (j>0){
            j = 0;
            ++k;
          }
        }else{
          ++j;
        }
      }
    }
    if (j>0){
      ++k;
    }
    final String[] tokens = new String[k];
    final StringBuilder sb = new StringBuilder();
    for (i=0,k=0;i<len;++i){
      c = s.charAt(i);
      if (esc){
        sb.append(c);
        esc = false;
      }else if (c=='^'){
        esc = true;
      }else if (quote){
        if (c=='"'){
          quote = false;
          tokens[k++] = sb.toString();
          sb.setLength(0);
        }else{
          sb.append(c);
        }
      }else if (c=='"'){
        quote = true;
      }else if (c==' '){
        if (sb.length()>0){
          tokens[k++] = sb.toString();
          sb.setLength(0);
        }
      }else{
        sb.append(c);
      }
    }
    if (sb.length()>0){
      tokens[k] = sb.toString();
    }
    return tokens;
  }
  /**
   * Replaces matches of the regular expression {@code %.*?%} with the corresponding environment variable.
   */
  public static String expandEnvironmentVariables(String str){
    if (str.indexOf('%')==-1){
      return str;
    }
    int len = str.length();
    StringBuilder out = new StringBuilder(len+16);
    StringBuilder var = new StringBuilder();
    String tmp;
    boolean env = false;
    char c;
    for (int i=0;i<len;++i){
      c = str.charAt(i);
      if (c=='%'){
        if (env){
          tmp = System.getenv(var.toString());
          if (tmp!=null){
            out.append(tmp);
            tmp = null;
          }
          var.setLength(0);
        }
        env^=true;
      }else if (env){
        var.append(c);
      }else{
        out.append(c);
      }
    }
    return out.toString();
  }
  /**
   * Assumes {@code src} and the parent of {@code dst} both exist.
   * Ensures the contents of {@code dst} exactly match the contents of {@code src}.
   * The last modified timestamp is used to determine whether two files of the same relative path are the same.
   */
  public static void copy(final Path src, final Path dst) throws IOException {
    if (Files.isDirectory(src)){
      boolean copyNew = false;
      if (Files.exists(dst)){
        if (Files.isDirectory(dst)){
          LinkedList<Path> arr = new LinkedList<Path>();
          try(
            DirectoryStream<Path> stream = Files.newDirectoryStream(dst);
          ){
            for (Path p:stream){
              arr.add(p);
            }
          }
          LinkedList<CopyEvent> copies = new LinkedList<CopyEvent>();
          try(
            DirectoryStream<Path> stream = Files.newDirectoryStream(src);
          ){
            Path p;
            for (Path a:stream){
              p = dst.resolve(a.getFileName());
              arr.remove(p);
              copies.add(new CopyEvent(a,p));
            }
          }
          for (Path p:arr){
            deleteTree(p);
            if (Initializer.stop){ return; }
          }
          arr = null;
          for (CopyEvent e:copies){
            e.exec();
            if (Initializer.stop){ return; }
          }
        }else{
          Files.delete(dst);
          copyNew = true;
        }
      }else{
        copyNew = true;
      }
      if (copyNew){
        Files.walkFileTree(src, new SimpleFileVisitor<Path>(){
          @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            Files.createDirectory(dst.resolve(src.relativize(dir)));
            return Initializer.stop?FileVisitResult.TERMINATE:FileVisitResult.CONTINUE;
          }
          @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
            Path p = dst.resolve(src.relativize(file));
            Files.copy(file, p);
            Files.setLastModifiedTime(p, Files.getLastModifiedTime(file));
            return Initializer.stop?FileVisitResult.TERMINATE:FileVisitResult.CONTINUE;
          }
        });
      }
    }else{
      FileTime srcTime = Files.getLastModifiedTime(src);
      if (Files.exists(dst)){
        if (Files.isDirectory(dst)){
          deleteTree(dst);
          if (Initializer.stop){ return; }
          Files.copy(src, dst);
          if (Initializer.stop){ return; }
          Files.setLastModifiedTime(dst, srcTime);
        }else if (Files.getLastModifiedTime(dst).compareTo(srcTime)!=0){
          Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
          if (Initializer.stop){ return; }
          Files.setLastModifiedTime(dst, srcTime);
        }
      }else{
        Files.copy(src, dst);
        if (Initializer.stop){ return; }
        Files.setLastModifiedTime(dst, srcTime);
      }
    }
  }
  /**
   * Recursively deletes a file tree.
   * @param root is the root of the tree to be deleted.
   */
  public static void deleteTree(Path root) throws IOException {
    Files.walkFileTree(root, new SimpleFileVisitor<Path>(){
      @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
        Files.delete(file);
        return Initializer.stop?FileVisitResult.TERMINATE:FileVisitResult.CONTINUE;
      }
      @Override public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
        if (e==null){
          Files.delete(dir);
          return Initializer.stop?FileVisitResult.TERMINATE:FileVisitResult.CONTINUE;
        }else{
          throw e;
        }
      }
    });
  }
  private static class CopyEvent {
    public Path src;
    public Path dst;
    public CopyEvent(Path src, Path dst){
      this.src = src;
      this.dst = dst;
    }
    public void exec() throws IOException {
      Utility.copy(src,dst);
    }
  }
  /**
   * Replaces occurrences of {@code $n} in the input {@code String} with the nth indexed argument.
   * For example, {@code format("Hello $0!", "Beautiful")=="Hello Beautiful!"}.
   */
  public static String format(final String s, final Object... args){
    final String[] args_ = new String[args.length];
    for (int i=0;i<args.length;++i){
      args_[i] = args[i]==null?"":Matcher.quoteReplacement(args[i].toString());
    }
    return replaceAll(s, formatter, new java.util.function.Function<MatchResult,String>(){
      public String apply(MatchResult m){
        int i = Integer.parseInt(m.group(1));
        return i<args.length?args_[i]:"";
      }
    });
  }
  /**
   * Meant to be used as an alternative to {@link Matcher#replaceAll(java.util.function.Function)} for compatibility with WebCTRL 7.0.
   */
  public static String replaceAll(String s, Pattern p, java.util.function.Function<MatchResult,String> replacer){
    final Matcher m = p.matcher(s);
    final StringBuffer sb = new StringBuffer(s.length());
    while (m.find()){
      m.appendReplacement(sb, replacer.apply(m));
    }
    m.appendTail(sb);
    return sb.toString();
  }
  /**
   * Writes all bytes from the specified resource to the output file.
   * @return {@code true} on success, {@code false} when the resource cannot be found.
   */
  public static boolean extractResource(String name, Path out) throws Throwable {
    try(
      InputStream s = Utility.class.getClassLoader().getResourceAsStream(name);
    ){
      if (s==null){
        return false;
      }
      try(
        OutputStream t = Files.newOutputStream(out);
      ){
        int read;
        byte[] buffer = new byte[8192];
        while ((read = s.read(buffer, 0, 8192)) >= 0) {
          t.write(buffer, 0, read);
        }
      }
    }
    return true;
  }
  /**
   * This method is provided for compatibility with older JRE versions.
   * Newer JREs already have a built-in equivalent of this method: {@code InputStream.readAllBytes()}.
   * @return a {@code byte[]} array containing all remaining bytes read from the {@code InputStream}.
   */
  public static byte[] readAllBytes(InputStream s) throws IOException {
    ArrayList<byte[]> list = new ArrayList<byte[]>();
    int len = 0;
    byte[] buf;
    int read;
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
    byte[] arr = new byte[len];
    int i = 0;
    for (byte[] bytes:list){
      read = Math.min(bytes.length,len);
      len-=read;
      System.arraycopy(bytes, 0, arr, i, read);
      i+=read;
    }
    return arr;
  }
  /**
   * Loads all bytes from the given resource and convert to a {@code UTF-8} string.
   * @return the {@code UTF-8} string representing the given resource.
   */
  public static String loadResourceAsString(String name) throws Throwable {
    byte[] arr;
    try(
      InputStream s = Utility.class.getClassLoader().getResourceAsStream(name);
    ){
      arr = readAllBytes(s);
    }
    return lineEnding.matcher(new String(arr, java.nio.charset.StandardCharsets.UTF_8)).replaceAll(System.lineSeparator());
  }
  /**
   * Loads all bytes from the given resource and convert to a {@code UTF-8} string.
   * @return the {@code UTF-8} string representing the given resource.
   */
  public static String loadResourceAsString(ClassLoader cl, String name) throws Throwable {
    byte[] arr;
    try(
      InputStream s = cl.getResourceAsStream(name);
    ){
      arr = readAllBytes(s);
    }
    return lineEnding.matcher(new String(arr, java.nio.charset.StandardCharsets.UTF_8)).replaceAll(System.lineSeparator());
  }
  /**
   * @return a string which encodes the given list.
   * @see #decodeList(String)
   */
  public static String encodeList(List<String> list){
    int cap = list.size()<<2;
    for (String s:list){
      cap+=s.length();
    }
    StringBuilder sb = new StringBuilder(cap);
    for (String s:list){
      sb.append(s.replace("\\", "\\\\").replace(";", "\\;")).append(';');
    }
    return sb.toString();
  }
  /**
   * @return a list decoded from the given string.
   * @see #encodeList(List)
   */
  public static ArrayList<String> decodeList(String s){
    int len = s.length();
    int i,j,k,max=0;
    char c;
    boolean esc = false;
    for (i=0,j=0,k=0;i<len;++i){
      if (esc){
        esc = false;
        ++k;
      }else{
        c = s.charAt(i);
        if (c=='\\'){
          esc = true;
        }else if (c==';'){
          ++j;
          if (k>max){
            max = k;
          }
          k = 0;
        }else{
          ++k;
        }
      }
    }
    ArrayList<String> list = new ArrayList<String>(j);
    StringBuilder sb = new StringBuilder(max);
    esc = false;
    for (i=0;i<len;++i){
      c = s.charAt(i);
      if (esc){
        esc = false;
        sb.append(c);
      }else if (c=='\\'){
        esc = true;
      }else if (c==';'){
        list.add(sb.toString());
        sb.setLength(0);
      }else{
        sb.append(c);
      }
    }
    return list;
  }
  /**
   * Escapes a {@code String} for usage in CSV document cells.
   * @param str is the {@code String} to escape.
   * @return the escaped {@code String}.
   */
  public static String escapeCSV(String str){
    if (str.indexOf(',')==-1 && str.indexOf('"')==-1 && str.indexOf('\n')==-1 && str.indexOf('\r')==-1){
      return str;
    }else{
      return '"'+str.replace("\"","\"\"")+'"';
    }
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
   * Encodes a JSON string.
   */
  public static String escapeJSON(String s){
    if (s==null){ return "NULL"; }
    int len = s.length();
    StringBuilder sb = new StringBuilder(len+16);
    char c;
    String hex;
    int hl;
    for (int i=0;i<len;++i){
      c = s.charAt(i);
      switch (c){
        case '\\': case '/': case '"': {
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
          if (c>31 && c<127){
            sb.append(c);
          }else{
            //JDK17: hex = HexFormat.of().toHexDigits(c);
            hex = Integer.toHexString((int)c);
            hl = hex.length();
            if (hl<=4){
              sb.append("\\u");
              for (;hl<4;hl++){
                sb.append('0');
              }
              sb.append(hex);
            }
          }
        }
      }
    }
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
   * @return a {@code String} containing the stack trace of the given {@code Throwable}.
   */
  public static String getStackTrace(Throwable t){
    StringWriter sw = new StringWriter(128);
    t.printStackTrace(new PrintWriter(sw));
    return sw.toString();
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