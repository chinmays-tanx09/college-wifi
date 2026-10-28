package bot.paradox.wifi;
import java.util.*;import java.util.regex.*;
/**
 * v5: one small driver per portal family, all over Http (bound to the Wi-Fi network) and all pure Java.
 * A driver signs in with ONE account and reports what the portal said; the session decides about failover.
 * Every driver posts only to hosts the user confirmed for this network (Http.allowedHosts), and anything that is not a
 * native protocol is verified by re-probing generate_204 on the same network: 204 is the only proof of success.
 */
final class Drivers {
 static String PROBE="http://connectivitycheck.gstatic.com/generate_204";
 /** Everything a driver needs about one saved network. The recipe is learned at setup and refined on each success. */
 static final class Net { String ssid="",type=Portal.GENERIC,loginUrl="",base="";Set<String> hosts=new LinkedHashSet<>();
  Map<String,String> recipe=new LinkedHashMap<>(); // userField, passField, formAction, method, keepalive, logoutId, zone …
  String r(String k){String v=recipe.get(k);return v==null?"":v;} }
 interface Driver {
  Portal.Result login(Http h,Net n,String user,String pass)throws Exception;
  /** true = portal confirmed signed out, false = it said no, null = no logout endpoint known (session falls back to check). */
  Boolean logout(Http h,Net n,String user)throws Exception;
  Portal.Live check(Http h,Net n,String user)throws Exception;
 }
 static Driver of(String type){if(type==null)type="";switch(type){
  case Portal.SOPHOS:return new Sophos();case Portal.FORTINET:return new Fortinet();case Portal.PFSENSE:return new PfSense();case Portal.OPNSENSE:return new OpnSense();
  case Portal.MIKROTIK:return new MikroTik();case Portal.ARUBA:return new Aruba();case Portal.CISCO_WLC:return new CiscoWlc();default:return new Generic();}}

 /** 204 on the Wi-Fi network = internet works; anything else = still behind the portal. */
 static Portal.Live probe(Http h){try{Http.Resp r=h.get(PROBE);if(r.code==204||(r.code==200&&r.body.trim().isEmpty()&&r.header("Content-Length")!=null&&"0".equals(r.header("Content-Length").trim())))return Portal.Live.IN;return Portal.Live.OUT;}catch(Exception e){return Portal.Live.UNKNOWN;}}
 static Portal.Result verified(Http h,Portal.Result ifIn,Portal.Result ifOut){return probe(h)==Portal.Live.IN?ifIn:ifOut;}
 static Portal.Result ok(String m){return new Portal.Result(Portal.Kind.OK,m);}
 static Portal.Result refused(String m){return new Portal.Result(Portal.refusal(m),m);}
 static Portal.Result unreadable(String m){return new Portal.Result(Portal.Kind.UNREADABLE,m);}
 /** The visible error/notice text of a portal page, best effort. */
 static String notice(String html){Matcher m=Pattern.compile("(?is)<(div|span|p|font|td|h\\d|li|label|strong|b)\\b[^>]*(?:class|id)\\s*=\\s*[\"'][^\"']*(error|err|alert|msg|message|warning|notice|fail|status)[^\"']*[\"'][^>]*>(.*?)</\\1>").matcher(html==null?"":html);
  while(m.find()){String t=Html.text(m.group(3));if(t.length()>2)return t;}String t=Html.text(html);return t.length()>200?t.substring(0,200):t;}
 static String latestUrl(Http.Resp r){return r.redirect()?Http.resolve(r.url,r.location):r.url;}

