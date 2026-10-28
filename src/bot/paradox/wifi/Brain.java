package bot.paradox.wifi;
import android.app.*;import android.content.*;import android.net.*;import android.os.*;
import java.io.*;import java.util.*;import java.util.concurrent.*;
/**
 * Process-wide owner of the sign-in state machine. One HandlerThread does all portal I/O and timing,
 * so the foreground service, the buttons and the setup screen can never run two logins at once. No wakelocks.
 * v5: any saved network, any portal (Drivers), several accounts per network (Session failover).
 */
final class Brain implements Session.Host, Session.Io {
 private static Brain instance;
 static synchronized Brain get(Context c){if(instance==null)instance=new Brain(c.getApplicationContext());return instance;}
 static volatile boolean serviceAlive=false;
 final Context ctx;final Handler h,main;final Session s;volatile Network network;volatile Store.NetProfile profile;
 private Brain(Context c){ctx=c;HandlerThread t=new HandlerThread("paradox-portal",android.os.Process.THREAD_PRIORITY_BACKGROUND);t.start();h=new Handler(t.getLooper());main=new Handler(Looper.getMainLooper());s=new Session(this,this);
  // v5.1: a busy state saved by a process that died mid-way (crash, OS kill, update) is not real any more: nothing is running now.
  String st=Store.status(c);if(Session.kind(st).startsWith("SIGNING"))Store.status(c,Store.p(c).getString("suppressed_net",null)!=null?Session.PAUSED:"Interrupted · the app restarted while it was busy · tap Sign in now to retry");}
 void post(Runnable r){h.post(r);}
 /** v5.1: every portal connection is remembered (weakly) so Cancel / the watchdog / a call past its deadline can drop it at once. */
 private final Set<java.net.URLConnection> conns=Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<java.net.URLConnection,Boolean>()));
 Http http(Network n,Store.NetProfile np){final Http.Opener o=Net.opener(n,np==null?null:np.pins);return new Http(u->{java.net.URLConnection c=o.open(u);conns.add(c);return c;});}
 public void abort(){java.net.URLConnection[] all;synchronized(conns){all=conns.toArray(new java.net.URLConnection[0]);conns.clear();}
  for(java.net.URLConnection c:all)if(c instanceof java.net.HttpURLConnection)new Thread(()->{try{((java.net.HttpURLConnection)c).disconnect();}catch(Throwable ignored){}},"paradox-abort").start();}
 public Portal.Live internet(int c,int r){try{Network n=net();return Drivers.probe(http(n,profile).timeouts(c,r));}catch(Exception e){return Portal.Live.UNKNOWN;}}

 // ---------- v5.1 watchdog + Cancel: a busy screen can never outlive OP_LIMIT_MS, whatever the portal thread is doing ----------
 private volatile int userGen;private final Runnable watchdogR=this::watchdog;
 /** Main thread. A busy state was just shown: one-shot check after the cap (no polling; re-armed by every busy status write). */
 void armWatchdog(){main.removeCallbacks(watchdogR);main.postDelayed(watchdogR,Session.OP_LIMIT_MS+1500);}
 private void watchdog(){String st=Store.status(ctx);if(!Session.kind(st).startsWith("SIGNING"))return;long age=System.currentTimeMillis()-Store.p(ctx).getLong("status_time",0);
  if(age<Session.OP_LIMIT_MS){main.postDelayed(watchdogR,Session.OP_LIMIT_MS-age+500);return;}
  timeline(false,null,"watchdog: '"+st+"' for "+(age/1000)+" s with no progress · stopping it");cancelBusy(true);}
 /** Main thread. Cancel button (auto=false) or watchdog (auto=true). Drops queued taps, releases the running operation, closes its connections. */
 void cancelBusy(boolean auto){if(!auto)userGen++; /* Cancel drops a queued tap; the watchdog lets it still run when the thread frees */boolean running=s.cancel();abort();
  // queued behind whatever is running: stop the auto sign-in loop, and if a busy state is still on screen once we get here, end it
  h.post(()->{s.fast=false;s.cancelAll();String k2=Session.kind(Store.status(ctx));if(k2.startsWith("SIGNING")&&!s.opActive)status(k2.equals("SIGNING_OUT")?"Sign-out cancelled · nothing was sent":Session.SIGNIN_CANCELLED);});
  String k=Session.kind(Store.status(ctx));
  if(!running){ // nothing of ours is running: the tap's job is queued behind something, or a dead state is on screen. Say so now.
   if(k.equals("SIGNING_OUT"))status(auto?"Couldn't start signing out · the app was stuck on the portal for "+(Session.OP_LIMIT_MS/1000)+" s · tap Sign in now to re-check":"Sign-out cancelled · nothing was sent");
   else if(k.equals("SIGNING_IN"))status(auto?"Couldn't sign in · no answer from the portal in "+(Session.OP_LIMIT_MS/1000)+" s · will try again on the next Wi-Fi change, screen on, or tap":Session.SIGNIN_CANCELLED);
   return;}
  // the worker unwinds and writes its own final state within moments; if it somehow doesn't, write one ourselves (one-shot)
  final long t0=Store.p(ctx).getLong("status_time",0);main.postDelayed(()->{if(Session.kind(Store.status(ctx)).startsWith("SIGNING")&&Store.p(ctx).getLong("status_time",0)==t0)status(k.equals("SIGNING_OUT")?Session.SIGNOUT_CANCELLED:Session.SIGNIN_CANCELLED);},2500);}
 /** Main thread (screen opened): a busy state with no progress for longer than the cap and nothing running is cleared now. */
 void reconcile(){String st=Store.status(ctx);if(!Session.kind(st).startsWith("SIGNING"))return;long age=System.currentTimeMillis()-Store.p(ctx).getLong("status_time",0);
  if(age>Session.OP_LIMIT_MS+1500&&!s.opActive)cancelBusy(true);else armWatchdog();}
 /** Is a portal operation running right now (for the screen's Cancel button)? */
 boolean busyNow(){return s.opActive;}

 // ---------- network events (called from any thread) ----------
 void wifi(Network n,NetworkCapabilities caps,long seenAt){if(!Integrity.ok(ctx))return;h.post(()->resolve(n,caps,seenAt,0));}
 private final Set<Network> resolving=new HashSet<>();
 private void resolve(Network n,NetworkCapabilities caps,long seenAt,int round){
  if(n==null)return;if(n.equals(network)&&n.toString().equals(s.net))return;
  long since=SystemClock.elapsedRealtime()-seenAt;String ssid=Net.ssid(ctx,n,caps);if(ssid!=null)Store.p(ctx).edit().putString("wifi_now",ssid).apply();
  if(!Store.enabled(ctx))return;
  Store.NetProfile np=Store.net(ctx,ssid);String how;
  if(ssid!=null&&np==null){resolving.remove(n);return;}   // a Wi-Fi we have no login for
  if(np!=null)how="SSID read at +"+since+" ms";
  else{ // SSID unreadable (location off / not populated yet): accept only if this network's portal is one we saved
   if(round==0&&!resolving.add(n))return;
   Detect.Found f=Session.capped(()->Detect.run(http(n,null).timeouts(1500,3000),null),8000,new Detect.Found());
   if(n.equals(network)&&n.toString().equals(s.net))return;
   np=f.host==null?null:Store.byHost(ctx,f.host);
   if(np==null){if(!f.reached&&!f.online&&SystemClock.elapsedRealtime()-seenAt<30000){final int r=round+1;h.postDelayed(()->resolve(n,null,seenAt,r),1000);}else resolving.remove(n);return;}
   how="SSID unreadable · saved portal "+f.host+" answered at +"+(SystemClock.elapsedRealtime()-seenAt)+" ms";}
  resolving.remove(n);adopt(n,np);Store.p(ctx).edit().putString("match_note",how).apply();
  s.onAvailable(n.toString(),System.currentTimeMillis()-since);s.tl(how);}
 private void adopt(Network n,Store.NetProfile np){network=n;profile=np;Store.p(ctx).edit().putString("current_ssid",np.n.ssid).apply();MonitorService.poke();}
 void lost(Network n){h.post(()->{resolving.remove(n);if(n.equals(network)){network=null;profile=null;MonitorService.poke();s.onLost(n.toString());}});}
 void linkChanged(Network n){h.post(()->{if(n.equals(network))s.kick();});}
 void validationLost(Network n,String why){h.post(()->{if(n.equals(network))s.onValidationLost(why);});}
 void captivePortal(Network n,String why){h.post(()->{if(n.equals(network))s.onCaptivePortal(why);});}
 void screenOn(){h.post(s::onScreenOn);}
 /** Android's "Sign in to Wi-Fi network" was tapped and this app picked: sign in only on a saved network, else hand back. */
 void systemSignIn(Network n,String portalUrl,java.util.function.Consumer<Boolean> matched){h.post(()->{Store.NetProfile np=null;
  try{NetworkCapabilities c=Net.cm(ctx).getNetworkCapabilities(n);String ssid=Net.ssid(ctx,n,c);np=Store.net(ctx,ssid);
   if(np==null&&ssid==null){Detect.Found f=Session.capped(()->Detect.run(http(n,null),portalUrl),8000,new Detect.Found());if(f.host!=null)np=Store.byHost(ctx,f.host);}}catch(Exception ignored){}
  final boolean r=np!=null&&!np.accounts.isEmpty();main.post(()->matched.accept(r));
  if(r){adopt(n,np);s.net=n.toString();s.manualSignIn("Sign-in notification tapped");}});}
 /** Main thread (buttons). The busy state shows at once and the watchdog is armed; a tap cancelled before it ran is dropped. */
 void signInNow(){status("Signing in…");armWatchdog();s.preempt();final int g=userGen;h.post(()->{if(g!=userGen)return;try{find();s.manualSignIn("Sign in tapped");}catch(Exception e){status("Couldn't start signing in ("+Session.brief(e)+") · tap Sign in now to retry");}});}
 void logout(){status("Signing out…");armWatchdog();s.preempt();final int g=userGen;h.post(()->{if(g!=userGen)return;try{find();s.logout();}catch(Exception e){status("Couldn't start signing out ("+Session.brief(e)+") · tap Sign in now to re-check");}});}
 /** Button pressed before any callback matched: a connected saved network by SSID. */
 private void find(){if(network!=null)return;Network n=Net.matching(ctx);if(n!=null){Store.NetProfile np=Store.net(ctx,Net.ssid(ctx,n));if(np!=null){adopt(n,np);s.net=n.toString();}}}
 /** Accounts or network settings changed in the UI: re-read them on the portal thread. */
 void credentialsChanged(){h.post(()->{if(profile!=null){Store.NetProfile np=Store.net(ctx,profile.n.ssid);if(np==null){profile=null;}else profile=np;}});}

 // ---------- setup: find the portal on the current Wi-Fi, then test a login ----------
 static final class Draft { Network network;String ssid;Detect.Found found;Store.NetProfile np=new Store.NetProfile();String certHost,certFp; }
 /** Probe on whatever Wi-Fi is connected now. hint = a URL typed by the user (manual override) or null. */
 void detect(String hint,Store.NetProfile pinsFrom,java.util.function.Consumer<Draft> done){h.post(()->{Draft d=new Draft();d.network=Net.currentWifi(ctx);
  if(d.network==null){main.post(()->done.accept(d));return;}d.ssid=Net.ssid(ctx,d.network);if(pinsFrom!=null)d.np.pins.putAll(pinsFrom.pins);
  d.found=Detect.run(http(d.network,d.np),hint);
  if(d.found.loginUrl==null&&d.found.error!=null&&d.found.error.contains("certificate")){String u=hint!=null&&hint.startsWith("https")?hint:null;
   if(u==null)for(String c:d.found.chain)if(c.startsWith("https")){u=c;break;}if(u!=null){d.certHost=Http.host(u);d.certFp=Net.fingerprint(d.network,u);}}
  if(d.found.loginUrl!=null){Drivers.Net n=d.np.n;n.ssid=d.ssid==null?"":d.ssid;n.type=d.found.type;n.loginUrl=d.found.loginUrl;n.base=Http.origin(d.found.loginUrl);n.hosts.clear();n.hosts.addAll(d.found.hosts);}
  main.post(()->done.accept(d));});}
 /** One real sign-in with the typed login against the detected portal. On success the network and account are saved. */
 void testSignIn(Draft d,String user,String pass,java.util.function.Consumer<Portal.Result> done){h.post(()->{Portal.Result r;if(!Integrity.ok(ctx)){main.post(()->done.accept(new Portal.Result(Portal.Kind.REFUSED,"This copy of the app was modified")));return;}
  try{Http x=http(d.network,d.np);x.allowedHosts=d.np.n.hosts;x.secret=pass;r=Drivers.of(d.np.n.type).login(x,d.np.n,user,pass);
   if(r.kind==Portal.Kind.UNREADABLE){String w=web(d.network,d.np,user,pass);if(w!=null)r=w.equals("OK")?new Portal.Result(Portal.Kind.OK,"Signed in via portal page"):new Portal.Result(Portal.refusal(w.substring(Math.min(8,w.length()))),w.substring(Math.min(8,w.length())));if(w!=null&&w.equals("OK"))d.np.n.recipe.put("web","1");}}
  catch(Http.Blocked b){r=new Portal.Result(Portal.Kind.REFUSED,"The login form sends to a different server ("+b.getMessage().replace("refusing to send to ","").replace(" (not this network's portal)","")+"), so nothing was sent");}
  catch(Exception e){r=new Portal.Result(Portal.Kind.UNREADABLE,"Couldn't reach the portal ("+Session.brief(e)+")");}
  if(r.ok()){try{Store.NetProfile existing=Store.net(ctx,d.np.n.ssid);Store.NetProfile np=existing!=null?existing:d.np;if(existing!=null){existing.n.type=d.np.n.type;existing.n.loginUrl=d.np.n.loginUrl;existing.n.base=d.np.n.base;existing.n.hosts.clear();existing.n.hosts.addAll(d.np.n.hosts);existing.n.recipe.putAll(d.np.n.recipe);existing.pins.putAll(d.np.pins);}
    Accounts.Account a=null;for(Accounts.Account x:np.accounts)if(x.user.equals(user))a=x;if(a==null)a=Store.addAccount(ctx,np,user,pass);else{Store.updateAccount(ctx,np,a,user,pass);}
    Store.saveNet(ctx,np);adopt(d.network,np);s.net=d.network.toString();s.loggedIn=true;s.activeId=a.id;int i=np.accounts.indexOf(a)+1;active(a,i,np.accounts.size());
    status("Signed in as "+user+(np.accounts.size()>1?" · account "+i+" of "+np.accounts.size():""));}catch(Exception e){r=new Portal.Result(Portal.Kind.REFUSED,"Signed in, but couldn't save the login securely on this phone");}}
  final Portal.Result fr=r;main.post(()->done.accept(fr));});}

 // ---------- Session.Host ----------
 public long now(){return System.currentTimeMillis();}
 public void schedule(String tag,long d,Runnable r){h.removeCallbacksAndMessages(tag);h.postAtTime(r,tag,SystemClock.uptimeMillis()+d);}
 public void cancel(String tag){h.removeCallbacksAndMessages(tag);}
 public String suppressedNet(){return Store.p(ctx).getString("suppressed_net",null);}
 public void setSuppressedNet(String id){Store.p(ctx).edit().putString("suppressed_net",id).apply();}
 public void status(String t){Store.status(ctx,t);updateNotification(t);if(Session.kind(t).startsWith("SIGNING"))main.post(this::armWatchdog);}
 public void lastCheck(String r){Store.p(ctx).edit().putString("last_check",r).putLong("last_check_time",System.currentTimeMillis()).apply();}
 public void lastSignIn(long ms,String how){Store.p(ctx).edit().putLong("last_signin_ms",ms).putString("last_signin_how",how).putLong("last_signin_time",System.currentTimeMillis()).apply();if(Updater.due(ctx))Updater.checkSoon(ctx);}
 public void active(Accounts.Account a,int i,int total){Store.p(ctx).edit().putString("active_id",a.id).putString("active_user",a.user).putInt("active_index",i).putInt("active_total",total).apply();}
 private final ArrayList<String> timeline=new ArrayList<>();private String title="";
 public synchronized void timeline(boolean fresh,String t,String line){if(fresh){timeline.clear();title=t+" at "+new java.text.SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date());}timeline.add(line);while(timeline.size()>40)timeline.remove(0);
  StringBuilder b=new StringBuilder(title);for(String l:timeline)b.append('\n').append(l);String key=title.startsWith("Logout")?"logout_timeline":"timeline";Store.p(ctx).edit().putString(key,b.toString()).apply();}
 public void notify(String text,boolean attention){if(!attention){Network n=network;if(n!=null)try{Net.cm(ctx).reportNetworkConnectivity(n,true);}catch(Exception ignored){}return;}
  NotificationManager nm=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);nm.createNotificationChannel(new NotificationChannel("wifi_status","College Wi-Fi status",NotificationManager.IMPORTANCE_DEFAULT));PendingIntent pi=PendingIntent.getActivity(ctx,0,new Intent(ctx,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  try{nm.notify(42,new Notification.Builder(ctx,"wifi_status").setSmallIcon(R.drawable.ic_wifi_off).setColor(0xff3f3f46).setContentTitle(text).setContentIntent(pi).setAutoCancel(true).build());}catch(SecurityException ignored){}}
 String lastNote;
 void updateNotification(String text){if(!serviceAlive||text.equals(lastNote))return;lastNote=text;MonitorService.poke();}

 // ---------- Session.Io: every request is bound to the saved Wi-Fi network, never mobile data ----------
 private Network net()throws IOException{Network n=network;if(n==null)throw new IOException("college Wi-Fi not connected");return n;}
 public List<Accounts.Account> accounts(){Store.NetProfile np=profile;return np==null?Collections.<Accounts.Account>emptyList():np.accounts;}
 public Portal.Result login(Accounts.Account a,int c,int r)throws Exception{if(!Integrity.ok(ctx))throw new IOException("app signature check failed");Network n=net();Store.NetProfile np=profile;String[] cr=Store.creds(np,a);if(cr==null)return new Portal.Result(Portal.Kind.EXPIRED,"Saved login can't be read on this phone any more · re-enter it");
  try{Http x=http(n,np).timeouts(c,r);x.allowedHosts=np.n.hosts;x.secret=cr[1];Map<String,String> before=new HashMap<>(np.n.recipe);
   Portal.Result res="1".equals(np.n.r("web"))?webResult(n,np,cr[0],cr[1]):Drivers.of(np.n.type).login(x,np.n,cr[0],cr[1]);
   if(!before.equals(np.n.recipe))Store.saveNet(ctx,np);return res;}
  catch(Http.Blocked b){status("Portal moved to a different server · open the app and re-detect it");throw b;}
  finally{cr[0]=null;cr[1]=null;}}
 private Portal.Result webResult(Network n,Store.NetProfile np,String u,String p){String w=web(n,np,u,p);if(w==null)return new Portal.Result(Portal.Kind.UNREADABLE,"hidden browser couldn't run");if(w.equals("OK"))return new Portal.Result(Portal.Kind.OK,"Signed in via portal page");String m=w.startsWith("REFUSED:")?w.substring(8):w;return new Portal.Result(Portal.refusal(m),m);}
 public Portal.Live check(Accounts.Account a,int c,int r){try{Network n=net();Store.NetProfile np=profile;return Drivers.of(np.n.type).check(http(n,np).timeouts(c,r),np.n,a==null?"":a.user);}catch(Exception e){return Portal.Live.UNKNOWN;}}
 public Boolean logout(Accounts.Account a,int c,int r)throws Exception{Network n=net();Store.NetProfile np=profile;Http x=http(n,np).timeouts(c,r);x.allowedHosts=np.n.hosts;return Drivers.of(np.n.type).logout(x,np.n,a==null?"":a.user);}
 public String webLogin(Accounts.Account a){Network n=network;Store.NetProfile np=profile;if(n==null||np==null)return null;String[] cr=Store.creds(np,a);if(cr==null)return null;try{String w=web(n,np,cr[0],cr[1]);if("OK".equals(w)){np.n.recipe.put("web","1");Store.saveNet(ctx,np);}return w;}finally{cr[0]=null;cr[1]=null;}}
 public void saved(Accounts.Account a){Store.NetProfile np=profile;if(np!=null)Store.saveNet(ctx,np);}
 public long nextReset(long t){Calendar c=Calendar.getInstance();c.setTimeInMillis(t);c.add(Calendar.DAY_OF_YEAR,1);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,30);c.set(Calendar.MILLISECOND,0);return c.getTimeInMillis();}
 /** Hidden WebView, bound to the Wi-Fi network, limited to the portal's confirmed hosts. Blocks this (portal) thread up to 20 s. */
 String web(Network n,Store.NetProfile np,String u,String p){CountDownLatch done=new CountDownLatch(1);final String[] out={null};
  main.post(()->new LoginEngine(ctx,n,np,u,p,code->{out[0]=code;done.countDown();}).start());try{done.await(22,TimeUnit.SECONDS);}catch(InterruptedException ignored){}return out[0];}
 /** v5.2: read the campus account's daily quota from the Sophos User Portal (Sophos networks only, signed in, nothing else running). Portal thread, blocks it up to 30 s. */
 volatile long portalAsked;
 void portalQuota(){long t=System.currentTimeMillis();if(t-portalAsked<20_000)return;portalAsked=t;h.post(()->{Network n=network;Store.NetProfile np=profile;
  if(n==null||np==null||!Portal.SOPHOS.equals(np.n.type)||s.busy||s.opActive||!"CONNECTED".equals(Session.kind(Store.status(ctx)))){return;}
  String host=Http.host(np.n.base!=null&&!np.n.base.isEmpty()?np.n.base:np.n.loginUrl);if(host==null||host.isEmpty())return;
  Accounts.Account a=null;String id=Store.p(ctx).getString("active_id",null);for(Accounts.Account x:np.accounts)if(x.id.equals(id))a=x;if(a==null&&!np.accounts.isEmpty())a=np.accounts.get(0);if(a==null)return;
  String[] cr=Store.creds(np,a);if(cr==null)return;final String fh=host;CountDownLatch done=new CountDownLatch(1);
  try{main.post(()->new UserPortalEngine(ctx,n,fh,cr[0],cr[1],(page,err)->{UsageTracker.portalResult(ctx,page,err);done.countDown();}).start());try{done.await(32,TimeUnit.SECONDS);}catch(InterruptedException ignored){}}
  finally{cr[0]=null;cr[1]=null;}});}
}
