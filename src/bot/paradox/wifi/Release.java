package bot.paradox.wifi;
import java.util.*;import java.util.regex.*;
/** v5 updater, pure part (JVM-tested): read a GitHub "latest release" or a simple manifest, and decide whether it is newer. */
final class Release {
 static final String UPDATE_URL="https://api.github.com/repos/chinmays-tanx09/paradox-wifi/releases/latest";
 int versionCode;String versionName="",apkUrl="",sha256="",changelog="",page="";boolean mandatory;
 boolean valid(){return !apkUrl.isEmpty()&&apkUrl.startsWith("https://")&&(versionCode>0||!versionName.isEmpty());}
 /** GitHub shape: tag_name "v5.1", body (changelog; may carry "versionCode: N", "sha256: …", "mandatory: true"), assets[].browser_download_url (first .apk).
  *  Simple shape: {versionCode, versionName, apkUrl, sha256, changelog, mandatory}. Null if neither. */
 static Release parse(String json){Object o;try{o=MiniJson.parse(json);}catch(Exception e){return null;}Map<String,Object> m=MiniJson.obj(o);Release r=new Release();
  if(m.containsKey("tag_name")){r.versionName=MiniJson.str(m,"tag_name").replaceFirst("^[vV]","");String body=MiniJson.str(m,"body");r.page=MiniJson.str(m,"html_url");
   Matcher c=Pattern.compile("(?i)versionCode\\s*[:=]\\s*(\\d+)").matcher(body);if(c.find())r.versionCode=Integer.parseInt(c.group(1));
   Matcher h=Pattern.compile("(?i)sha-?256\\s*[:=]\\s*([0-9a-f]{64})").matcher(body);if(h.find())r.sha256=h.group(1).toLowerCase(Locale.ROOT);
   r.mandatory=Pattern.compile("(?i)mandatory\\s*[:=]\\s*(true|yes)").matcher(body).find();
   r.changelog=body.replaceAll("(?im)^\\s*(versionCode|sha-?256|mandatory)\\s*[:=].*$","").trim();
   if(Boolean.TRUE.equals(m.get("draft"))||Boolean.TRUE.equals(m.get("prerelease")))return null;
   for(Object a:MiniJson.arr(m.get("assets"))){Map<String,Object> am=MiniJson.obj(a);String n=MiniJson.str(am,"name");if(n.toLowerCase(Locale.ROOT).endsWith(".apk")){r.apkUrl=MiniJson.str(am,"browser_download_url");
     if(r.versionCode==0){Matcher vc=Pattern.compile("(?i)(?:vc|code)[-_]?(\\d+)").matcher(n);if(vc.find())r.versionCode=Integer.parseInt(vc.group(1));}break;}}
   return r.valid()?r:null;}
  if(m.containsKey("apkUrl")){try{r.versionCode=(int)Double.parseDouble(MiniJson.str(m,"versionCode"));}catch(Exception ignored){}r.versionName=MiniJson.str(m,"versionName");r.apkUrl=MiniJson.str(m,"apkUrl");
   r.sha256=MiniJson.str(m,"sha256").toLowerCase(Locale.ROOT);r.changelog=MiniJson.str(m,"changelog");r.mandatory=Boolean.TRUE.equals(m.get("mandatory"));return r.valid()?r:null;}
  return null;}
 /** Newer than what is installed? versionCode decides when the release names one; otherwise the dotted version name. */
 boolean newerThan(int code,String name){if(versionCode>0)return versionCode>code;return compare(versionName,name)>0;}
 static int compare(String a,String b){String[] x=a.split("[^0-9]+"),y=b.split("[^0-9]+");for(int i=0;i<Math.max(x.length,y.length);i++){int p=i<x.length&&!x[i].isEmpty()?Integer.parseInt(x[i]):0,q=i<y.length&&!y[i].isEmpty()?Integer.parseInt(y[i]):0;if(p!=q)return p<q?-1:1;}return 0;}
 /** Install only an APK signed by exactly the certificate(s) this app is signed with. */
 static boolean sameSigner(Set<String> ours,Set<String> theirs){return ours!=null&&theirs!=null&&!ours.isEmpty()&&ours.equals(theirs);}
}
