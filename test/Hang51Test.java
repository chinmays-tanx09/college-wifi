package bot.paradox.wifi;
import java.util.*;
/** v5.1 regression tests for the 27 Oct "stuck on Signing out…" report (real Session + Sophos driver + mocks/mocks.py, dummy logins). */
public class Hang51Test {
 static int fails=0,passes=0;
 static void check(boolean ok,String what){System.out.println((ok?"PASS  ":"FAIL  ")+what);if(ok)passes++;else fails++;}
 static boolean busy(String st){return Session.kind(st).startsWith("SIGNING");}
 public static void main(String[] a)throws Exception{int port=19640;Process p=H51.start(port);
  try{
   kinds();
   quotaDuringSignIn(port);
   allQuota(port);
   logoutHangs(port);
   logoutHtml(port);
   logoutThrows(port);
   cancel(port);
   blockedButLive(port);
   blockedLogoutHangs(port);
   signInHangs(port);
   tapPreemptsStuckCheck(port);
  }catch(Throwable t){check(false,"crashed: "+t);t.printStackTrace(System.out);}
  finally{p.destroy();}
  System.out.println("\n"+passes+" passed, "+fails+" failed");System.out.println(fails==0?"ALL PASS":"FAILURES: "+fails);System.exit(fails==0?0:1);}

 static void kinds(){String[][] c={{Session.SIGNIN_CANCELLED,"ATTENTION"},{Session.SIGNOUT_CANCELLED,"ATTENTION"},{"Data limit reached on all 2 accounts · x","ATTENTION"},{"Interrupted · the app restarted","ATTENTION"},
   {"Signing in with the next account… (account 1: data limit)","SIGNING_IN"},{"Signing out…","SIGNING_OUT"},{"Sign-out didn't go through · x","ATTENTION"},{"Couldn't start signing out · x","ATTENTION"}};
  int ok=0;for(String[] x:c)if(Session.kind(x[0]).equals(x[1]))ok++;else System.out.println("      kind('"+x[0]+"') = "+Session.kind(x[0]));
  check(ok==c.length,"screen states: every new final state maps to a non-spinner screen, switching maps to Signing in ("+ok+"/"+c.length+")");}

 static void quotaDuringSignIn(int port)throws Exception{
  for(String how:new String[]{"limit_data_html","limit_data_redirect","limit_data"}){
   H51 h=H51.signedIn(port,"u1","u2");H51.admin(port,"/__admin?user=u1&outcome="+how);
   Portal.Result r=Drivers.of(h.n.type).login(h.locked("p1"),h.n,"u1","p1");
   check(r.kind==Portal.Kind.LIMIT_QUOTA,"quota at sign-in ("+how+"): portal's answer read as a data limit, not an unreadable page ("+r+")");
   H51.admin(port,"/__admin?reset=1");H51.admin(port,"/__admin?user=u1&outcome="+how);
   long t=System.currentTimeMillis();h.on(()->h.s.onAvailable("net-q",t));boolean ok=h.waitStatus("Signed in",10000);
   check(ok&&h.status.startsWith("Signed in as u2")&&H51.logins(port).equals(Arrays.asList("u1","u2"))&&"LIMIT_QUOTA".equals(h.accts.get(0).state),
    "quota at sign-in ("+how+"): account 1 tried once, failover signs in with account 2 in "+(System.currentTimeMillis()-t)+" ms ('"+h.status+"', portal saw "+H51.logins(port)+")");
   if(!ok)h.dump();h.ex.shutdownNow();}}

