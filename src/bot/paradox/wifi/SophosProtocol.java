package bot.paradox.wifi;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.*;
/** Plain Java implementation of the Sophos/Cyberoam captive-portal wire format (login.xml 191, live 192, logout 193). No logging. */
final class SophosProtocol {
 static final String BASE="http://172.16.16.16:8090";
 static final String ENDPOINT=BASE+"/login.xml";
 interface Opener {URLConnection open(URL url) throws IOException;}
 /** Portal answered, but not with something we can read (HTTP error, redirect, HTML). The portal IS reachable. */
 static final class HttpFailure extends IOException {final int code;final String body;HttpFailure(int code){this(code,"");}HttpFailure(int code,String body){super("Portal HTTP "+code);this.code=code;this.body=body==null?"":body;}}
 static final class Unparseable extends IOException {final int code;final String body;Unparseable(int code,String body){super("Portal reply not recognised (HTTP "+code+")");this.code=code;this.body=body==null?"":body;}}
 static final class Reply {
  final String status,message;final int httpCode;
  Reply(String s,String m){this(s,m,0);}
  Reply(String s,String m,int h){status=s==null?"":s.trim();message=m==null?"":m.replace("&#39;","'").replace("&quot;","\"").replace("&lt;","<").replace("&gt;",">").replace("&amp;","&").trim();httpCode=h;}
  boolean success(){String m=message.toLowerCase(Locale.ROOT);return "LIVE".equalsIgnoreCase(status)||(!m.contains("not signed")&&!m.contains("not logged")&&!m.contains("failed")&&!m.contains("signed out")&&!m.contains("logged out")&&m.matches("(?s).*(?:signed\\s+in|logged\\s+in|successfully\\s+authenticated).*"));}
  boolean rejected(){String c=code();return "REJECTED".equals(c)||"LOGIN_LIMIT".equals(c)||"DATA_LIMIT".equals(c);}
  boolean signedOut(){String m=message.toLowerCase(Locale.ROOT);if("LOGOUT".equalsIgnoreCase(status))return true;if(m.matches("(?s).*(?:signed|logged)\\s*(?:out|off).*"))return true;return "LOGIN".equalsIgnoreCase(status)&&m.isEmpty();}
  String code(){if(success())return "PORTAL_CONFIRMED";String m=message.toLowerCase(Locale.ROOT);
   if(m.contains("maximum login")||m.contains("login limit"))return "LOGIN_LIMIT";
   if(m.contains("data transfer")||m.contains("quota")||m.contains("bandwidth limit"))return "DATA_LIMIT";
   if(m.contains("invalid")||m.contains("incorrect")||m.contains("wrong")||m.contains("denied")||m.contains("failed"))return "REJECTED";
   return "PORTAL_REJECTED";}
 }
 enum Live {IN,OUT,UNKNOWN}
 static Reply post(Opener opener,String mode,String user,String pass) throws Exception {return post(opener,"/login.xml",mode,user,pass,5000,8000);}
 /** POST to a fixed path on the fixed LAN host. pass==null omits the password field (logout). */
 static Reply post(Opener opener,String path,String mode,String user,String pass,int connectMs,int readMs) throws Exception {
  HttpURLConnection c=(HttpURLConnection)opener.open(new URL(BASE+path));try{
   c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(connectMs);c.setReadTimeout(readMs);c.setInstanceFollowRedirects(false);c.setUseCaches(false);
   c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");c.setRequestProperty("Referer",BASE+"/httpclient.html");
   String data="mode="+mode+"&username="+enc(user)+(pass==null?"":"&password="+enc(pass))+"&a="+System.currentTimeMillis()+"&producttype=0";
   byte[] payload=data.getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(payload.length);try(OutputStream o=c.getOutputStream()){o.write(payload);}
   int http=c.getResponseCode();String body=read(c,http);
   if(pass!=null&&!pass.isEmpty())body=body.replace(pass,"[redacted]");
   Reply r=parse(body,http);if(r==null){if(http>=300)throw new HttpFailure(http,body);throw new Unparseable(http,body);}return fill(r,user);
  }finally{c.disconnect();}
 }
 /** Sophos messages are templates the portal page normally fills in JS ("You are signed in as {username}"): fill the user, drop any other placeholder. */
 static Reply fill(Reply r,String user){if(r==null||r.message.indexOf('{')<0)return r;String m=r.message.replaceAll("(?i)\\{\\s*user(?:name)?\\s*\\}",java.util.regex.Matcher.quoteReplacement(user==null?"":user)).replaceAll("\\{[A-Za-z_]+\\}","").replaceAll("\\s{2,}"," ").trim();return new Reply(r.status,m,r.httpCode);}
 /** Sophos keep-alive: GET /live?mode=192&username=…&a=…&producttype=0. Returns the raw reply text (never contains the password). */
 static String live(Opener opener,String user,int connectMs,int readMs) throws Exception {
  HttpURLConnection c=(HttpURLConnection)opener.open(new URL(BASE+"/live?mode=192&username="+enc(user)+"&a="+System.currentTimeMillis()+"&producttype=0"));try{
   c.setConnectTimeout(connectMs);c.setReadTimeout(readMs);c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setRequestProperty("Referer",BASE+"/httpclient.html");
   int http=c.getResponseCode();String body=read(c,http);return http+"\n"+body;
  }finally{c.disconnect();}
 }
 static Live parseLive(String raw){if(raw==null)return Live.UNKNOWN;String r=raw.toLowerCase(Locale.ROOT);
  if(r.contains("login_again")||r.matches("(?s).*<loginstatus>\\s*(?:<!\\[cdata\\[)?\\s*false.*")||r.contains("relogon"))return Live.OUT;
  if(r.matches("(?s).*<ack>\\s*(?:<!\\[cdata\\[)?\\s*ack\\b.*")||r.contains("<liverequesttime>"))return Live.IN;
  return Live.UNKNOWN;}
 static String read(HttpURLConnection c,int http) throws IOException {InputStream stream=http>=400?c.getErrorStream():c.getInputStream();if(stream==null)return "";
  try(InputStream in=stream){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[2048];int len;while((len=in.read(buf))!=-1){if(out.size()+len>65536)break;out.write(buf,0,len);}return new String(out.toByteArray(),StandardCharsets.UTF_8);}}
 static final Pattern TAG=Pattern.compile("<(status|message)>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?\\s*</\\1>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
 /** DOM first (XXE-safe), then a tolerant regex for slightly malformed portal XML. Null if neither finds a status/message. */
 static Reply parse(String body,int http){if(body==null||body.trim().isEmpty())return null;
  try{DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);try{f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);}catch(Exception ignored){}f.setXIncludeAware(false);f.setExpandEntityReferences(false);
   Document d=f.newDocumentBuilder().parse(new ByteArrayInputStream(body.trim().getBytes(StandardCharsets.UTF_8)));String s=tag(d,"status"),m=tag(d,"message");if(!s.isEmpty()||!m.isEmpty())return new Reply(s,m,http);}catch(Exception ignored){}
  String s="",m="";Matcher x=TAG.matcher(body);while(x.find()){String v=x.group(2).replace("&#39;","'").replace("&amp;","&").trim();if(x.group(1).equalsIgnoreCase("status")&&s.isEmpty())s=v;else if(x.group(1).equalsIgnoreCase("message")&&m.isEmpty())m=v;}
  return s.isEmpty()&&m.isEmpty()?null:new Reply(s,m,http);}
 static String enc(String s)throws Exception{return URLEncoder.encode(s==null?"":s,"UTF-8");}
 static String tag(Document d,String name){NodeList nodes=d.getElementsByTagName(name);return nodes.getLength()>0?nodes.item(0).getTextContent().trim():"";}
}
