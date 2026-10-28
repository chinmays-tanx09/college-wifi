package bot.paradox.wifi;
import android.content.*;import android.content.pm.PackageInstaller;
/** PackageInstaller's answer: open the system confirm screen when it asks for the user, else record the outcome. */
public final class UpdateReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context c,Intent i){int st=i.getIntExtra(PackageInstaller.EXTRA_STATUS,-99);
  if(st==PackageInstaller.STATUS_PENDING_USER_ACTION){Intent confirm=i.getParcelableExtra(Intent.EXTRA_INTENT);if(confirm!=null){confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);try{c.startActivity(confirm);}catch(Exception ignored){}}return;}
  Store.p(c).edit().putString("upd_result",st==PackageInstaller.STATUS_SUCCESS?"Updated":"Install didn't finish: "+i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)).apply();}
}