 static void allQuota(int port)throws Exception{H51 h=H51.signedIn(port,"u1","u2");H51.admin(port,"/__admin?user=u1&outcome=limit_data");H51.admin(port,"/__admin?user=u2&outcome=limit_data_html");
  long t=System.currentTimeMillis();h.on(()->h.s.onAvailable("net-a",t));boolean ok=h.waitStatus("Data limit reached",10000);
  check(ok&&h.status.startsWith("Data limit reached on all 2 accounts")&&Session.kind(h.status).equals("ATTENTION"),"no account left: clear 'Data limit reached on all accounts' screen, no spinner ('"+h.status+"')");
  H.sleep(1500);check(H51.logins(port).equals(Arrays.asList("u1","u2"))&&h.tasks.isEmpty(),"no account left: each account tried once, nothing scheduled after (portal saw "+H51.logins(port)+", timers "+h.tasks.keySet()+")");
  H51 one=H51.signedIn(port,"u1");H51.admin(port,"/__admin?user=u1&outcome=limit_data");long t2=System.currentTimeMillis();one.on(()->one.s.onAvailable("net-b",t2));
  check(one.waitStatus("Data limit reached on your account",10000),"single account at its limit: 'Data limit reached on your account' ('"+one.status+"')");
  if(!ok)h.dump();h.ex.shutdownNow();one.ex.shutdownNow();}

 /** Sign in u1 on a fresh session. */
 static H51 in(int port,String... users)throws Exception{H51 h=H51.signedIn(port,users);long t=System.currentTimeMillis();h.on(()->h.s.onAvailable("net-"+t,t));if(!h.waitStatus("Signed in",8000))throw new IllegalStateException("precondition sign-in failed: "+h.status);return h;}
 static long logoutAndWait(H51 h,long max){long t=System.currentTimeMillis();h.post(h.s::logout);H.sleep(50);return h.untilNotBusy(t,max);}

 static void logoutHangs(int port)throws Exception{H51 h=in(port,"u1","u2");H51.admin(port,"/__admin?logout=hang&live=hang&hang=60");
  long ms=logoutAndWait(h,20000);
  check(ms>=0&&ms<=Session.LOGOUT_LIMIT_MS+1000,"logout endpoint + status check hang 60 s: final state after "+ms+" ms (cap "+Session.LOGOUT_LIMIT_MS+" ms): '"+h.status+"'");
  check(!h.s.busy&&!h.s.opActive&&h.status.startsWith("Couldn't reach the portal to confirm sign-out"),"logout hang: engine not busy, honest 'couldn't confirm' screen, auto sign-in paused ("+(h.suppressed!=null)+")");
  long t=System.currentTimeMillis();h.on(()->{});check(System.currentTimeMillis()-t<1000,"logout hang: the engine thread is free again ("+(System.currentTimeMillis()-t)+" ms for a queued no-op)");
  check(h.aborts>0,"logout hang: the hung connections were dropped ("+h.aborts+" aborts)");
  if(ms<0)h.dump();H51.admin(port,"/__admin?reset=1");h.ex.shutdownNow();}

 static void logoutHtml(int port)throws Exception{H51 h=in(port,"u1");H51.admin(port,"/__admin?logout=html");
  long ms=logoutAndWait(h,15000);
  check(ms>=0&&ms<=Session.LOGOUT_LIMIT_MS+1000&&!busy(h.status),"logout answered with an HTML data-limit page: final state after "+ms+" ms: '"+h.status+"'");
  H51.admin(port,"/__admin?reset=1");h.ex.shutdownNow();}

 static void logoutThrows(int port)throws Exception{H51 h=in(port,"u1");h.logoutThrows=true;h.checkThrows=true;
  long ms=logoutAndWait(h,15000);
  check(ms>=0&&ms<=3000&&!h.s.busy&&!busy(h.status),"logout and check throw on the worker thread: final state after "+ms+" ms, not busy: '"+h.status+"'");
  h.ex.shutdownNow();}

 static void cancel(int port)throws Exception{H51 h=in(port,"u1");H51.admin(port,"/__admin?logout=hang&live=hang&hang=60");
  long t=System.currentTimeMillis();h.post(h.s::logout);H.sleep(1500);long c=System.currentTimeMillis();boolean was=h.s.cancel();long ms=h.untilNotBusy(c,3000);
  check(was&&ms>=0&&ms<=1000&&h.status.equals(Session.SIGNOUT_CANCELLED),"Cancel during a hung sign-out (from another thread): final state "+ms+" ms after the tap: '"+h.status+"'");
  long q=System.currentTimeMillis();h.on(()->{});check(System.currentTimeMillis()-q<1000,"Cancel: engine thread free at once");
  if(ms<0)h.dump();H51.admin(port,"/__admin?reset=1");h.ex.shutdownNow();}

