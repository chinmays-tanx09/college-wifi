package bot.paradox.wifi;
import android.content.*;
/** PendingIntent NetworkCallback: revives the app after process death. v3: no delayed re-schedules (v2's 90 s job here kept replacing the 30 s retry). */
public final class NetworkReceiver extends BroadcastReceiver {public void onReceive(Context c,Intent i){if(Store.enabled(c)&&!Brain.serviceAlive)Triggers.schedule(c);}}
