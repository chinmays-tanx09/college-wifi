package bot.paradox.wifi;
import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;
/** v5 JVM tests: real Detect + Drivers + Session against the local portal mocks (mocks/mocks.py). Dummy credentials only. */
public class V5Test {
 static int fails=0,passes=0;static String MOCKS=System.getProperty("mocks","mocks/mocks.py");
 static void check(boolean ok,String what){System.out.println((ok?"PASS  ":"FAIL  ")+what);if(ok)passes++;else fails++;}
 static Process start(String kind,int port)throws Exception{ProcessBuilder pb=new ProcessBuilder("python3",MOCKS,"--kind",kind,"--port",""+port,"--users","u1:p1,u2:p2,u3:p3");pb.redirectErrorStream(true);pb.redirectOutput(new File("/tmp/mock_"+kind+".log"));Process p=pb.start();
  for(int i=0;i<50;i++){try(Socket s=new Socket("127.0.0.1",port)){return p;}catch(IOException e){Thread.sleep(100);}}throw new IOException("mock did not start");}
 static String admin(int port,String q)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+q).openConnection();c.setConnectTimeout(2000);c.setReadTimeout(4000);try(InputStream in=c.getResponseCode()>=400?c.getErrorStream():c.getInputStream()){return new String(readAll(in),"UTF-8");}}
 static byte[] readAll(InputStream in)throws IOException{ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))>0)o.write(b,0,n);return o.toByteArray();}
 /** Like Network.openConnection on the phone: the probe hosts are hijacked by the portal on this "Wi-Fi". */
 static Http http(int port){return new Http(u->{String s=u.toString();for(String h:new String[]{"http://connectivitycheck.gstatic.com","http://clients3.google.com","http://captive.apple.com"})s=s.replace(h,"http://127.0.0.1:"+port);return new URL(s).openConnection();}).timeouts(2000,4000);}
 static Drivers.Net net(Detect.Found f){Drivers.Net n=new Drivers.Net();n.type=f.type;n.loginUrl=f.loginUrl;n.base=Http.origin(f.loginUrl);n.hosts.addAll(f.hosts);return n;}
 static Http locked(int port,Drivers.Net n,String secret){Http h=http(port);h.allowedHosts=n.hosts;h.secret=secret;return h;}

 static final String[][] KINDS={{"sophos","sophos"},{"fortigate","fortinet"},{"pfsense","pfsense"},{"opnsense","opnsense"},{"mikrotik","mikrotik"},{"mikrotik_chap","mikrotik"},{"aruba","aruba"},{"ciscowlc","ciscowlc"},{"generic","generic"}};
 public static void main(String[] a)throws Exception{
  classifier();
  int port=19500;
  for(String[] k:KINDS){port++;Process p=start(k[0],port);try{portal(k[0],k[1],port);}catch(Throwable t){check(false,k[0]+": crashed "+t);t.printStackTrace(System.out);}finally{p.destroy();p.waitFor(3,TimeUnit.SECONDS);}}
  port++;Process p=start("generic_js",port);try{
   Detect.Found f=Detect.run(http(port),null);check(f.loginUrl!=null&&f.type.equals("generic"),"generic_js: JS redirect followed to the login page ("+f.summary()+")");
   Drivers.Net n=net(f);Portal.Result r=Drivers.of(n.type).login(locked(port,n,"p1"),n,"u1","p1");check(r.kind==Portal.Kind.UNREADABLE,"generic_js: no static form -> UNREADABLE so the hidden-browser path takes over ("+r+")");}finally{p.destroy();}
  port++;p=start("sophos",port);try{failover(port);}finally{p.destroy();}
  port++;p=start("pfsense",port);try{security(port);}finally{p.destroy();}
  System.out.println("\n"+passes+" passed, "+fails+" failed");System.out.println(fails==0?"ALL PASS":"FAILURES: "+fails);System.exit(fails==0?0:1);}

 static void classifier(){String[][] cases={
   {"You have reached the maximum login limit.","LIMIT_SESSION"},{"You have reached Maximum Login Limit.","LIMIT_SESSION"},{"Your data transfer has been exceeded, Please contact the administrator","LIMIT_QUOTA"},
   {"Your time limit has been reached","LIMIT_QUOTA"},{"Your account has expired","EXPIRED"},{"Login failed.","WRONG_PASSWORD"},{"Invalid user name/password. Please contact the administrator.","WRONG_PASSWORD"},
   {"The system could not log you on. Make sure your password is correct","WRONG_PASSWORD"},{"Firewall authentication failed","WRONG_PASSWORD"},{"Invalid credentials specified","WRONG_PASSWORD"},
   {"invalid username or password","WRONG_PASSWORD"},{"no more sessions are allowed for user u1","LIMIT_SESSION"},{"user u1 has reached traffic limit","LIMIT_QUOTA"},{"user u1 has reached uptime limit","LIMIT_QUOTA"},
   {"Maximum number of devices reached","LIMIT_SESSION"},{"Simultaneous use limit","LIMIT_SESSION"},{"Daily quota exhausted","LIMIT_QUOTA"},{"Bandwidth limit exceeded","LIMIT_QUOTA"},
   {"Account is disabled","EXPIRED"},{"Unrecognized authentication response","REFUSED"},{"Something odd happened","REFUSED"}};
  int ok=0;for(String[] c:cases){Portal.Kind k=Portal.refusal(c[0]);if(k.name().equals(c[1]))ok++;else System.out.println("      classifier: '"+c[0]+"' -> "+k+" (want "+c[1]+")");}
  check(ok==cases.length,"refusal classifier: "+ok+"/"+cases.length+" portal messages read correctly (limits, quotas, expiry, wrong password, unknown)");}

 static void portal(String kind,String type,int port)throws Exception{
  Detect.Found f=Detect.run(http(port),null);
  check(f.loginUrl!=null&&type.equals(f.type),kind+": detected "+f.summary()+" · "+f.loginUrl+" · hops "+f.chain.size());
  if(f.loginUrl==null)return;Drivers.Net n=net(f);Drivers.Driver d=Drivers.of(n.type);
  Portal.Result w=d.login(locked(port,n,"nope"),n,"u1","nope");
  check(w.kind==Portal.Kind.WRONG_PASSWORD||(kind.equals("aruba")||kind.equals("ciscowlc"))&&w.refused(),kind+": wrong password -> "+w);
  check(Drivers.probe(http(port))==Portal.Live.OUT,kind+": still behind the portal after the wrong password");
  admin(port,"/__admin?user=u1&outcome=limit_data");Portal.Result q=d.login(locked(port,n,"p1"),n,"u1","p1");
  check(q.refused()&&q.kind!=Portal.Kind.WRONG_PASSWORD,kind+": data-limit account -> "+q+" (not parked as a wrong password)");
  admin(port,"/__admin?user=u1&outcome=ok");
  long t=System.nanoTime();Portal.Result r=d.login(locked(port,n,"p1"),n,"u1","p1");long ms=(System.nanoTime()-t)/1000000;
  check(r.ok(),kind+": correct login -> "+r+" in "+ms+" ms");
  check(Drivers.probe(http(port))==Portal.Live.IN,kind+": generate_204 now answers 204");
  String st=admin(port,"/__state");check(!st.contains("\"p1\"")&&!st.contains("nope"),kind+": portal never logged a password in clear (mock state redacted, fields "+st.replaceAll(".*\"last_fields\"\\s*:\\s*(\\{[^}]*\\}).*","$1")+")");
  check(d.check(http(port),n,"u1")==Portal.Live.IN,kind+": check says signed in");
  admin(port,"/__admin?expire=1");check(d.check(http(port),n,"u1")==Portal.Live.OUT,kind+": after the portal drops the session, check says signed out");
  Portal.Result r2=d.login(locked(port,n,"p1"),n,"u1","p1");check(r2.ok(),kind+": sign back in -> "+r2);
  Boolean lo=d.logout(locked(port,n,null),n,"u1");Portal.Live after=Drivers.probe(http(port));
  System.out.println("      "+kind+": logout -> "+(lo==null?"no logout endpoint (session uses check)":lo)+", probe after: "+after);
  if(lo!=null)check(Boolean.TRUE.equals(lo)==(after==Portal.Live.OUT),kind+": logout result matches the probe");}

 // ---------- Session failover on the Sophos mock (the real engine, the real driver) ----------
 static final class H implements Session.Host,Session.Io{final ScheduledExecutorService ex=Executors.newSingleThreadScheduledExecutor();final Map<String,ScheduledFuture<?>> tasks=new HashMap<>();
  final int port;final Drivers.Net n;final List<Accounts.Account> accts=new ArrayList<>();final Map<String,String> pw=new HashMap<>();String status="",suppressed;Accounts.Account activeA;int activeIdx;List<String> log=Collections.synchronizedList(new ArrayList<>());Session s;
  H(int port,Drivers.Net n){this.port=port;this.n=n;s=new Session(this,this);}
  void add(String u,String p){Accounts.Account a=new Accounts.Account("id-"+u,u);accts.add(a);pw.put(a.id,p);}
  public long now(){return System.currentTimeMillis();}
  public void schedule(String tag,long d,Runnable r){cancel(tag);tasks.put(tag,ex.schedule(()->{tasks.remove(tag);r.run();},d,TimeUnit.MILLISECONDS));}
  public void cancel(String tag){ScheduledFuture<?> f=tasks.remove(tag);if(f!=null)f.cancel(false);}
  public String suppressedNet(){return suppressed;}public void setSuppressedNet(String id){suppressed=id;}
  public void status(String t){status=t;log.add("STATUS "+t);}public void lastCheck(String r){}public void lastSignIn(long ms,String how){}
  public void timeline(boolean fresh,String title,String line){log.add((fresh?"-- "+title+"\n":"")+"   "+line);}
  public void notify(String t,boolean a){log.add("NOTIFY "+t);}
  public void active(Accounts.Account a,int i,int total){activeA=a;activeIdx=i;}
  public List<Accounts.Account> accounts(){return accts;}
  public Portal.Result login(Accounts.Account a,int c,int r)throws Exception{String p=pw.get(a.id);return Drivers.of(n.type).login(locked(port,n,p),n,a.user,p);}
  public Portal.Live check(Accounts.Account a,int c,int r){try{return Drivers.of(n.type).check(http(port),n,a==null?"":a.user);}catch(Exception e){return Portal.Live.UNKNOWN;}}
  public Boolean logout(Accounts.Account a,int c,int r)throws Exception{return Drivers.of(n.type).logout(locked(port,n,null),n,a.user);}
  public String webLogin(Accounts.Account a){return null;}public void saved(Accounts.Account a){log.add("SAVED "+a.user+" "+a.state);}public long nextReset(long t){return t+3600_000L;}
  void on(Runnable r)throws Exception{ex.submit(r).get(20,TimeUnit.SECONDS);}
  boolean waitStatus(String prefix,long ms)throws Exception{long end=System.currentTimeMillis()+ms;while(System.currentTimeMillis()<end){if(status.startsWith(prefix))return true;Thread.sleep(50);}return false;}}
 static List<String> logins(int port)throws Exception{String s=admin(port,"/__state");String l=s.replaceAll("(?s).*\"logins\"\\s*:\\s*\\[([^\\]]*)\\].*","$1").replace("\"","").replace(" ","");return l.isEmpty()?new ArrayList<>():new ArrayList<>(Arrays.asList(l.split(",")));}
 static void failover(int port)throws Exception{Detect.Found f=Detect.run(http(port),null);Drivers.Net n=net(f);H h=new H(port,n);h.add("u1","p1");h.add("u2","p2");h.add("u3","p3");
  admin(port,"/__admin?user=u1&outcome=limit_login");
  h.on(()->h.s.onAvailable("net-1",System.currentTimeMillis()));check(h.waitStatus("Signed in",8000),"failover: primary at its login limit -> signed in with the next account: '"+h.status+"'");
  check(h.status.equals("Signed in as u2 · account 2 of 3"),"failover: subline names the active account and its position");
  check(logins(port).equals(Arrays.asList("u1","u2")),"failover: exactly one try for the limited primary, then account 2 (portal saw "+logins(port)+")");
  check("LIMIT_SESSION".equals(h.accts.get(0).state),"failover: primary marked 'limit reached' with its time");
  // quota runs out mid-session on account 2
  admin(port,"/__admin?reset=1");admin(port,"/__admin?user=u1&outcome=limit_login");admin(port,"/__admin?user=u2&outcome=limit_data");
  h.on(()->h.s.onValidationLost("Android lost internet validation"));check(h.waitStatus("Signed in",8000),"mid-session: quota ran out on account 2 -> re-login fails over: '"+h.status+"'");
  check(h.status.startsWith("Signed in as u3")&&logins(port).equals(Arrays.asList("u2","u3")),"mid-session: limited primary skipped (still within its window), account 2 tried once, account 3 signed in ("+logins(port)+")");
  check("LIMIT_QUOTA".equals(h.accts.get(1).state),"mid-session: account 2 marked data/time limit until the daily reset");
  // next connect: session-limited primary is retried first and now works
  admin(port,"/__admin?reset=1");h.on(()->h.s.onLost("net-1"));h.on(()->h.s.onAvailable("net-2",System.currentTimeMillis()));check(h.waitStatus("Signed in",8000),"next connect: signed in");
  check(h.status.equals("Signed in as u1 · account 1 of 3")&&logins(port).equals(Arrays.asList("u1")),"next connect: primary retried first (device limits clear on reconnect) and signs in; quota-limited account 2 left alone ("+logins(port)+")");
  // wrong password parks the account; no retry loops
  admin(port,"/__admin?reset=1");h.pw.put("id-u1","WRONG");h.on(()->h.s.onLost("net-2"));h.on(()->h.s.onAvailable("net-3",System.currentTimeMillis()));h.waitStatus("Signed in",8000);
  check(h.status.equals("Signed in as u3 · account 3 of 3")&&logins(port).equals(Arrays.asList("u1","u3")),"wrong password: primary tried once, parked; quota-limited #2 skipped; #3 signs in ("+logins(port)+", '"+h.status+"')");
  check("WRONG_PASSWORD".equals(h.accts.get(0).state),"wrong password: account shows 'Wrong password'");
  admin(port,"/__admin?reset=1");h.on(()->h.s.onLost("net-3"));h.on(()->h.s.onAvailable("net-4",System.currentTimeMillis()));h.waitStatus("Signed in",8000);
  check(!logins(port).contains("u1"),"wrong password: parked account not retried on the next connect (no lockout loop) ("+logins(port)+")");
  // all refused
  admin(port,"/__admin?reset=1");for(String u:new String[]{"u2","u3"})admin(port,"/__admin?user="+u+"&outcome=limit_devices");h.accts.get(1).state="";h.on(()->h.s.onLost("net-4"));h.on(()->h.s.onAvailable("net-5",System.currentTimeMillis()));
  check(h.waitStatus("Couldn't sign in",8000)&&Session.kind(h.status).equals("ATTENTION"),"all refused: clear status and no endless retries: '"+h.status+"'");Thread.sleep(1500);
  check(logins(port).equals(Arrays.asList("u2","u3")),"all refused: each eligible account tried once ("+logins(port)+")");
  h.ex.shutdownNow();}

 // ---------- credentials only go to the confirmed portal host ----------
 static void security(int port)throws Exception{Detect.Found f=Detect.run(http(port),null);Drivers.Net n=net(f);n.hosts.clear();n.hosts.add("10.9.9.9");
  Portal.Result r=null;Exception e=null;try{r=Drivers.of(n.type).login(locked(port,n,"p1"),n,"u1","p1");}catch(Exception x){e=x;}
  check(e instanceof Http.Blocked,"security: a login form posting to a host other than the confirmed portal is blocked before anything is sent ("+(e==null?r:e.getMessage())+")");
  check(logins(port).isEmpty(),"security: the portal received no credentials");}
}
