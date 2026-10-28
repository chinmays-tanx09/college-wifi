package bot.paradox.wifi;
import android.app.*;import android.content.*;import android.net.*;import android.net.Uri;import android.os.*;import android.provider.Settings;
import java.util.*;import java.util.concurrent.*;
/**
 * v5.2 daily data usage on the campus Wi-Fi. Everything stays on the phone (SharedPreferences "settings", keys u_*).
 *  - THIS PHONE: while the campus Wi-Fi is current the service records sessions (start/end). With Usage access granted
 *    the bytes come from Android's own Wi-Fi counter for exactly those times (NetworkStatsManager.querySummaryForDevice,
 *    TYPE_WIFI); without it, from whole-device TrafficStats deltas sampled by the service (approximate).
 *  - CAMPUS ACCOUNT (all devices on the login): the Sophos User Portal's daily-cycle table (UserPortalEngine), when it reads.
 *  - Remaining = portal allowance − portal used, else your own daily cap − this phone's use. Optional alerts at 80 % / 100 %.
 */
final class UsageTracker {
 static final int HISTORY=7;static final long FLUSH_MS=30_000,PORTAL_EVERY_MS=15*60_000L,PORTAL_ON_OPEN_MS=2*60_000L;
 static final ExecutorService BG=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"paradox-usage");t.setDaemon(true);return t;});
 static SharedPreferences p(Context c){return Store.p(c);}
 static TimeZone tz(){return TimeZone.getDefault();}
 static long now(){return System.currentTimeMillis();}
 // ---------- settings ----------
 static long cap(Context c){return p(c).getLong("u_cap",0);}
 static void cap(Context c,long v){p(c).edit().putLong("u_cap",Math.max(0,v)).putString("u_alert_day","").apply();}
 static boolean alerts(Context c){return p(c).getBoolean("u_alerts",true);}
 static boolean inNote(Context c){return p(c).getBoolean("u_in_note",false);}
 static boolean portalOn(Context c){return p(c).getBoolean("u_portal_on",true);}
 /** Usage access (the "special app access" Android needs before it shares its own Wi-Fi counter). */
 static boolean precise(Context c){try{AppOpsManager o=(AppOpsManager)c.getSystemService(Context.APP_OPS_SERVICE);int m=o.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),c.getPackageName());
   if(m==AppOpsManager.MODE_DEFAULT)return c.checkSelfPermission("android.permission.PACKAGE_USAGE_STATS")==android.content.pm.PackageManager.PERMISSION_GRANTED;return m==AppOpsManager.MODE_ALLOWED;}catch(Throwable t){return false;}}
 static void openUsageAccess(Activity a){try{a.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,Uri.parse("package:"+a.getPackageName())));}catch(Exception e){try{a.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));}catch(Exception ignored){}}}

 // ---------- sessions + TrafficStats sampling (main thread, from the service) ----------
 private static long rx0=-1,tx0=-1,ts0,pendD,pendU,pendT0,lastFlush;
 /** Called on every service refresh: the campus Wi-Fi is (not) current. */
 static synchronized void state(Context c,boolean onCampus){long t=now();Usage.Sessions s=Usage.Sessions.load(p(c).getString("u_sessions",null));boolean was=s.open();
  if(onCampus){if(!was){s.begin(t);baseline(t);}else sample(c,false);}
  else{if(was){sample(c,true);s.end(t);}rx0=tx0=-1;}
  if(was!=onCampus||!onCampus){s.prune(Usage.daysBack(t,HISTORY,tz()),96);p(c).edit().putString("u_sessions",s.save()).apply();}}
 private static void baseline(long t){rx0=android.net.TrafficStats.getTotalRxBytes();tx0=android.net.TrafficStats.getTotalTxBytes();ts0=t;}
 /** Take a counter reading; bytes since the last one count for the campus Wi-Fi. Written to disk every 30 s (or when forced). */
 static synchronized boolean sample(Context c,boolean force){long t=now();long rx=android.net.TrafficStats.getTotalRxBytes(),tx=android.net.TrafficStats.getTotalTxBytes();
  if(rx0>=0){long d=Usage.delta(rx0,rx),u=Usage.delta(tx0,tx);if(pendD==0&&pendU==0)pendT0=ts0;pendD+=d;pendU+=u;}
  rx0=rx;tx0=tx;ts0=t;if(rx<0)rx0=tx0=-1;
  if((force||t-lastFlush>=FLUSH_MS)&&(pendD>0||pendU>0)){Usage.Ledger l=Usage.Ledger.load(p(c).getString("u_ledger",null));l.add(pendT0,t,pendD,pendU,tz());l.prune(Usage.dayKey(t,tz()),HISTORY+1);
   pendD=pendU=0;lastFlush=t;p(c).edit().putString("u_ledger",l.save()).putLong("u_rev",t).apply();return true;}return false;}

 // ---------- what the screen shows ----------
 static final class View {
  String[] days;long[] down,up;boolean precise,fromPortal,portalStale;long phoneDown,phoneUp,used,cap,left;PortalUsage.Quota portal;long portalAt;String portalNote;
  long phone(){return phoneDown+phoneUp;}
  float fraction(){return Usage.fraction(cap,used);}
  String line(){if(fromPortal)return PortalUsage.line(portal);return Usage.line(used,cap);}
 }
 private static final Map<String,long[]> pastCache=new HashMap<>(); // precise totals for finished days: they don't change
 /** Any thread except main (binder calls). */
 static View compute(Context c){long t=now();TimeZone z=tz();View v=new View();v.days=Usage.lastDays(t,HISTORY,z);v.down=new long[HISTORY];v.up=new long[HISTORY];v.precise=precise(c);
  Usage.Ledger l=Usage.Ledger.load(p(c).getString("u_ledger",null));Usage.Sessions s=Usage.Sessions.load(p(c).getString("u_sessions",null));
  android.app.usage.NetworkStatsManager nsm=v.precise?(android.app.usage.NetworkStatsManager)c.getSystemService(Context.NETWORK_STATS_SERVICE):null;
  for(int i=0;i<HISTORY;i++){long[] got=null;
   if(nsm!=null){long from=Usage.daysBack(t,HISTORY-1-i,z),to=Usage.nextMidnight(from,z);boolean today=i==HISTORY-1;String key=v.days[i]+"|"+s.save().hashCode();
    got=today?null:pastCache.get(key);
    if(got==null){try{long d=0,u=0;for(long[] seg:s.clip(from,to,t)){android.app.usage.NetworkStats.Bucket b=nsm.querySummaryForDevice(ConnectivityManager.TYPE_WIFI,null,seg[0],seg[1]);if(b!=null){d+=b.getRxBytes();u+=b.getTxBytes();}}
      got=new long[]{d,u};if(!today)pastCache.put(key,got);}catch(Throwable e){got=null;v.precise=false;}}}
   if(got==null)got=l.get(v.days[i]);v.down[i]=got[0];v.up[i]=got[1];}
  v.phoneDown=v.down[HISTORY-1];v.phoneUp=v.up[HISTORY-1];
  // portal (campus account): today's daily cycle only
  PortalUsage.Quota q=PortalUsage.Quota.load(p(c).getString("u_portal",null));long at=p(c).getLong("u_portal_at",0);
  boolean current=q!=null&&(q.daily()?Usage.dayKey(at,z).equals(v.days[HISTORY-1]):t-at<Usage.DAY_MS);
  if(current&&portalOn(c)){v.portal=q;v.portalAt=at;v.fromPortal=true;v.used=q.used();v.cap=q.cap();v.left=q.left();v.portalStale=t-at>2*PORTAL_EVERY_MS;}
  else{v.used=v.phone();v.cap=cap(c);v.left=Usage.remaining(v.cap,v.used);}
  v.portalNote=p(c).getString("u_portal_err",null);
  lastLine=v.line();return v;}
 static volatile String lastLine;
 static void computeAsync(Context c,java.util.function.Consumer<View> done){final Context app=c.getApplicationContext();BG.execute(()->{View v;try{v=compute(app);}catch(Throwable e){return;}maybeAlert(app,v);if(done!=null)done.accept(v);});}

 // ---------- 80 % / 100 % alerts (once per level per day) ----------
 static void maybeAlert(Context c,View v){if(!alerts(c)||v.cap<=0)return;String today=v.days[HISTORY-1];SharedPreferences sp=p(c);int already=today.equals(sp.getString("u_alert_day",""))?sp.getInt("u_alert_lvl",0):0;
  int due=Usage.alertDue(v.cap,v.used,already);if(due==0)return;sp.edit().putString("u_alert_day",today).putInt("u_alert_lvl",due).apply();
  try{NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);NotificationChannel ch=new NotificationChannel("usage_alerts","Daily data alerts",NotificationManager.IMPORTANCE_DEFAULT);ch.setShowBadge(false);nm.createNotificationChannel(ch);
   PendingIntent pi=PendingIntent.getActivity(c,1,new Intent(c,MainActivity.class).putExtra("page","usage"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
   String title=due>=100?"Today's data is used up":"80% of today's data used";
   Notification n=new Notification.Builder(c,"usage_alerts").setSmallIcon(R.drawable.ic_wifi).setColor(0xff3f3f46).setContentTitle(title).setContentText(v.line()+(v.fromPortal?" · campus portal":" · this phone")).setAutoCancel(true).setContentIntent(pi).setLocalOnly(true).build();
   nm.notify(101,n);}catch(Exception ignored){}}

 // ---------- campus portal quota ----------
 /** Ask Brain to read the user portal if it's due (on its own portal thread, never during a sign-in). */
 static void portalIfDue(Context c,long maxAge){if(!portalOn(c))return;long at=p(c).getLong("u_portal_try",0);if(now()-at<maxAge)return;Brain.get(c).portalQuota();}
 static void portalResult(Context c,String page,String err){long t=now();SharedPreferences.Editor e=p(c).edit().putLong("u_portal_try",t);PortalUsage.Quota q=page==null?null:PortalUsage.parse(page);
  if(q!=null){e.putString("u_portal",q.save()).putLong("u_portal_at",t).remove("u_portal_err");}
  else e.putString("u_portal_err",err!=null?err:page==null?"no answer":"page read, but no daily-cycle table on it");
  e.putLong("u_rev",t).apply();}
}