 // ---------- Sophos / Cyberoam: login.xml (mode 191), live (192), logout (193) ----------
 static final class Sophos implements Driver {
  static String base(Net n){return n.base.isEmpty()?Http.origin(n.loginUrl):n.base;}
  public Portal.Result login(Http h,Net n,String u,String p)throws Exception{String b=base(n);Map<String,String> f=new LinkedHashMap<>();f.put("mode","191");f.put("username",u);f.put("password",p);f.put("a",String.valueOf(System.currentTimeMillis()));f.put("producttype","0");
   Http.Resp r=h.post(b+"/login.xml",f,b+"/httpclient.html");SophosProtocol.Reply x=SophosProtocol.fill(SophosProtocol.parse(r.body,r.code),u);
   if(x==null)return pageVerdict(h,r);if(x.success())return ok(x.message);if(x.message.isEmpty())return unreadable("status "+x.status);return refused(x.message);}
  /** v5.1: login.xml answered with an HTML page or a redirect instead of XML (seen when a quota runs out). A clear limit/expiry
   *  sentence on that page is a refusal of THIS account (so failover moves on); anything else stays UNREADABLE. */
  static Portal.Result pageVerdict(Http h,Http.Resp r){String body=r.body==null?"":r.body,where=r.location==null?"":r.location;
   if(r.redirect()){try{Http.Resp f=h.follow(r,2);body=f.body==null?"":f.body;}catch(Exception ignored){}}
   String text=Html.text(body);if(text.length()>2000)text=text.substring(0,2000);String said=notice(body);
   for(String t:new String[]{said,text,where.replace('_',' ').replace('-',' ')}){String m=t.toLowerCase(java.util.Locale.ROOT);Portal.Kind k=null; // limits first: a block page that also says "validity" must not park the account
    if(Portal.any(m,Portal.QUOTA))k=Portal.Kind.LIMIT_QUOTA;else if(Portal.any(m,Portal.SESSION))k=Portal.Kind.LIMIT_SESSION;else if(m.contains("account has expired")||m.contains("account expired"))k=Portal.Kind.EXPIRED;
    if(k!=null)return new Portal.Result(k,Portal.shortMsg(t.equals(where.replace('_',' ').replace('-',' '))?"Portal redirected to "+where:t));}
   return unreadable("HTTP "+r.code);}
  public Boolean logout(Http h,Net n,String u)throws Exception{String b=base(n);for(String path:new String[]{"/logout.xml","/login.xml"}){try{Map<String,String> f=new LinkedHashMap<>();f.put("mode","193");f.put("username",u);f.put("a",String.valueOf(System.currentTimeMillis()));f.put("producttype","0");
    Http.Resp r=h.post(b+path,f,b+"/httpclient.html");SophosProtocol.Reply x=SophosProtocol.parse(r.body,r.code);if(x!=null&&x.signedOut())return true;}catch(Exception ignored){}}return null;}
  public Portal.Live check(Http h,Net n,String u)throws Exception{String b=base(n);try{Http.Resp r=h.get(b+"/live?mode=192&username="+Http.enc(u)+"&a="+System.currentTimeMillis()+"&producttype=0");SophosProtocol.Live l=SophosProtocol.parseLive(r.body);
    if(l==SophosProtocol.Live.IN)return Portal.Live.IN;if(l==SophosProtocol.Live.OUT)return Portal.Live.OUT;}catch(Exception ignored){}return probe(h);}
 }

