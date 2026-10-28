package bot.paradox.wifi;
import java.util.*;import java.util.concurrent.*;
/**
 * Pure-Java sign-in / re-check / logout state machine (no Android imports, so it runs in a JVM harness).
 * EVENT-DRIVEN (since v4): nothing is scheduled while signed in. Work happens only on events: Wi-Fi connect, Android
 * flagging a captive portal, Android losing VALIDATED, screen on / app open, the system "Sign in to network" tap,
 * or a button. The only timers are short, bounded retry chains right after such an event.
 * v5: works on any portal (through Io → Drivers) and with several accounts: on a limit/expiry/unknown refusal it
 * moves to the next account at once; a wrong password parks that account (one attempt, no lockout loops).
 * Every method runs on ONE worker thread (Handler thread on Android). Passwords never pass through here.
 * v5.1: nothing on that thread can hang. Every portal call runs on a helper thread with a deadline (CALL_LIMIT_MS) and every
 * busy state (signing in / signing out / switching) has a hard cap (OP_LIMIT_MS = 15 s) and writes its final state in a finally.
 * cancel() may be called from ANY thread (Cancel button, watchdog): it releases the worker thread at once.
 */
final class Session {
 static long OP_LIMIT_MS=15000, LOGOUT_LIMIT_MS=12000, CALL_LIMIT_MS=9000;
 static long FAST_LOOP_MS=OP_LIMIT_MS, FAST_RETRY_MS=1000, SCREEN_RECHECK_GAP_MS=120000;
 static long[] LATE_RETRY_MS={15000,45000};
 static long[] RELOGIN_BACKOFF_MS={2000,4000};
 static long BLOCKED_RETRY_MS=15*60_000L;
 interface Io {
  /** Saved accounts for the current network, in the user's order (first = primary). */
  List<Accounts.Account> accounts();
  /** One sign-in attempt with one account. Throws only when the portal could not be reached. */
  Portal.Result login(Accounts.Account a,int connectMs,int readMs) throws Exception;
  /** Is this device signed in? Never throws (UNKNOWN when nothing answered). */
  Portal.Live check(Accounts.Account active,int connectMs,int readMs);
  /** true/false = the portal's answer; null = no logout endpoint known. */
  Boolean logout(Accounts.Account active,int connectMs,int readMs) throws Exception;
  /** WebView fallback for pages only a browser can drive: "OK", "REFUSED:<portal text>", or null when it could not run. */
  String webLogin(Accounts.Account a);
  /** Persist an account's state (limit time, wrong password…). */
  void saved(Accounts.Account a);
  /** Next local midnight after t (daily quotas reset). */
  long nextReset(long t);
  /** v5.1: drop every portal connection in flight (Cancel, watchdog, a call past its deadline). */
  default void abort(){}
  /** v5.1: does the open internet answer on this Wi-Fi? IN = 204, OUT = the portal intercepted it, UNKNOWN = no answer. Never throws. */
  default Portal.Live internet(int connectMs,int readMs){return Portal.Live.UNKNOWN;}
 }
 interface Host {
  long now();
  void schedule(String tag,long delayMs,Runnable r);
  void cancel(String tag);
  String suppressedNet();
  void setSuppressedNet(String id);
  void status(String text);
  void lastCheck(String result);
  void lastSignIn(long msFromWifi,String how);
  void timeline(boolean fresh,String title,String line);
  void notify(String text,boolean attention);
  void active(Accounts.Account a,int index,int total);
 }
 static final String GAVE_UP="Couldn't reach the college portal · will try again on the next Wi-Fi change, screen on, or tap";
 static final String PAUSED="Signed out by you · auto sign-in paused until Wi-Fi reconnects or you tap Sign in";
 static final String SIGNIN_CANCELLED="Sign-in cancelled · tap Sign in now to try again";
 static final String SIGNOUT_CANCELLED="Sign-out cancelled · auto sign-in stays paused until Wi-Fi reconnects or you tap Sign in";
 enum Chain { SIGNED_IN, UNREACHABLE, ALL_REFUSED, NO_ACCOUNTS, CANCELLED }

