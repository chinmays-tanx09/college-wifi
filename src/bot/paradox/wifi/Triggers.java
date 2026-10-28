package bot.paradox.wifi;
import android.app.*;import android.app.job.*;import android.content.*;import android.net.*;
final class Triggers {
 static final int ID=410,JOB=971;
 static PendingIntent pi(Context c){return PendingIntent.getBroadcast(c,ID,new Intent(c,NetworkReceiver.class).setAction("bot.paradox.wifi.NETWORK"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);}
 static void register(Context c){if(!Store.enabled(c))return;try{Net.cm(c).registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),pi(c));}catch(Exception e){Store.status(c,"Wi-Fi watch could not start. Open the app to check permissions.");}}
 static void unregister(Context c){try{Net.cm(c).unregisterNetworkCallback(pi(c));}catch(Exception ignored){}JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);js.cancel(JOB);js.cancel(972);}
 /** Expedited, no constraints, no delay. Not rescheduled if already pending (avoids the replace-and-postpone trap). */
 static void schedule(Context c){try{JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);if(js.getPendingJob(JOB)!=null)return;
  JobInfo.Builder j=new JobInfo.Builder(JOB,new ComponentName(c,LoginJob.class)).setExpedited(true);try{js.schedule(j.build());}catch(Exception quota){js.schedule(new JobInfo.Builder(JOB,new ComponentName(c,LoginJob.class)).setOverrideDeadline(0).build());}}catch(Exception ignored){}}
}
