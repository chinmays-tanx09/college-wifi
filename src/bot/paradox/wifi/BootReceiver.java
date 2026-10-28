package bot.paradox.wifi;
import android.content.*;
public final class BootReceiver extends BroadcastReceiver {public void onReceive(Context c,Intent i){if(!Store.enabled(c))return;
 if(Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()))Store.p(c).edit().remove("suppressed_net").apply(); // network ids restart after reboot
 Triggers.register(c);try{c.startForegroundService(new Intent(c,MonitorService.class));}catch(Exception e){Store.status(c,"Open the app once to restart Wi-Fi monitoring");}}}
