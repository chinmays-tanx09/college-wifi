package bot.paradox.wifi;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;
/**
 * v5: tiny HTTP client for portal work. Pure Java (JVM-tested). Every connection comes from the Opener, which on the
 * phone is Network.openConnection on the Wi-Fi network (never mobile data). Redirects are followed by hand so each hop
 * can be checked; a cookie jar keeps portal sessions; bodies are capped. Credentials are only ever written by callers
 * that have already checked the target host against the network's allowed portal hosts.
 */
final class Http {
 interface Opener { URLConnection open(URL u) throws IOException; }
 static final class Resp { int code;String url,location,body="";Map<String,List<String>> headers=new HashMap<>();
  String header(String k){for(Map.Entry<String,List<String>> e:headers.entrySet())if(e.getKey()!=null&&e.getKey().equalsIgnoreCase(k)&&!e.getValue().isEmpty())return e.getValue().get(0);return null;}
  boolean redirect(){return code>=300&&code<400&&location!=null;} }
 static final class Blocked extends IOException { Blocked(String host){super("refusing to send to "+host+" (not this network's portal)");} }
 final Opener opener;final Map<String,String> cookies=new LinkedHashMap<>();int connectMs=3000,readMs=6000;String userAgent="Mozilla/5.0 (Linux; Android 14) ParadoxWiFi/5";
 /** When non-null, POSTs (anything carrying credentials) may only go to these hosts. */
 Set<String> allowedHosts;String secret;
 Http(Opener o){opener=o;}
 Http timeouts(int c,int r){connectMs=c;readMs=r;return this;}
 Resp get(String url)throws IOException{return send("GET",url,null,null,null);}
 /** A GET whose query carries the login (GET-method forms): held to the same host rule as a POST. */
 Resp getSecret(String url)throws IOException{if(allowedHosts!=null&&!allowedHosts.contains(host(url)))throw new Blocked(host(url));return send("GET",url,null,null,null);}
 Resp post(String url,Map<String,String> form,String referer)throws IOException{return send("POST",url,encode(form),"application/x-www-form-urlencoded; charset=UTF-8",referer);}
 Resp postBody(String url,String body,String type,String referer)throws IOException{return send("POST",url,body,type,referer);}
 /** GET/POST and follow up to n redirects (POST becomes GET after 301/302/303, as browsers do). */
 Resp follow(Resp r,int n)throws IOException{while(r.redirect()&&n-->0)r=get(resolve(r.url,r.location));return r;}
 static String encode(Map<String,String> f){StringBuilder b=new StringBuilder();for(Map.Entry<String,String> e:f.entrySet()){if(b.length()>0)b.append('&');b.append(enc(e.getKey())).append('=').append(enc(e.getValue()==null?"":e.getValue()));}return b.toString();}
 static String enc(String s){try{return URLEncoder.encode(s,"UTF-8");}catch(UnsupportedEncodingException e){throw new RuntimeException(e);}}
 static String host(String url){try{String h=new URI(url).getHost();return h==null?"":h.toLowerCase(Locale.ROOT);}catch(Exception e){return "";}}
 static String origin(String url){try{URI u=new URI(url);return u.getScheme()+"://"+u.getHost()+(u.getPort()>0?":"+u.getPort():"");}catch(Exception e){return url;}}
 static String resolve(String base,String rel){if(rel==null)return base;rel=rel.trim().replace("&amp;","&");try{return new URI(base).resolve(new URI(rel.replace(" ","%20"))).toString();}catch(Exception e){try{return new URL(new URL(base),rel).toString();}catch(Exception e2){return rel;}}}
 Resp send(String method,String url,String body,String type,String referer)throws IOException{
  if(body!=null&&allowedHosts!=null&&!allowedHosts.contains(host(url)))throw new Blocked(host(url));
  HttpURLConnection c=(HttpURLConnection)opener.open(new URL(url));try{
   c.setRequestMethod(method);c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setConnectTimeout(connectMs);c.setReadTimeout(readMs);
   c.setRequestProperty("User-Agent",userAgent);c.setRequestProperty("Accept","text/html,application/xhtml+xml,application/xml,application/json;q=0.9,*/*;q=0.8");
   String ck=cookieHeader(host(url));if(!ck.isEmpty())c.setRequestProperty("Cookie",ck);if(referer!=null)c.setRequestProperty("Referer",referer);
   if(body!=null){byte[] b=body.getBytes(StandardCharsets.UTF_8);c.setDoOutput(true);c.setRequestProperty("Content-Type",type);c.setFixedLengthStreamingMode(b.length);try(OutputStream o=c.getOutputStream()){o.write(b);}}
   Resp r=new Resp();r.url=url;r.code=c.getResponseCode();Map<String,List<String>> h=c.getHeaderFields();if(h!=null)r.headers=h;r.location=r.header("Location");
   List<String> sc=null;for(Map.Entry<String,List<String>> e:r.headers.entrySet())if("Set-Cookie".equalsIgnoreCase(e.getKey()))sc=e.getValue();if(sc!=null)for(String s:sc)keepCookie(host(url),s);
   r.body=read(c,r.code);if(secret!=null&&!secret.isEmpty())r.body=r.body.replace(secret,"[redacted]");return r;
  }finally{c.disconnect();}}
 void keepCookie(String host,String setCookie){String kv=setCookie.split(";",2)[0];int i=kv.indexOf('=');if(i<=0)return;cookies.put(host+"\u0000"+kv.substring(0,i).trim(),kv.substring(i+1).trim());}
 String cookieHeader(String host){StringBuilder b=new StringBuilder();for(Map.Entry<String,String> e:cookies.entrySet()){String[] k=e.getKey().split("\u0000",2);if(!k[0].equals(host))continue;if(b.length()>0)b.append("; ");b.append(k[1]).append('=').append(e.getValue());}return b.toString();}
 static String read(HttpURLConnection c,int code){try{InputStream in=code>=400?c.getErrorStream():c.getInputStream();if(in==null)return "";try(InputStream s=in){ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n,total=0;while((n=s.read(buf))>0&&total<262144){o.write(buf,0,n);total+=n;}
    String cs="UTF-8";String ct=c.getContentType();if(ct!=null&&ct.toLowerCase(Locale.ROOT).contains("charset=")){cs=ct.substring(ct.toLowerCase(Locale.ROOT).indexOf("charset=")+8).replace("\"","").split(";")[0].trim();}
    try{return o.toString(cs);}catch(Exception e){return o.toString("UTF-8");}}}catch(IOException e){return "";}}
}