 static void blockedButLive(int port)throws Exception{H51 h=in(port,"u1","u2");
  // the data limit runs out but the portal keeps the session "Live" and intercepts the internet instead
  H51.admin(port,"/__admin?blocked=1&live=ack");H51.admin(port,"/__admin?user=u1&outcome=limit_data");int lo0=H51.logouts(port);
  long t=System.currentTimeMillis();h.post(()->h.s.onValidationLost("Android lost internet validation"));
  boolean ok=h.waitStatus("Signed in as u2",15000);long ms=System.currentTimeMillis()-t;
  check(ok&&ms<=Session.OP_LIMIT_MS,"signed in but blocked (quota, session still live): switches to account 2 in "+ms+" ms ('"+h.status+"')");
  check(String.join("\n",h.log).contains("intercepts the internet")&&H51.logouts(port)>lo0&&"LIMIT_QUOTA".equals(h.accts.get(0).state),"blocked: account 1 signed out first and marked at its limit (logouts "+(H51.logouts(port)-lo0)+", state "+h.accts.get(0).state+")");
  if(!ok)h.dump();H51.admin(port,"/__admin?reset=1");h.ex.shutdownNow();}

 static void blockedLogoutHangs(int port)throws Exception{H51 h=in(port,"u1","u2");
  H51.admin(port,"/__admin?blocked=1&live=ack&logout=hang&hang=60");int lo0=H51.logouts(port);
  long t=System.currentTimeMillis();h.post(()->h.s.onValidationLost("Android lost internet validation"));
  boolean ok=h.waitStatus("Signed in as u2",16000);long ms=System.currentTimeMillis()-t;
  check(ok&&ms<=Session.OP_LIMIT_MS+500&&H51.logouts(port)>lo0,"blocked + logout hangs: failover still proceeds to account 2 after the (hung) logout attempt, in "+ms+" ms ('"+h.status+"')");
  if(!ok)h.dump();H51.admin(port,"/__admin?reset=1");h.ex.shutdownNow();}

 static void signInHangs(int port)throws Exception{H51 h=H51.signedIn(port,"u1","u2");h.loginDelayMs=60000;
  long t=System.currentTimeMillis();h.on(()->h.s.onAvailable("net-h",t));long ms=h.untilNotBusy(t,20000);
  check(ms>=0&&ms<=Session.OP_LIMIT_MS+1000,"sign-in call hangs 60 s: 'Signing in' ends after "+ms+" ms (cap "+Session.OP_LIMIT_MS+" ms): '"+h.status+"'");
  check(Session.kind(h.status).equals("ATTENTION"),"sign-in hang: honest retry screen, not a spinner");
  h.loginDelayMs=0;h.ex.shutdownNow();}

 static void tapPreemptsStuckCheck(int port)throws Exception{H51 h=in(port,"u1","u2");h.checkHangMs=60000;
  h.post(()->h.s.onValidationLost("Android lost internet validation"));H.sleep(300);
  long tap=System.currentTimeMillis();h.status("Signing out…");h.s.preempt();h.post(h.s::logout);
  h.checkHangMs=0;long ms=h.untilNotBusy(tap,16000);
  check(ms>=0&&ms<=Session.OP_LIMIT_MS&&h.logoutCalls>0&&h.suppressed!=null,"Sign out tapped while a re-check is stuck: sign-out starts at once, final state "+ms+" ms after the tap: '"+h.status+"'");
  h.ex.shutdownNow();}

 static final class H{static void sleep(long ms){try{Thread.sleep(ms);}catch(InterruptedException e){}}}
}
