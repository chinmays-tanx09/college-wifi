package bot.paradox.wifi;
import android.app.*;import android.content.*;import android.content.pm.*;import android.graphics.*;import android.graphics.drawable.*;import android.net.*;import android.os.*;import android.view.*;import android.widget.*;
/**
 * Handles Android's "Sign in to Wi-Fi network" tap (ACTION_CAPTIVE_PORTAL_SIGN_IN) when the user picks this app.
 * College Wi-Fi only: it signs in with the saved login, then tells Android the portal is dismissed so the
 * notification clears and Wi-Fi re-validates. Any other network (or any failure) is handed straight to the
 * system's own sign-in screen, else to the browser. It never sends the saved login anywhere but the college portal.
 */
public final class CaptivePortalActivity extends Activity {
 CaptivePortal portal;Network net;boolean done;Ui.Pal p;OrbView orb;TextView title,sub;final Handler h=new Handler(Looper.getMainLooper());
 final SharedPreferences.OnSharedPreferenceChangeListener l=(sp,key)->{if("status".equals(key))runOnUiThread(this::onStatus);};
 @Override protected void onCreate(Bundle b){super.onCreate(b);Intent i=getIntent();
  try{portal=i.getParcelableExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL,CaptivePortal.class);net=i.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK,Network.class);}
  catch(Throwable t){portal=i.getParcelableExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL);net=i.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK);}
  if(net==null||!Store.has(this)){handOff("no network or no saved login");return;}
  p=new Ui.Pal(Ui.isDark(this));build();Store.p(this).registerOnSharedPreferenceChangeListener(l);
  String portalUrl=null;try{portalUrl=i.getStringExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL_URL);}catch(Throwable ignored){}
  Brain.get(this).systemSignIn(net,portalUrl,matched->{if(!matched)handOff("not a saved Wi-Fi");});
  h.postDelayed(()->handOff("no sign-in within 35 s"),35000);}   // one-shot, only while this screen is open
 void build(){Window w=getWindow();w.setDecorFitsSystemWindows(false);w.setStatusBarColor(Color.TRANSPARENT);w.setNavigationBarColor(Color.TRANSPARENT);w.setBackgroundDrawable(new ColorDrawable(p.bg));
  w.getDecorView().post(()->{WindowInsetsController c=w.getInsetsController();if(c!=null){int light=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;c.setSystemBarsAppearance(p.dark?0:light,light);}});
  LinearLayout col=new LinearLayout(this);col.setOrientation(LinearLayout.VERTICAL);col.setGravity(Gravity.CENTER);col.setBackgroundColor(p.bg);col.setPadding(Ui.dp(this,32),0,Ui.dp(this,32),0);
  orb=new OrbView(this,p);orb.setState("SIGNING_IN",false);col.addView(orb,new LinearLayout.LayoutParams(Ui.dp(this,220),Ui.dp(this,220)));
  title=Ui.display(this,p,"Signing in");title.setGravity(Gravity.CENTER);col.addView(title,Ui.lp(-2,-2,36,this));
  sub=Ui.body(this,p,Store.ssid(this));sub.setGravity(Gravity.CENTER);col.addView(sub,Ui.lp(-2,-2,10,this));setContentView(col);}
 void onStatus(){if(done)return;String k=Session.kind(Store.status(this));
  if("CONNECTED".equals(k)){done=true;h.removeCallbacksAndMessages(null);try{if(portal!=null)portal.reportCaptivePortalDismissed();}catch(Exception ignored){}
   orb.setState("CONNECTED",true);title.setText("Connected");long ms=Store.p(this).getLong("last_signin_ms",-1);if(ms>=0)sub.setText("Signed in in "+String.format(java.util.Locale.US,"%.1f",ms/1000.0)+" s");h.postDelayed(this::finish,1100);}
  else if("ATTENTION".equals(k))handOff("college sign-in failed: "+Store.status(this));}
 /** Give the tap to the system's own captive-portal app (or the browser) with the original extras, then leave. */
 void handOff(String why){if(done)return;done=true;h.removeCallbacksAndMessages(null);Store.p(this).edit().putString("captive_handoff",why).apply();
  Intent src=getIntent();try{PackageManager pm=getPackageManager();ResolveInfo pick=null;
   for(ResolveInfo ri:pm.queryIntentActivities(new Intent(ConnectivityManager.ACTION_CAPTIVE_PORTAL_SIGN_IN),PackageManager.MATCH_ALL)){if(getPackageName().equals(ri.activityInfo.packageName))continue;
    if(pick==null||(ri.activityInfo.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM)!=0)pick=ri;}
   if(pick!=null){Intent fwd=new Intent(src);fwd.setComponent(new ComponentName(pick.activityInfo.packageName,pick.activityInfo.name));fwd.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(fwd);finish();return;}}catch(Exception ignored){}
  try{String url=src.getStringExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL_URL);if(url!=null){Intent v=new Intent(Intent.ACTION_VIEW,Uri.parse(url));v.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(v);}}catch(Exception ignored){}
  finish();}
 @Override protected void onDestroy(){h.removeCallbacksAndMessages(null);try{Store.p(this).unregisterOnSharedPreferenceChangeListener(l);}catch(Exception ignored){}super.onDestroy();}
}
