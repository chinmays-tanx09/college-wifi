package bot.paradox.wifi;
import java.util.*;
/** Replays Chinmay's 27 Oct report on the engine: signed in, the data limit cuts traffic, a silent re-check is stuck on a portal/DNS call
 *  that no socket timeout covers, and he taps Sign out. The screen pre-writes "Signing out…" (v5 MainActivity / v5.1 Brain) and queues the job.
 *  v5 expectation (bug): after 16 s the status is still "Signing out…" and auto sign-in was never paused (his footer: "Auto sign-in on").
 *  v5.1 expectation: the tap pre-empts the stuck re-check; a final state is on screen within 15 s. Run with -Dexpect=stuck to assert the v5 bug. */
public class Repro51 {
 public static void main(String[] a)throws Exception{int port=19631;Process p=H51.start(port);int rc=1;
  try{H51 h=H51.signedIn(port,"u1","u2");long t=System.currentTimeMillis();h.on(()->h.s.onAvailable("net-1",t));
   if(!h.waitStatus("Signed in",8000)){System.out.println("FAIL  precondition: could not sign in: "+h.status);return;}
   H51.admin(port,"/__admin?user=u1&outcome=limit_data");
   h.checkHangMs=60000;h.post(()->h.s.onValidationLost("Android lost internet validation"));Thread.sleep(300);
   long tap=System.currentTimeMillis();h.status("Signing out…"); // the button writes this before handing over the job
   try{Session.class.getDeclaredMethod("preempt").invoke(h.s);System.out.println("      (engine has preempt: v5.1)");}catch(NoSuchMethodException e){System.out.println("      (engine has no preempt: v5)");}
   h.post(h.s::logout);
   long ms=h.untilNotBusy(tap,16000);
   System.out.println("RESULT after tap: "+(ms<0?"still '"+h.status+"' after 16 s":"final state in "+ms+" ms: '"+h.status+"'")+" · auto sign-in "+(h.suppressed==null?"ON (sign-out never started)":"paused (sign-out ran)")+" · logout calls "+h.logoutCalls);
   boolean stuck=ms<0&&h.suppressed==null&&Session.kind(h.status).equals("SIGNING_OUT");
   if("stuck".equals(System.getProperty("expect"))){System.out.println((stuck?"PASS  ":"FAIL  ")+"v5 reproduces the screenshot: stuck on 'Signing out…' with 'Auto sign-in on'");rc=stuck?0:1;}
   else{boolean ok=ms>=0&&ms<=15000&&h.logoutCalls>0;System.out.println((ok?"PASS  ":"FAIL  ")+"v5.1: same scenario reaches a final state within 15 s and the sign-out really ran");rc=ok?0:1;}
   if(rc!=0)h.dump();
  }finally{p.destroy();}
  System.exit(rc);}
}