 // ---------- generic HTML form (also the engine under pfSense, Aruba, Cisco WLC, Meraki, Ruckus, Nomadix) ----------
 static final Pattern USERISH=Pattern.compile("(?i)user|login|uname|email|e-mail|uid|userid|account|roll|enroll|regno|reg_no|student|mobile|phone|msisdn|name|id$|^id|auth_user");
 static final Pattern AGREE=Pattern.compile("(?i)accept|agree|terms|tos|policy|aup|consent|eula|conditions");
 /** Pick the login form (has a password field; most user-ish inputs) and the user field inside it. */
 static Html.Form loginForm(List<Html.Form> forms,Net n){Html.Form best=null;int bs=-1;for(Html.Form f:forms){if(!f.hasPassword())continue;int s=1;
   if(!n.r("passField").isEmpty()&&f.named(n.r("passField"))!=null)s+=10;for(Html.Input i:f.inputs)if(i.name!=null&&USERISH.matcher(i.name).find())s+=2;if(s>bs){bs=s;best=f;}}return best;}
 static Html.Input userField(Html.Form f,Net n){if(!n.r("userField").isEmpty()){Html.Input i=f.named(n.r("userField"));if(i!=null)return i;}
  Html.Input first=null;Html.Input pass=f.password();
  for(Html.Input i:f.inputs){if(i==pass)break;if(i.name==null)continue;boolean texty=i.is("text")||i.is("email")||i.is("tel")||i.is("number")||i.is("username");if(!texty)continue;
   if(USERISH.matcher(i.name).find()||USERISH.matcher(i.id).find()||USERISH.matcher(i.placeholder).find())return i;if(first==null)first=i;}
  if(first!=null)return first;for(Html.Input i:f.inputs)if(i.name!=null&&(i.is("text")||i.is("email")))return i;return null;}
 /** The form's fields as a browser would submit them, with the login filled in and terms ticked. */
 static Map<String,String> fill(Html.Form f,Html.Input user,String u,String p){Map<String,String> m=new LinkedHashMap<>();Html.Input pass=f.password();boolean submitDone=false;
  for(Html.Input i:f.inputs){if(i.name==null||i.name.isEmpty())continue;
   if(i==user){m.put(i.name,u);continue;}if(i==pass){m.put(i.name,p);continue;}
   if(i.is("password")){m.put(i.name,"");continue;}
   if(i.is("checkbox")){if(i.checked||AGREE.matcher(i.name+" "+i.id).find())m.put(i.name,i.value.isEmpty()?"on":i.value);continue;}
   if(i.is("radio")){if(i.checked&&!m.containsKey(i.name))m.put(i.name,i.value);continue;}
   if(i.is("submit")||i.is("image")||(i.tag.equals("button")&&i.is("submit"))){if(!submitDone){m.put(i.name,i.value);submitDone=true;}continue;}
   if(i.is("button")||i.is("reset")||i.is("file"))continue;
   if(!m.containsKey(i.name))m.put(i.name,i.value);}
  return m;}
 static Http.Resp submit(Http h,Html.Form f,Map<String,String> fields,String referer)throws Exception{
  if("GET".equals(f.method)){String q=Http.encode(fields);String a=f.action;return h.getSecret(a+(a.contains("?")?"&":"?")+q);}return h.post(f.action,fields,referer);}
 static Http.Resp page(Http h,Net n)throws Exception{Http.Resp r=h.get(n.loginUrl.isEmpty()?PROBE:n.loginUrl);int hops=5;
  while(hops-->0){if(r.redirect()){r=h.get(Http.resolve(r.url,r.location));continue;}if(r.code==200&&Html.forms(r.body,r.url).isEmpty()){String m=Html.metaRefresh(r.body);if(m==null)m=Html.jsRedirect(r.body);if(m!=null){r=h.get(Http.resolve(r.url,m));continue;}}break;}return r;}
 static class Generic implements Driver {
  /** Hook for drivers that need to adjust the filled fields (Cisco WLC's JS-set buttonClicked). */
  void adjust(Map<String,String> fields,Html.Form f){}
  public Portal.Result login(Http h,Net n,String u,String p)throws Exception{
   if(probe(h)==Portal.Live.IN)return ok("Already online");
   Http.Resp pg=page(h,n);List<Html.Form> forms=Html.forms(pg.body,pg.url);Html.Form f=loginForm(forms,n);
   if(f==null)return unreadable("No login form in the page (it may be built by JavaScript)");
   Html.Input ui=userField(f,n);Map<String,String> fields=fill(f,ui,u,p);adjust(fields,f);
   n.recipe.put("passField",f.password().name==null?"":f.password().name);if(ui!=null&&ui.name!=null)n.recipe.put("userField",ui.name);n.recipe.put("formAction",f.action);n.recipe.put("method",f.method);
   Http.Resp r=submit(h,f,fields,pg.url);String lastHtml=r.body;int hops=4;
   while(hops-->0){if(r.redirect()){r=h.get(Http.resolve(r.url,r.location));lastHtml=r.body;continue;}String m=Html.metaRefresh(r.body);if(m!=null&&!m.isEmpty()&&r.code==200&&Html.forms(r.body,r.url).isEmpty()){try{r=h.get(Http.resolve(r.url,m));lastHtml=r.body;}catch(Exception e){break;}continue;}break;}
   onReply(n,r);
   if(probe(h)==Portal.Live.IN)return ok(Portal.looksSignedIn(Html.text(lastHtml))?Portal.shortMsg(notice(lastHtml)):"Signed in");
   String t=notice(lastHtml);Portal.Kind k=Portal.refusal(Html.text(lastHtml));
   if(k==Portal.Kind.REFUSED&&!Html.forms(lastHtml,r.url).isEmpty()&&loginForm(Html.forms(lastHtml,r.url),n)!=null)return new Portal.Result(Portal.Kind.REFUSED,t.isEmpty()?"The portal showed its login form again":t);
   return new Portal.Result(k,t.isEmpty()?"Portal did not let the internet through":t);}
  void onReply(Net n,Http.Resp r){}
  public Boolean logout(Http h,Net n,String u)throws Exception{String lo=n.r("logoutUrl");if(lo.isEmpty())return null;Http.Resp r=h.get(lo);h.follow(r,3);return probe(h)==Portal.Live.OUT;}
  public Portal.Live check(Http h,Net n,String u)throws Exception{return probe(h);}
 }

