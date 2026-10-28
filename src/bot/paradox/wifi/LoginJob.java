package bot.paradox.wifi;
import android.app.job.*;import android.net.*;import android.os.*;
/**
 * Backup path only, for when Android has killed the foreground service: the PendingIntent network callback wakes
 * NetworkReceiver, which schedules this expedited job. It feeds the same Brain (same fast loop) and keeps the process
 * alive ~40 s so the sign-in can finish. When the service is alive this job does nothing (no duplicate logins).
 */
public final class LoginJob extends JobService {
 private final Handler h=new Handler(Looper.getMainLooper());
 @Override public boolean onStartJob(JobParameters p){
  if(!Store.enabled(this)||Brain.serviceAlive)return false;
  try{startForegroundService(new android.content.Intent(this,MonitorService.class));}catch(Exception notAllowedFromBackground){}
  Brain b=Brain.get(this);Network n=Net.matching(this);long t=SystemClock.elapsedRealtime();
  if(n!=null)b.wifi(n,null,t);else for(Network any:Net.cm(this).getAllNetworks())b.wifi(any,null,t);
  h.postDelayed(()->jobFinished(p,false),40000);return true;
 }
 @Override public boolean onStopJob(JobParameters p){h.removeCallbacksAndMessages(null);return false;}
}
