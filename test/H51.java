package bot.paradox.wifi;
import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.*;
/** v5.1 test host: the real Session + Sophos driver against mocks/mocks.py, with fault hooks (hanging / throwing portal calls).
 *  Compiles against both v5 and v5.1 Session (no v5.1-only API here) so the same scenario can be replayed on the shipped engine. */
public class H51 implements Session.Host,Session.Io{
 final ScheduledExecutorService ex=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"session");t.setDaemon(true);return t;});final Map<String,ScheduledFuture<?>> tasks=new ConcurrentHashMap<>();
 final int port;final Drivers.Net n;final List<Accounts.Account> accts=new ArrayList<>();final Map<String,String> pw=new HashMap<>();
 volatile String status="",suppressed;volatile long statusAt;final List<String> log=Collections.synchronizedList(new ArrayList<>());Session s;
 volatile long loginDelayMs,checkHangMs;volatile boolean logoutThrows,checkThrows;volatile int aborts,logoutCalls;
 final Set<URLConnection> conns=Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<URLConnection,Boolean>()));
 H51(int port,Drivers.Net n){this.port=port;this.n=n;s=new Session(this,this);}
 void add(String u,String p){Accounts.Account a=new Accounts.Account("id-"+u,u);accts.add(a);pw.put(a.id,p);}
 Http http(){return new Http(u->{String x=u.toString();for(String h:new String[]{"http://connectivitycheck.gstatic.com","http://clients3.google.com","http://captive.apple.com"})x=x.replace(h,"http://127.0.0.1:"+port);URLConnection c=new URL(x).openConnection();conns.add(c);return c;}).timeouts(2000,4000);}
 Http locked(String secret){Http h=http();h.allowedHosts=n.hosts;h.secret=secret;return h;}
 public long now(){return System.currentTimeMillis();}
 public void schedule(String tag,long d,Runnable r){cancel(tag);tasks.put(tag,ex.schedule(()->{tasks.remove(tag);r.run();},d,TimeUnit.MILLISECONDS));}
 public void cancel(String tag){ScheduledFuture<?> f=tasks.remove(tag);if(f!=null)f.cancel(false);}
 public String suppressedNet(){return suppressed;}public void setSuppressedNet(String id){suppressed=id;}
 public void status(String t){status=t;statusAt=now();log.add(String.format("[%6d] STATUS %s",now()%1000000,t));}
 public void lastCheck(String r){}public void lastSignIn(long ms,String how){}
 public void timeline(boolean fresh,String title,String line){log.add((fresh?"-- "+title+"\n":"")+"   "+line);}
 public void notify(String t,boolean a){log.add("NOTIFY "+t);}
 public void active(Accounts.Account a,int i,int total){}
 public List<Accounts.Account> accounts(){return accts;}
 static void nap(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){}}
 public Portal.Result login(Accounts.Account a,int c,int r)throws Exception{if(loginDelayMs>0)nap(loginDelayMs);String p=pw.get(a.id);return Drivers.of(n.type).login(locked(p),n,a.user,p);}
 public Portal.Live check(Accounts.Account a,int c,int r){if(checkHangMs>0)nap(checkHangMs);if(checkThrows)throw new IllegalStateException("check blew up");try{return Drivers.of(n.type).check(http(),n,a==null?"":a.user);}catch(Exception e){return Portal.Live.UNKNOWN;}}
 public Boolean logout(Accounts.Account a,int c,int r)throws Exception{logoutCalls++;if(logoutThrows)throw new IllegalStateException("logout blew up");return Drivers.of(n.type).logout(locked(null),n,a==null?"":a.user);}
 public String webLogin(Accounts.Account a){return null;}public void saved(Accounts.Account a){log.add("SAVED "+a.user+" "+a.state);}public long nextReset(long t){return t+3600_000L;}
 public void abort(){aborts++;URLConnection[] all;synchronized(conns){all=conns.toArray(new URLConnection[0]);conns.clear();}for(URLConnection c:all)if(c instanceof HttpURLConnection)((HttpURLConnection)c).disconnect();}
 public Portal.Live internet(int c,int r){return Drivers.probe(http());}
 void on(Runnable r)throws Exception{ex.submit(r).get(40,TimeUnit.SECONDS);}
 void post(Runnable r){ex.submit(r);}
 boolean waitStatus(String prefix,long ms){long end=now()+ms;while(now()<end){if(status.startsWith(prefix))return true;nap(50);}return false;}
 /** ms until the status is no longer busy (SIGNING_*), or -1 if it still is after max. */
 long untilNotBusy(long since,long max){long end=since+max;while(now()<end){if(!Session.kind(status).startsWith("SIGNING"))return now()-since;nap(25);}return -1;}
 void dump(){synchronized(log){for(String l:log)System.out.println("      | "+l.replace("\n","\n      | "));}}
 static Process start(int port)throws Exception{ProcessBuilder pb=new ProcessBuilder("python3",System.getProperty("mocks","mocks/mocks.py"),"--kind","sophos","--port",""+port,"--users","u1:p1,u2:p2,u3:p3");pb.redirectErrorStream(true);pb.redirectOutput(new File("/tmp/mock51_"+port+".log"));Process p=pb.start();
  for(int i=0;i<50;i++){try(Socket so=new Socket("127.0.0.1",port)){return p;}catch(IOException e){Thread.sleep(100);}}throw new IOException("mock did not start");}
 static String admin(int port,String q){try{HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+q).openConnection();c.setConnectTimeout(2000);c.setReadTimeout(4000);try(InputStream in=c.getResponseCode()>=400?c.getErrorStream():c.getInputStream()){ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[4096];int k;while((k=in.read(b))>0)o.write(b,0,k);return o.toString("UTF-8");}}catch(Exception e){return "ERR "+e;}}
 static List<String> logins(int port){String s=admin(port,"/__state");String l=s.replaceAll("(?s).*\"logins\"\\s*:\\s*\\[([^\\]]*)\\].*","$1").replace("\"","").replace(" ","");return l.isEmpty()?new ArrayList<>():new ArrayList<>(Arrays.asList(l.split(",")));}
 static int logouts(int port){String s=admin(port,"/__state");try{return Integer.parseInt(s.replaceAll("(?s).*\"logouts\"\\s*:\\s*(\\d+).*","$1"));}catch(Exception e){return -1;}}
 static H51 signedIn(int port,String... users)throws Exception{admin(port,"/__admin?reset=1");Detect.Found f=Detect.run(new H51(port,new Drivers.Net()).http(),null);Drivers.Net n=new Drivers.Net();n.type=f.type;n.loginUrl=f.loginUrl;n.base=Http.origin(f.loginUrl);n.hosts.addAll(f.hosts);
  H51 h=new H51(port,n);for(String u:users)h.add(u,"p"+u.substring(1));return h;}
}