 // ---------- FortiGate: fgtauth?<magic> form → POST "/" (4Tredir, magic, username, password); keepalive?/logout? ----------
 static final class Fortinet extends Generic {
  @Override public Portal.Result login(Http h,Net n,String u,String p)throws Exception{
   Http.Resp r0=h.get(PROBE);if(r0.code==204)return ok("Already online");
   String auth=null;if(r0.redirect())auth=Http.resolve(r0.url,r0.location);else{String j=Html.jsRedirect(r0.body);if(j==null)j=Html.metaRefresh(r0.body);if(j!=null)auth=Http.resolve(r0.url,j);}
   if(auth==null||!auth.contains("fgtauth"))auth=n.loginUrl; // magic is minted by the FortiGate per redirect; the saved URL is a last resort
   Http.Resp pg=h.get(auth);if(pg.redirect())pg=h.follow(pg,3);
   List<Html.Form> forms=Html.forms(pg.body,pg.url);Html.Form f=loginForm(forms,n);
   if(f==null){for(Html.Form x:forms)if(x.named("answer")!=null){Map<String,String> a=fill(x,null,"","");a.put("answer","1");pg=h.follow(submit(h,x,a,pg.url),3);f=loginForm(Html.forms(pg.body,pg.url),n);break;}} // disclaimer page first
   if(f==null)return unreadable("FortiGate page had no login form");
   Map<String,String> fields=fill(f,userField(f,n),u,p);if(f.named("magic")==null){String mg=auth.contains("?")?auth.substring(auth.indexOf('?')+1):"";if(!mg.isEmpty())fields.put("magic",mg);}
   Http.Resp r=submit(h,f,fields,pg.url);String where=latestUrl(r),body=r.body;
   if(r.redirect()&&where.contains("keepalive")){n.recipe.put("keepalive",where);n.recipe.put("logoutUrl",where.replace("keepalive","logout"));}
   else{Matcher m=Pattern.compile("(https?://[^\"'\\s]+/keepalive\\?[A-Za-z0-9]+)").matcher(body);if(m.find()){n.recipe.put("keepalive",m.group(1));n.recipe.put("logoutUrl",m.group(1).replace("keepalive","logout"));}}
   if(!n.r("keepalive").isEmpty()&&(where.contains("keepalive")||body.contains("keepalive?")))return verified(h,ok("Signed in (FortiGate)"),ok("Signed in (FortiGate keepalive page)"));
   if(probe(h)==Portal.Live.IN)return ok("Signed in");
   if(r.redirect()){try{body=h.follow(r,3).body;}catch(Exception ignored){}}
   return refused(notice(body).isEmpty()?"Firewall authentication failed":notice(body));}
  @Override public Portal.Live check(Http h,Net n,String u)throws Exception{return probe(h);}
 }

 // ---------- pfSense: auth_user / auth_pass / zone / redirurl / accept; logout_id ----------
 static final class PfSense extends Generic {
  @Override void onReply(Net n,Http.Resp r){Matcher m=Pattern.compile("(?i)name=[\"']?logout_id[\"']?[^>]*value=[\"']?([A-Za-z0-9]+)").matcher(r.body==null?"":r.body);if(m.find())n.recipe.put("logoutId",m.group(1));
   Matcher z=Pattern.compile("(?i)name=[\"']?zone[\"']?[^>]*value=[\"']?([A-Za-z0-9_-]+)").matcher(r.body==null?"":r.body);if(z.find())n.recipe.put("zone",z.group(1));}
  @Override public Boolean logout(Http h,Net n,String u)throws Exception{String id=n.r("logoutId");if(id.isEmpty())return null;Map<String,String> f=new LinkedHashMap<>();f.put("logout_id",id);if(!n.r("zone").isEmpty())f.put("zone",n.r("zone"));f.put("logout","Logout");
   String to=n.r("formAction").isEmpty()?n.loginUrl:n.r("formAction");h.follow(h.post(to,f,n.loginUrl),3);return probe(h)==Portal.Live.OUT;}
 }

