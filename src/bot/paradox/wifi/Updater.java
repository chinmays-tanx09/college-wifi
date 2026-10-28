package bot.paradox.wifi;
import android.app.*;import android.content.*;import android.content.pm.*;import android.net.Uri;import android.os.*;import android.provider.Settings;
import java.io.*;import java.net.*;import java.security.MessageDigest;import java.util.*;
/**
 * v5 in-app updater. No polling: checked on app open and at most once a day right after a successful sign-in (the
 * internet is up then). A newer release posts one notification on the "Updates" channel and a card on the main screen.
 * Install: HTTPS download to app cache → SHA-256 (when published) → the APK must be this package, newer, and signed by
 * the same certificate as the installed app → PackageInstaller (the user confirms). Anything else is refused.
 */
final class Updater {
 interface Progress { void on(int percent,String status,boolean done,boolean ok); }
 static final long DAY=24*3600_000L;
 static SharedPreferences sp(Context c){return Store.p(c);}
 static int myCode(Context c){try{return (int)c.getPackageManager().getPackageInfo(c.getPackageName(),0).getLongVersionCode();}catch(Exception e){return 0;}}
 static String myName(Context c){try{return c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName;}catch(Exception e){return "";}}
 static boolean due(Context c){return System.currentTimeMillis()-sp(c).getLong("upd_checked",0)>DAY;}
 static void checkSoon(Context c){final Context a=c.getApplicationContext();new Thread(()->check(a,false),"paradox-update").start();}
 /** Returns a human line for the UI ("You're up to date", "Version 5.1 available", or why it couldn't check). */
 static String check(Context c,boolean manual){sp(c).edit().putLong("upd_checked",System.currentTimeMillis()).apply();HttpURLConnection h=null;
  try{h=(HttpURLConnection)new URL(Release.UPDATE_URL).openConnection();h.setConnectTimeout(6000);h.setReadTimeout(8000);h.setRequestProperty("Accept","application/vnd.github+json");h.setRequestProperty("User-Agent","ParadoxWiFi");
   int code=h.getResponseCode();if(code==404){clear(c);return "No releases published yet";}if(code!=200)return "Couldn't check for updates (HTTP "+code+")";
   String body=Http.read(h,code);Release r=Release.parse(body);if(r==null){clear(c);return "No installable release found";}
   if(!r.newerThan(myCode(c),myName(c))){clear(c);return "You're up to date ("+myName(c)+")";}
   String key=r.versionCode+"/"+r.versionName;boolean skipped=key.equals(sp(c).getString("upd_skip",""))&&!r.mandatory;
   sp(c).edit().putString("upd_name",r.versionName).putInt("upd_code",r.versionCode).putString("upd_url",r.apkUrl).putString("upd_sha",r.sha256).putString("upd_log",r.changelog).putBoolean("upd_mandatory",r.mandatory).putString("upd_key",key).apply();
   if(!skipped&&(manual||!key.equals(sp(c).getString("upd_notified",""))))notifyAvailable(c,r);return "Version "+r.versionName+" available";}
  catch(Exception e){return "Couldn't check for updates (no internet?)";}finally{if(h!=null)h.disconnect();}}
 static void clear(Context c){sp(c).edit().remove("upd_name").remove("upd_code").remove("upd_url").remove("upd_sha").remove("upd_log").remove("upd_mandatory").remove("upd_key").apply();}
 static boolean available(Context c){String k=sp(c).getString("upd_key",null);if(k==null)return false;return sp(c).getBoolean("upd_mandatory",false)||!k.equals(sp(c).getString("upd_skip",""));}
 static void skip(Context c){sp(c).edit().putString("upd_skip",sp(c).getString("upd_key","")).apply();((NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(77);}
 static void notifyAvailable(Context c,Release r){sp(c).edit().putString("upd_notified",r.versionCode+"/"+r.versionName).apply();
  NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);nm.createNotificationChannel(new NotificationChannel("updates","Updates",NotificationManager.IMPORTANCE_DEFAULT));
  PendingIntent pi=PendingIntent.getActivity(c,7,new Intent(c,MainActivity.class).putExtra("update",true),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  try{nm.notify(77,new Notification.Builder(c,"updates").setSmallIcon(R.drawable.ic_wifi).setColor(0xff3f3f46).setContentTitle("College WiFi "+r.versionName+" available").setContentText("Tap to update").setContentIntent(pi).setAutoCancel(true).build());}catch(SecurityException ignored){}}

 /** Download, verify, hand to PackageInstaller. Runs on its own thread; progress comes back on the main thread. */
 static void install(Activity a,Progress p){final Handler ui=new Handler(Looper.getMainLooper());final Context c=a.getApplicationContext();
  if(!c.getPackageManager().canRequestPackageInstalls()){ui.post(()->p.on(0,"Allow installs from College WiFi, then tap Update again",true,false));
   try{a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+c.getPackageName())));}catch(Exception ignored){}return;}
  new Thread(()->{File f=new File(c.getCacheDir(),"update.apk");try{String url=sp(c).getString("upd_url","");if(!url.startsWith("https://"))throw new IOException("update link isn't HTTPS");
    String sha=download(url,f,pct->ui.post(()->p.on(pct,"Downloading… "+pct+"%",false,false)));
    String want=sp(c).getString("upd_sha","");if(!want.isEmpty()&&!want.equalsIgnoreCase(sha))throw new IOException("download doesn't match the published checksum");
    String why=verify(c,f);if(why!=null)throw new IOException(why);
    ui.post(()->p.on(100,"Verified · opening installer",false,false));commit(c,f);ui.post(()->p.on(100,"Confirm the install on the next screen",true,true));}
   catch(Exception e){f.delete();String m=e.getMessage()==null?"download failed":e.getMessage();ui.post(()->p.on(0,"Update refused: "+m,true,false));}},"paradox-update-dl").start();}
 interface Pct { void on(int pct); }
 static String download(String url,File out,Pct pct)throws Exception{HttpURLConnection h=(HttpURLConnection)new URL(url).openConnection();h.setInstanceFollowRedirects(true);h.setConnectTimeout(10000);h.setReadTimeout(20000);h.setRequestProperty("User-Agent","ParadoxWiFi");
  try{int code=h.getResponseCode();if(code!=200)throw new IOException("download answered HTTP "+code);if(!h.getURL().getProtocol().equals("https"))throw new IOException("download was redirected off HTTPS");
   long len=h.getContentLengthLong();MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=h.getInputStream();OutputStream o=new FileOutputStream(out)){byte[] b=new byte[65536];long got=0;int n,last=-1;
    while((n=in.read(b))>0){o.write(b,0,n);md.update(b,0,n);got+=n;if(got>64L*1024*1024)throw new IOException("file too large");int p=len>0?(int)(got*100/len):0;if(p!=last){last=p;pct.on(p);}}}
   StringBuilder s=new StringBuilder();for(byte x:md.digest())s.append(String.format("%02x",x&0xff));return s.toString();}finally{h.disconnect();}}
 static Set<String> signers(SigningInfo si){Set<String> out=new HashSet<>();if(si==null)return out;Signature[] s=si.hasMultipleSigners()?si.getApkContentsSigners():si.getSigningCertificateHistory();if(s==null)return out;
  for(Signature x:s)try{out.add(Net.sha256(x.toByteArray()));}catch(Exception ignored){}if(!si.hasMultipleSigners()&&s.length>0){out.clear();try{out.add(Net.sha256(s[0].toByteArray()));}catch(Exception ignored){}}return out;}
 /** null = OK to install; else the reason it is refused. */
 static String verify(Context c,File f){PackageManager pm=c.getPackageManager();PackageInfo theirs=pm.getPackageArchiveInfo(f.getPath(),PackageManager.GET_SIGNING_CERTIFICATES);
  if(theirs==null)return "the download isn't a valid app";if(!c.getPackageName().equals(theirs.packageName))return "it's a different app ("+theirs.packageName+")";
  if(theirs.getLongVersionCode()<=myCode(c))return "it isn't newer than the installed version";
  try{PackageInfo ours=pm.getPackageInfo(c.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);if(!Release.sameSigner(signers(ours.signingInfo),signers(theirs.signingInfo)))return "it isn't signed by the same developer key";}catch(Exception e){return "couldn't read signatures";}
  return null;}
 static void commit(Context c,File f)throws Exception{PackageInstaller pi=c.getPackageManager().getPackageInstaller();PackageInstaller.SessionParams sp=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);sp.setAppPackageName(c.getPackageName());
  if(Build.VERSION.SDK_INT>=31)sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);int id=pi.createSession(sp);
  try(PackageInstaller.Session s=pi.openSession(id)){try(OutputStream o=s.openWrite("base.apk",0,f.length());InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))>0)o.write(b,0,n);s.fsync(o);}
   PendingIntent done=PendingIntent.getBroadcast(c,id,new Intent(c,UpdateReceiver.class).setAction("bot.paradox.wifi.INSTALL"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);s.commit(done.getIntentSender());}}
}