 // ---------- v5.1: bounded portal calls + cancel from any thread ----------
 static final ExecutorService IO_POOL=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"paradox-io");t.setDaemon(true);return t;});
 static final class Cancelled extends Exception { Cancelled(){super("cancelled");} }
 static final class TimedOut extends Exception { TimedOut(String what){super(what+": no answer in time");} }
 private volatile CompletableFuture<Object> inflight;
 volatile boolean opActive,cancelRequested,silentCheck,preempted;volatile long opStartedAt;volatile String opKind="";
 void begin(String kind){cancelRequested=false;if(!silentCheck)preempted=false;opKind=kind;opStartedAt=host.now();opActive=true;}
 void end(){opActive=false;cancelRequested=false;inflight=null;}
 /** Any thread. Stops the busy operation now; the worker thread unwinds and writes the final state itself. false = nothing was running. */
 boolean cancel(){if(!opActive)return false;cancelRequested=true;CompletableFuture<Object> f=inflight;if(f!=null)f.completeExceptionally(new CancellationException("cancelled"));try{io.abort();}catch(Throwable ignored){}return true;}
 /** Any thread. The user just tapped Sign in / Sign out: stop a silent re-check or an automatic sign-in now so the tap runs at once
  *  (it is queued right behind). The stopped work writes no "cancelled" state of its own; the tap writes the next state. */
 void preempt(){if(!opActive&&!silentCheck)return;preempted=true;if(opActive)cancelRequested=true;CompletableFuture<Object> f=inflight;if(f!=null)f.completeExceptionally(new CancellationException("preempted"));}
 private boolean tookPreempt(){boolean p=preempted;preempted=false;return p;}
 /** Run one portal call on a helper thread; give up at the deadline (or CALL_LIMIT_MS) or on cancel. The abandoned call's result is ignored. */
 @SuppressWarnings("unchecked")
 <T> T bounded(String what,Callable<T> c,long deadline)throws Exception{
  if(cancelRequested)throw new Cancelled();
  long left=Math.min(deadline-host.now(),CALL_LIMIT_MS);if(left<=0)throw new TimedOut(what);
  CompletableFuture<Object> f=new CompletableFuture<>();inflight=f;
  try{IO_POOL.execute(()->{try{f.complete(c.call());}catch(Throwable t){f.completeExceptionally(t);}});
   if(cancelRequested)throw new Cancelled();
   return (T)f.get(left,TimeUnit.MILLISECONDS);}
  catch(TimeoutException e){try{io.abort();}catch(Throwable ignored){}throw new TimedOut(what);}
  catch(CancellationException|InterruptedException e){throw new Cancelled();}
  catch(ExecutionException e){Throwable t=e.getCause();if(t instanceof CancellationException||cancelRequested)throw new Cancelled();if(t instanceof Exception)throw (Exception)t;throw new Exception(t);}
  finally{inflight=null;}}
 /** A pause on the worker thread that Cancel can cut short (no Thread.sleep). */
 void pause(long ms,long deadline)throws Cancelled{long left=Math.min(ms,deadline-host.now());if(left>0){CompletableFuture<Object> f=new CompletableFuture<>();inflight=f;
   try{f.get(left,TimeUnit.MILLISECONDS);}catch(TimeoutException ignored){}catch(Exception e){throw new Cancelled();}finally{inflight=null;}}
  if(cancelRequested)throw new Cancelled();}
 /** For callers outside a Session (Brain's network matching): run c with a hard cap, fallback on timeout/failure. */
 static <T> T capped(Callable<T> c,long ms,T fallback){Future<T> f=IO_POOL.submit(c);try{return f.get(ms,TimeUnit.MILLISECONDS);}catch(Exception e){f.cancel(true);return fallback;}}
 final Io io;final Host host;
 String net,activeId,lastRefusal;long assocAt,fastStart,lastCheckAt,reloginDeadline;boolean fast,loggedIn,busy,webTried,fresh,manualTry;int fastAttempt,lateIdx=-1;
 Session(Io io,Host host){this.io=io;this.host=host;}
 boolean paused(){return net!=null&&net.equals(host.suppressedNet());}
 long since(){return host.now()-assocAt;}
 void tl(String line){host.timeline(false,null,"+"+since()+" ms  "+line);}

 // ---------- events ----------
 void onAvailable(String id,long availableAt){
  if(id.equals(net))return;
  String s=host.suppressedNet();if(s!=null&&!s.equals(id))host.setSuppressedNet(null);
  net=id;assocAt=availableAt;loggedIn=false;webTried=false;fresh=true;cancelAll();
  host.timeline(true,"Wi-Fi connected (network "+id+")","+0 ms  Wi-Fi available");
  tl("saved Wi-Fi recognised");
  if(paused()){host.status(PAUSED);return;}
  if(io.accounts().isEmpty()){host.status("Save your portal login in the app");return;}
  startFast("connect");
 }
 void onLost(String id){if(net==null||!net.equals(id))return;tl("Wi-Fi lost");net=null;fast=false;loggedIn=false;cancelAll();host.setSuppressedNet(null);host.status("Waiting for college Wi-Fi");}
 void kick(){if(fast&&!busy){host.cancel("fast");host.schedule("fast",0,this::fastStep);}}
 void onCaptivePortal(String why){if(net==null||busy)return;if(paused()){host.status(PAUSED);return;}host.timeline(true,"Portal detected ("+why+")","+0 ms  "+why);assocAt=host.now();loggedIn=false;
  if(fast){kick();return;}cancelAll();startFast("captive portal");}
 void onValidationLost(String why){if(net==null||fast||busy)return;check(why);}
 void onScreenOn(){if(net==null||fast||busy||paused())return;if(host.now()-lastCheckAt<SCREEN_RECHECK_GAP_MS)return;check("screen on");}
 void manualSignIn(String why){if(net==null){host.status("Not on college Wi-Fi right now");return;}host.setSuppressedNet(null);host.timeline(true,why,"+0 ms  "+why);assocAt=host.now();loggedIn=false;webTried=false;fresh=true;manualTry=true;cancelAll();
  if(io.accounts().isEmpty()){host.status("Save your portal login in the app");return;}startFast("manual");}

 // ---------- connect loop: try at once, ~1 s retries for up to 30 s while the portal comes up ----------
 void startFast(String why){fast=true;fastStart=host.now();fastAttempt=0;host.status("Signing in…");host.schedule("fast",0,this::fastStep);}
 void fastStep(){if(!fast||net==null||busy)return;if(paused()){fast=false;host.status(PAUSED);return;}
  fastAttempt++;long t=host.now();long deadline=fastStart+FAST_LOOP_MS;Chain c=Chain.UNREACHABLE;
  begin("sign-in");try{c=runChain(fastAttempt==1?"attempt 1":"attempt "+fastAttempt,deadline);}catch(Throwable e){tl("sign-in step stopped: "+e);}finally{end();}
  if(c==Chain.CANCELLED){fast=false;cancelAll();if(tookPreempt())return;host.status(SIGNIN_CANCELLED);host.lastCheck("Sign-in cancelled");return;}
  if(net==null||!fast){if(net!=null&&c!=Chain.SIGNED_IN)host.status("Couldn't reach the college portal yet · will try again on the next Wi-Fi change, screen on, or tap");return;}
  switch(c){
   case SIGNED_IN:return;
   case NO_ACCOUNTS:fast=false;host.status("Save your portal login in the app");return;
   case ALL_REFUSED:fast=false;allRefused();return;
   default:break;}
  if(host.now()+Math.max(100,FAST_RETRY_MS-(host.now()-t))<deadline-500){host.status("Signing in… waiting for the portal ("+fastAttempt+")");host.schedule("fast",Math.max(100,FAST_RETRY_MS-(host.now()-t)),this::fastStep);return;}
  fast=false;host.status("Couldn't reach the college portal yet · retrying automatically");host.lastCheck("Portal not reachable after "+(FAST_LOOP_MS/1000)+" s");late(0);}
 /** Bounded late retries (15 s, then 45 s). After that: idle until the next network event. */
 void late(int i){if(i>=LATE_RETRY_MS.length){host.status(GAVE_UP);return;}lateIdx=i;host.schedule("check",LATE_RETRY_MS[i],()->check("retry "+(i+1)+" after connect"));}

 /** Try the accounts in order until one signs in. A refusal moves on at once; an unreachable portal stops the chain (retry later).
  *  v5.1: every call is bounded by the deadline; Cancel returns CANCELLED at once. */
 Chain runChain(String label,long deadline){List<Accounts.Account> all=io.accounts();if(all.isEmpty())return Chain.NO_ACCOUNTS;
  boolean manual=manualTry;manualTry=false;
  List<Accounts.Account> cands=Accounts.candidates(all,host.now(),fresh,manual);if(cands.isEmpty())return Chain.ALL_REFUSED;
  for(Accounts.Account a:cands){if(cancelRequested)return Chain.CANCELLED;int idx=Accounts.indexOf(all,a.id)+1;
   if(cands.size()<all.size()||idx>1)host.status("Signing in with account "+idx+" of "+all.size()+"…");
   tl(label+": signing in as "+a.user+(all.size()>1?" (account "+idx+" of "+all.size()+")":""));long t=host.now();Portal.Result r;
   try{r=bounded("sign-in",()->io.login(a,2500,6000),deadline);}
   catch(Cancelled e){tl("cancelled");return Chain.CANCELLED;}
   catch(Exception e){tl("no answer ("+brief(e)+")");return Chain.UNREACHABLE;}
   if(net==null)return Chain.UNREACHABLE;
   if(r==null)r=new Portal.Result(Portal.Kind.UNREADABLE,"empty reply");
   tl("reply in "+(host.now()-t)+" ms: "+r);
   if(r.kind==Portal.Kind.UNREADABLE){if(webTried){tl("page still unreadable");return Chain.UNREACHABLE;}webTried=true;tl("trying the portal page in a hidden browser");String w;
    try{w=bounded("hidden browser",()->io.webLogin(a),deadline);}catch(Cancelled e){tl("cancelled");return Chain.CANCELLED;}catch(Exception e){w=null;tl("hidden browser: "+brief(e));}
    tl("portal page result: "+(w==null?"could not run":w));
    if(w==null)return Chain.UNREACHABLE;if(w.equals("OK"))r=new Portal.Result(Portal.Kind.OK,"Signed in via portal page");else{String m=w.startsWith("REFUSED:")?w.substring(8):w;r=new Portal.Result(Portal.refusal(m),m);}}
   if(r.ok()){fresh=false;success(a,idx,all.size(),r);return Chain.SIGNED_IN;}
   Accounts.mark(a,r,host.now(),io.nextReset(host.now()));io.saved(a);lastRefusal=r.message;
   tl("account "+idx+" "+label(r.kind)+(idx<all.size()?" · moving to the next account":""));}
  fresh=false;return Chain.ALL_REFUSED;}
 static String label(Portal.Kind k){switch(k){case WRONG_PASSWORD:return "refused: wrong password (parked until you edit it)";case EXPIRED:return "refused: account expired";case LIMIT_SESSION:return "hit a device/login limit";case LIMIT_QUOTA:return "hit a data/time limit";default:return "refused";}}
 void success(Accounts.Account a,int idx,int total,Portal.Result r){boolean wasFast=fast;fast=false;loggedIn=true;activeId=a.id;
  if(!a.state.isEmpty()){Accounts.mark(a,r,host.now(),0);io.saved(a);}host.active(a,idx,total);
  if(wasFast)host.lastSignIn(since(),"account "+idx);tl("SIGNED IN as "+a.user);
  host.status("Signed in as "+a.user+(total>1?" · account "+idx+" of "+total:""));host.lastCheck("Signed in ("+(total>1?"account "+idx+" of "+total:"direct")+")");lastCheckAt=host.now();
  host.notify(idx>1?"Signed in with account "+idx+" ("+a.user+")":"Signed in to college Wi-Fi",false);}
 void allRefused(){loggedIn=false;List<Accounts.Account> all=io.accounts();long now=host.now();
  boolean allQuota=!all.isEmpty();for(Accounts.Account a:all)if(!("LIMIT_QUOTA".equals(a.state)&&now<a.until))allQuota=false;
  if(allQuota){String m=all.size()==1?"Data limit reached on your account":"Data limit reached on all "+all.size()+" accounts";
   host.status(m+" · auto sign-in waits for the limit to reset · tap Sign in now to retry");host.lastCheck(m);host.notify(m+". Add another account, or wait for the reset.",true);return;}
  if(all.size()==1){Accounts.Account a=all.get(0);host.status("Portal: "+(lastRefusal==null||lastRefusal.isEmpty()?a.message:lastRefusal));host.lastCheck("Portal refused the saved login");host.notify("College portal refused your login. Check the app.",true);return;}
  host.status("Couldn't sign in · all "+all.size()+" accounts were refused or are at their limit");host.lastCheck("Every saved account refused");host.notify("All saved accounts were refused. Check Accounts in the app.",true);}

 // ---------- one-shot check (only on an event: validation lost, screen on, bounded retry) ----------
 Accounts.Account active(){List<Accounts.Account> all=io.accounts();for(Accounts.Account a:all)if(a.id.equals(activeId))return a;return all.isEmpty()?null:all.get(0);}
 void check(String why){if(net==null||fast||busy)return;if(paused()){host.status(PAUSED);return;}
  lastCheckAt=host.now();Accounts.Account act=active();Portal.Live s;
  silentCheck=true;try{s=bounded("check",()->io.check(act,2000,4000),host.now()+CALL_LIMIT_MS);}catch(Exception e){s=Portal.Live.UNKNOWN;}finally{silentCheck=false;}
  if(tookPreempt()){tl("re-check stopped: you tapped a button");return;}
  if(s==null)s=Portal.Live.UNKNOWN;
  if(s==Portal.Live.UNKNOWN){host.lastCheck("Portal unreachable ("+why+")");if(lateIdx>=0)late(lateIdx+1);return;}
  lateIdx=-1;
  if(s==Portal.Live.IN&&why.startsWith("Android lost")){ // v5.1: the portal says "signed in" but Android says no internet: is the portal blocking us (data limit)?
   Portal.Live w;silentCheck=true;try{w=bounded("internet check",()->io.internet(2000,4000),host.now()+CALL_LIMIT_MS);}catch(Exception e){w=Portal.Live.UNKNOWN;}finally{silentCheck=false;}
   if(tookPreempt()){tl("internet check stopped: you tapped a button");return;}
   if(w==Portal.Live.OUT){host.timeline(true,"Internet blocked ("+why+")","+0 ms  portal says signed in, but it intercepts the internet (data limit?)");assocAt=host.now();host.lastCheck("Signed in but blocked by the portal ("+why+")");blocked(act);return;}}
  if(s==Portal.Live.IN){boolean changed=!loggedIn;loggedIn=true;host.lastCheck("Signed in (checked: "+why+")");if(changed){Accounts.Account a=active();List<Accounts.Account> all=io.accounts();int i=a==null?1:Accounts.indexOf(all,a.id)+1;host.status("Signed in"+(a==null?"":" as "+a.user+(all.size()>1?" · account "+i+" of "+all.size():"")));}return;}
  loggedIn=false;host.timeline(true,"Signed out detected ("+why+")","+0 ms  portal says log in again");assocAt=host.now();host.lastCheck("Signed out detected ("+why+")");
  relogin(1);}
 void relogin(int attempt){if(net==null||busy||paused())return;if(attempt==1)reloginDeadline=host.now()+OP_LIMIT_MS;host.status("Signing back in… (try "+attempt+" of 3)");
  Chain c=Chain.UNREACHABLE;begin("re-login");try{c=runChain("re-login "+attempt,reloginDeadline);}catch(Throwable e){tl("re-login stopped: "+e);}finally{end();}
  if(c==Chain.CANCELLED){cancelAll();if(tookPreempt())return;host.status(SIGNIN_CANCELLED);host.lastCheck("Sign-in cancelled");return;}
  if(net==null)return;if(paused()){host.status(PAUSED);return;}if(busy)return;
  if(c==Chain.SIGNED_IN){host.lastSignIn(since(),"automatic re-login");return;}
  if(c==Chain.ALL_REFUSED){allRefused();return;}if(c==Chain.NO_ACCOUNTS){host.status("Save your portal login in the app");return;}
  if(attempt<3){long d=RELOGIN_BACKOFF_MS[attempt-1];if(host.now()+d+1500<reloginDeadline){host.schedule("relogin",d,()->relogin(attempt+1));return;}}
  host.status("Couldn't sign back in ("+attempt+(attempt==1?" try":" tries")+") · will try again on the next Wi-Fi change, screen on, or tap");}

 /** v5.1: signed in, but the portal intercepts the internet (typically a used-up data quota while the session stays "live").
  *  Mark this account, sign it out (bounded, result ignored), then go on to the next account at once. */
 void blocked(Accounts.Account cur){List<Accounts.Account> all=io.accounts();long now=host.now();
  if(cur!=null){Accounts.mark(cur,new Portal.Result(Portal.Kind.LIMIT_QUOTA,"Signed in, but the portal blocked the internet (data limit used up?)"),now,now+BLOCKED_RETRY_MS);io.saved(cur);}
  if(Accounts.candidates(all,now,false).isEmpty()){loggedIn=false;allRefused();return;}
  switchAccount(cur,"data limit");}
 void switchAccount(Accounts.Account from,String reason){List<Accounts.Account> all=io.accounts();int idx=from==null?1:Accounts.indexOf(all,from.id)+1;
  long deadline=host.now()+OP_LIMIT_MS;cancelAll();fast=false;loggedIn=false;fresh=false;
  host.status("Signing in with the next account… (account "+idx+": "+reason+")");tl("account "+idx+" "+reason+" · signing it out, then the next account");
  Chain c=Chain.UNREACHABLE;begin("switch");
  try{
   try{Boolean r=bounded("logout",()->io.logout(from,2500,4000),Math.min(deadline,host.now()+6000));tl("logout of account "+idx+" → "+(r==null?"no logout endpoint":r?"portal says signed out":"portal said no"));}
   catch(Cancelled e){throw e;}catch(Exception e){tl("logout of account "+idx+" → "+brief(e)+" · moving on anyway");}
   c=runChain("switch",deadline);}
  catch(Cancelled e){c=Chain.CANCELLED;}catch(Throwable t){tl("switch stopped: "+t);}
  finally{end();
   if(c==Chain.SIGNED_IN)host.lastSignIn(since(),"switched account ("+reason+")");
   else if(c==Chain.CANCELLED){if(!tookPreempt())host.status(SIGNIN_CANCELLED);}
   else if(c==Chain.ALL_REFUSED)allRefused();
   else if(c==Chain.NO_ACCOUNTS)host.status("Save your portal login in the app");
   else host.status("Couldn't reach the portal to switch accounts · will try again on the next Wi-Fi change, screen on, or tap");}}

 // ---------- user logout (v5.1: hard 12 s cap, final state always written in finally, Cancel releases it at once) ----------
 void logout(){if(net==null){host.status("Not on college Wi-Fi right now");return;}
  host.setSuppressedNet(net);cancelAll();fast=false;busy=true;host.status("Signing out…");assocAt=host.now();host.timeline(true,"Logout tapped","+0 ms  logout tapped · auto sign-in paused");
  long deadline=host.now()+LOGOUT_LIMIT_MS;String ok=null;Portal.Live last=null;boolean cancelled=false,timedOut=false;Accounts.Account a=active();
  begin("sign-out");
  try{
   for(int round=0;round<2&&ok==null;round++){
    try{Boolean r=bounded("logout",()->io.logout(a,3000,4000),deadline);tl("logout → "+(r==null?"no logout endpoint":r?"portal says signed out":"portal said no"));if(Boolean.TRUE.equals(r))ok="portal says you're signed out";}
    catch(Cancelled e){throw e;}catch(TimedOut e){timedOut=true;tl("logout → no answer in time");}catch(Exception e){tl("logout → "+brief(e));}
    if(ok==null){try{last=bounded("check",()->io.check(a,2000,3000),deadline);}catch(Cancelled e){throw e;}catch(TimedOut e){timedOut=true;last=Portal.Live.UNKNOWN;}catch(Exception e){last=Portal.Live.UNKNOWN;}
     tl("check → "+last);if(last==Portal.Live.OUT)ok="portal confirms you're signed out";}
    if(ok==null&&round==0)pause(1000,deadline);
    if(host.now()>=deadline-500)break;}}
  catch(Cancelled e){cancelled=true;tl("cancelled by you");}
  catch(Throwable t){tl("logout stopped: "+t);}
  finally{busy=false;end();
   if(ok!=null){loggedIn=false;tl("SIGNED OUT");host.status("Signed out · auto sign-in paused until Wi-Fi reconnects or you tap Sign in");host.lastCheck("Signed out ("+ok+")");}
   else if(cancelled){tl("cancelled");host.status(SIGNOUT_CANCELLED);host.lastCheck("Sign-out cancelled");}
   else if(last==Portal.Live.IN){host.status("Sign-out didn't go through · portal still shows you signed in. Auto sign-in stays paused.");host.lastCheck("Logout not accepted by portal");}
   else{host.status("Couldn't reach the portal to confirm sign-out"+(timedOut?" (no answer in "+(LOGOUT_LIMIT_MS/1000)+" s)":"")+" · auto sign-in stays paused");host.lastCheck("Logout unconfirmed (portal "+(timedOut?"too slow":"unreachable")+")");}}}

 void cancelAll(){host.cancel("fast");host.cancel("check");host.cancel("relogin");lateIdx=-1;}
 /** UI state for a status line written by this class. One place, unit-tested, so the screen never guesses. */
 static String kind(String st){if(st==null||st.isEmpty())return "IDLE";
  if(st.startsWith("Signed in")||st.startsWith("Signed back in"))return "CONNECTED";
  if(st.startsWith("Signing out"))return "SIGNING_OUT";
  if(st.startsWith("Signing"))return "SIGNING_IN";
  if(st.startsWith("Signed out"))return st.contains("refused")?"ATTENTION":"SIGNED_OUT";
  if(st.startsWith("Waiting for college")||st.startsWith("Not on college"))return "AWAY";
  if(st.startsWith("Save your portal login")||st.startsWith("Waiting for setup")||st.startsWith("Login deleted")||st.startsWith("Set up"))return "SETUP";
  if(st.startsWith("Auto-login off"))return "OFF";
  if(st.startsWith("Portal:")||st.startsWith("Couldn")||st.startsWith("Sign-out")||st.startsWith("Sign-in")||st.startsWith("Data limit")||st.startsWith("Interrupted")||st.startsWith("Background")||st.startsWith("Open the app")||st.startsWith("Wi-Fi watch")||st.startsWith("Portal moved"))return "ATTENTION";
  return "IDLE";}
 static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException ignored){}}
 static String brief(Exception e){if(e==null)return "unknown";String n=e.getClass().getSimpleName();String m=e.getMessage();return m==null?n:n+": "+(m.length()>80?m.substring(0,80):m);}
}