 // ---------- OPNsense: JSON API /api/captiveportal/access/{logon,status,logoff}/<zoneid>/ (user, password) ----------
 static final class OpnSense implements Driver {
  static String zone(Http h,Net n)throws Exception{if(!n.r("zoneid").isEmpty())return n.r("zoneid");String z=null;
   try{Http.Resp pg=page(h,n);z=findZone(pg.body);if(z==null){Matcher s=Pattern.compile("(?i)<script[^>]+src=[\"']([^\"']*zone[^\"']*\\.js)").matcher(pg.body);if(s.find())z=findZone(h.get(Http.resolve(pg.url,s.group(1))).body);}}catch(Exception ignored){}
   if(z==null){Matcher q=Pattern.compile("[?&]zone(?:id)?=([0-9]+)").matcher(n.loginUrl);if(q.find())z=q.group(1);}if(z==null)z="0";n.recipe.put("zoneid",z);return z;}
  static String findZone(String s){Matcher m=Pattern.compile("zoneid\\s*=\\s*[\"']?([0-9]+)").matcher(s==null?"":s);return m.find()?m.group(1):null;}
  static String api(Net n,String what,String z){return Http.origin(n.loginUrl)+"/api/captiveportal/access/"+what+"/"+z+"/";}
  static String state(String json){Matcher m=Pattern.compile("\"clientState\"\\s*:\\s*\"([A-Z_]+)\"").matcher(json==null?"":json);return m.find()?m.group(1):"";}
  public Portal.Result login(Http h,Net n,String u,String p)throws Exception{String z=zone(h,n);Map<String,String> f=new LinkedHashMap<>();f.put("user",u);f.put("password",p);
   Http.Resp r=h.post(api(n,"logon",z),f,n.loginUrl);String s=state(r.body);if("AUTHORIZED".equals(s))return ok("Signed in (OPNsense)");if(s.isEmpty())return unreadable("OPNsense API answered HTTP "+r.code);
   String msg=r.body.contains("\"message\"")?r.body.replaceAll("(?s).*\"message\"\\s*:\\s*\"([^\"]*)\".*","$1"):"authentication failed";return refused(msg);}
  public Boolean logout(Http h,Net n,String u)throws Exception{Map<String,String> f=new LinkedHashMap<>();f.put("user","");f.put("password","");Http.Resp r=h.post(api(n,"logoff",zone(h,n)),f,n.loginUrl);String s=state(r.body);return s.isEmpty()?null:!"AUTHORIZED".equals(s);}
  public Portal.Live check(Http h,Net n,String u)throws Exception{try{Map<String,String> f=new LinkedHashMap<>();f.put("user","");f.put("password","");String s=state(h.post(api(n,"status",zone(h,n)),f,n.loginUrl).body);if("AUTHORIZED".equals(s))return Portal.Live.IN;if(!s.isEmpty())return Portal.Live.OUT;}catch(Exception ignored){}return probe(h);}
 }

