package bot.paradox.wifi;
import android.app.*;import android.content.*;import android.net.TrafficStats;import android.content.pm.ServiceInfo;import android.net.*;import android.os.*;
/**
 * User-started foreground service (silent, low-importance notification, START_STICKY).
 * Holds the Wi-Fi NetworkCallback and the screen-on receiver; all work happens on Brain's HandlerThread.
 * v4: NO polling. Reacts on onAvailable immediately, signs in the moment Android flags a captive portal
 * (the same signal behind "Sign in to Wi-Fi network"), re-checks once when Android drops VALIDATED, and once on
 * screen-on (at most every 2 min). Idle = zero timers, zero wakelocks; the process just holds this callback.
 */
public final class MonitorService extends Service {
 ConnectivityManager cm;ConnectivityManager.NetworkCallback callback;BroadcastReceiver screen;final java.util.Map<Network,Boolean> validated=new java.util.concurrent.ConcurrentHashMap<>(),captive=new java.util.concurrent.ConcurrentHashMap<>();
 static volatile MonitorService live;
 final Handler ui=new Handler(Looper.getMainLooper());boolean screenOn=true,ticking;long rx0=-1,tx0=-1,t0;String speed,shownTitle,shownText;
 /** v4.2: title = connectivity word only, text = live speed while the screen is on and we're on college Wi-Fi. Never portal message text. */
 static Notification notification(Context c,String title,String text){NotificationManager nm=(NotificationManager)c.getSystemService(NOTIFICATION_SERVICE);NotificationChannel channel=new NotificationChannel("wifi_monitor","Status & speed (silent)",NotificationManager.IMPORTANCE_MIN);channel.setSound(null,null);channel.enableVibration(false);channel.setShowBadge(false);nm.createNotificationChannel(channel);
  PendingIntent pi=PendingIntent.getActivity(c,0,new Intent(c,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  Notification.Builder b=new Notification.Builder(c,"wifi_monitor").setSmallIcon(LiveNote.offline(title)?R.drawable.ic_wifi_off:R.drawable.ic_wifi).setColor(0xff3f3f46).setContentTitle(title).setCategory(Notification.CATEGORY_SERVICE).setShowWhen(false).setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(true).setLocalOnly(true);
  if(text!=null)b.setContentText(text);
  if(Build.VERSION.SDK_INT>=31)b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);return b.build();}
 static void poke(){MonitorService m=live;if(m!=null){m.ui.removeCallbacks(m.refreshR);m.ui.postDelayed(m.refreshR,150);}}
 final Runnable refreshR=this::refresh,tickR=this::tick,portalR=()->{if(live==this)refresh();},usageR=()->UsageTracker.computeAsync(this,v->ui.post(()->{if(live==this)post(currentTitle(),Brain.get(this).network!=null?speed:null);}));
 /** v5.2: recompute today's usage (alerts, notification line) off the main thread, at most every few seconds. */
 void usageSoon(){ui.removeCallbacks(usageR);ui.postDelayed(usageR,3000);}
 String currentTitle(){Brain br=Brain.get(this);Network n=br.network;Boolean v=n==null?null:validated.get(n);return LiveNote.title(Session.kind(Store.status(this)),n!=null,v);}
 /** Main thread. Recomputes the title, starts/stops the 1 s speed ticker (only screen on + college Wi-Fi), reposts only on change. */
 void refresh(){if(live!=this)return;boolean onCollege=Brain.get(this).network!=null;UsageTracker.state(this,onCollege);usageSoon();
  if(onCollege&&screenOn){UsageTracker.portalIfDue(this,UsageTracker.PORTAL_EVERY_MS);ui.removeCallbacks(portalR);ui.postDelayed(portalR,UsageTracker.PORTAL_EVERY_MS+5000);}else ui.removeCallbacks(portalR);
 boolean want=screenOn&&onCollege&&TrafficStats.getTotalRxBytes()!=TrafficStats.UNSUPPORTED;
  if(want&&!ticking){ticking=true;rx0=TrafficStats.getTotalRxBytes();tx0=TrafficStats.getTotalTxBytes();t0=SystemClock.elapsedRealtime();speed=null;ui.postDelayed(tickR,1000);}
  else if(!want&&ticking){ticking=false;ui.removeCallbacks(tickR);speed=null;}
  post(currentTitle(),onCollege?speed:null);}
 void tick(){if(!ticking||live!=this)return;long rx=TrafficStats.getTotalRxBytes(),tx=TrafficStats.getTotalTxBytes(),t=SystemClock.elapsedRealtime();
  if(UsageTracker.sample(this,false))usageSoon();String sp=LiveNote.speedFrom(rx0,tx0,rx,tx,t-t0);rx0=rx;tx0=tx;t0=t;if(sp!=null)speed=sp;post(currentTitle(),speed);ui.postDelayed(tickR,1000);}
 void post(String title,String text){String line=LiveNote.line(title,text);String usage=UsageTracker.inNote(this)?UsageTracker.lastLine:null;String key=line+"|"+usage;if(key.equals(shownTitle))return;shownTitle=key;
  try{((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(100,notification(this,line,usage));}catch(Exception ignored){}}
 @Override public void onCreate(){super.onCreate();if(!Integrity.ok(this)){try{startForeground(100,notification(this,"College WiFi",null),ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);}catch(Exception ignored){}stopSelf();return;}
  shownTitle=currentTitle();try{startForeground(100,notification(this,shownTitle,null),ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);}catch(Exception e){Store.status(this,"Background monitor could not start. Open the app and check Location permissions.");stopSelf();return;}
  Brain.serviceAlive=true;live=this;screenOn=((PowerManager)getSystemService(POWER_SERVICE)).isInteractive();cm=Net.cm(this);register();poke();}
 @Override public int onStartCommand(Intent intent,int flags,int startId){if(!Store.enabled(this)){stopSelf();return START_NOT_STICKY;}if(callback==null)register();return START_STICKY;}
 void register(){if(cm==null||callback!=null||!Store.enabled(this))return;final Brain brain=Brain.get(this);
  try{callback=new ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO){
    @Override public void onAvailable(Network n){brain.wifi(n,null,SystemClock.elapsedRealtime());poke();}
    @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities caps){
     boolean v=caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);Boolean before=validated.put(n,v);
     brain.wifi(n,caps,SystemClock.elapsedRealtime()); // SSID often only readable here; no-op once matched
     boolean cp=caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);Boolean cpBefore=captive.put(n,cp);
     if(cp&&!Boolean.TRUE.equals(cpBefore))brain.captivePortal(n,"Android detected the login portal");
     else if(Boolean.TRUE.equals(before)&&!v)brain.validationLost(n,"Android lost internet validation");
     if(before==null||before!=v)poke();}
    @Override public void onLinkPropertiesChanged(Network n,LinkProperties lp){brain.linkChanged(n);}
    @Override public void onLost(Network n){validated.remove(n);captive.remove(n);brain.lost(n);poke();}};
   cm.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),callback);}
  catch(Exception e){Store.status(this,"Wi-Fi watcher couldn't register. Reopen the app to retry.");stopSelf();return;}
  screen=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){if(Intent.ACTION_SCREEN_OFF.equals(i.getAction())){screenOn=false;refresh();return;}screenOn=true;refresh();brain.screenOn();}};
  IntentFilter f=new IntentFilter(Intent.ACTION_SCREEN_ON);f.addAction(Intent.ACTION_USER_PRESENT);f.addAction(Intent.ACTION_SCREEN_OFF);
  try{if(Build.VERSION.SDK_INT>=33)registerReceiver(screen,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(screen,f);}catch(Exception ignored){screen=null;}}
 @Override public void onDestroy(){Brain.serviceAlive=false;if(live==this)live=null;ticking=false;ui.removeCallbacksAndMessages(null);if(callback!=null&&cm!=null)try{cm.unregisterNetworkCallback(callback);}catch(Exception ignored){}callback=null;if(screen!=null)try{unregisterReceiver(screen);}catch(Exception ignored){}screen=null;super.onDestroy();}
 @Override public IBinder onBind(Intent i){return null;}
}