 // ---------- MikroTik hotspot: /login (username, password, dst, popup); HTTP-CHAP = md5(chap-id + password + chap-challenge) ----------
 static final class MikroTik extends Generic {
  static final Pattern CHAP=Pattern.compile("hexMD5\\(\\s*'((?:\\\\[0-7]{1,3}|[^'])*)'\\s*\\+\\s*document\\.login\\.password\\.value\\s*\\+\\s*'((?:\\\\[0-7]{1,3}|[^'])*)'\\s*\\)");
  /** Decode the template's JS string literal ('\\341\\032…' octal escapes) to raw bytes. */
  static byte[] jsBytes(String s){java.io.ByteArrayOutputStream o=new java.io.ByteArrayOutputStream();for(int i=0;i<s.length();i++){char c=s.charAt(i);
    if(c=='\\'&&i+1<s.length()){int j=i+1,v=0,k=0;while(j<s.length()&&k<3&&s.charAt(j)>='0'&&s.charAt(j)<='7'){v=v*8+(s.charAt(j)-'0');j++;k++;}if(k>0){o.write(v&0xff);i=j-1;continue;}char d=s.charAt(i+1);o.write(d=='n'?'\n':d=='t'?'\t':d);i++;continue;}o.write(c&0xff);}return o.toByteArray();}
  static String md5(byte[]... parts)throws Exception{java.security.MessageDigest md=java.security.MessageDigest.getInstance("MD5");for(byte[] p:parts)md.update(p);StringBuilder b=new StringBuilder();for(byte x:md.digest())b.append(String.format("%02x",x&0xff));return b.toString();}
  @Override public Portal.Result login(Http h,Net n,String u,String p)throws Exception{
   if(probe(h)==Portal.Live.IN)return ok("Already online");
   Http.Resp pg=page(h,n);List<Html.Form> forms=Html.forms(pg.body,pg.url);Html.Form login=null,sendin=null;
   for(Html.Form f:forms){if("sendin".equalsIgnoreCase(f.name))sendin=f;else if(f.hasPassword()&&login==null)login=f;}
   if(login==null)return unreadable("No hotspot login form");
   Matcher c=CHAP.matcher(pg.body);Map<String,String> fields;Html.Form target;
   if(c.find()){String hash=md5(jsBytes(c.group(1)),p.getBytes("ISO-8859-1"),jsBytes(c.group(2)));target=sendin!=null?sendin:login;fields=new LinkedHashMap<>();
    Html.Form src=target;for(Html.Input i:src.inputs)if(i.name!=null&&!i.name.isEmpty())fields.put(i.name,i.value);fields.put("username",u);fields.put("password",hash);
    Html.Input dst=login.named("dst");if(dst!=null&&!fields.containsKey("dst"))fields.put("dst",dst.value);if(!fields.containsKey("popup"))fields.put("popup","true");n.recipe.put("chap","1");}
   else{target=login;fields=fill(login,userField(login,n),u,p);n.recipe.put("chap","0");}
   Http.Resp r=submit(h,target,fields,pg.url);String body=r.body,where=latestUrl(r);
   if(r.redirect()){try{Http.Resp f2=h.follow(r,3);body=f2.body;where=f2.url;}catch(Exception ignored){}}
   String base=Http.origin(pg.url);n.recipe.put("logoutUrl",base+"/logout");
   if(probe(h)==Portal.Live.IN)return ok("Signed in (MikroTik)");
   String t=Html.text(body).toLowerCase(Locale.ROOT);if(where.contains("/status")||t.contains("you are logged in"))return ok("Signed in (MikroTik)");
   String err=notice(body);return refused(err.isEmpty()?"Hotspot login refused":err);}
 }

 // ---------- Aruba: /cgi-bin/login  user, password, cmd=authenticate ----------
 static final class Aruba extends Generic {
  @Override public Portal.Result login(Http h,Net n,String u,String p)throws Exception{
   if(probe(h)==Portal.Live.IN)return ok("Already online");
   Http.Resp pg=null;try{pg=page(h,n);}catch(Exception ignored){}
   if(pg!=null){Html.Form f=loginForm(Html.forms(pg.body,pg.url),n);if(f!=null&&(f.named("cmd")!=null||f.action.contains("/cgi-bin/login")))return super.login(h,n,u,p);}
   String to=Http.origin(n.loginUrl.isEmpty()?pg.url:n.loginUrl)+"/cgi-bin/login";Map<String,String> f=new LinkedHashMap<>();f.put("user",u);f.put("password",p);f.put("cmd","authenticate");f.put("Login","Log In");
   Http.Resp r=h.post(to,f,n.loginUrl);String body=r.redirect()?"":r.body;String where=latestUrl(r);
   if(probe(h)==Portal.Live.IN)return ok("Signed in (Aruba)");
   if(where.contains("errmsg=")){String e=java.net.URLDecoder.decode(where.substring(where.indexOf("errmsg=")+7).split("&")[0],"UTF-8");return refused(e);}
   return refused(notice(body).isEmpty()?"Authentication failed":notice(body));}
 }

 // ---------- Cisco WLC web-auth: login.html; JS sets buttonClicked=4; err_flag=1 marks a refusal ----------
 static final class CiscoWlc extends Generic {
  @Override void adjust(Map<String,String> f,Html.Form form){if(form.named("buttonClicked")!=null)f.put("buttonClicked","4");if(form.named("err_flag")!=null)f.put("err_flag","0");}
 }
}
