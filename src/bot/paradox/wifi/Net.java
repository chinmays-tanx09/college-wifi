package bot.paradox.wifi;
import android.content.*;import android.net.*;import android.net.wifi.*;import android.location.LocationManager;import java.net.*;import java.io.*;import java.security.MessageDigest;import java.security.cert.*;import javax.net.ssl.*;
final class Net {
 static ConnectivityManager cm(Context c){return (ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);}
 static boolean locationOn(Context c){LocationManager l=(LocationManager)c.getSystemService(Context.LOCATION_SERVICE);return l!=null&&l.isLocationEnabled();}
 static String clean(String s){if(s==null||s.equals(WifiManager.UNKNOWN_SSID)||s.equals("<unknown ssid>")||s.isEmpty())return null;return s.replaceAll("^\\\"|\\\"$","");}
 static String ssid(Context c,Network n){return ssid(c,n,null);}
 /** SSID from the callback's own capabilities first (carries location info with FLAG_INCLUDE_LOCATION_INFO), then system lookups. */
 static String ssid(Context c,Network n,NetworkCapabilities given){try{
   if(given!=null&&given.getTransportInfo() instanceof WifiInfo){String s=clean(((WifiInfo)given.getTransportInfo()).getSSID());if(s!=null)return s;}
   NetworkCapabilities caps=cm(c).getNetworkCapabilities(n);if(caps==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return null;
   String s=caps.getTransportInfo() instanceof WifiInfo?clean(((WifiInfo)caps.getTransportInfo()).getSSID()):null;
   if(s==null){WifiManager wm=(WifiManager)c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);WifiInfo current=wm==null?null:wm.getConnectionInfo();s=current==null?null:clean(current.getSSID());}
   return s;}catch(SecurityException e){return null;}}
 /** The Wi-Fi network in use (whatever its name), or null. */
 static Network currentWifi(Context c){for(Network n:cm(c).getAllNetworks()){NetworkCapabilities caps=cm(c).getNetworkCapabilities(n);if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return n;}return null;}
 /** A connected Wi-Fi whose SSID is one of the saved networks. */
 static Network matching(Context c){for(Network n:cm(c).getAllNetworks()){String s=ssid(c,n);if(s!=null&&Store.net(c,s)!=null)return n;}return null;}
 /** Connections bound to this Wi-Fi network (never mobile data). HTTPS to a host the user pinned trusts exactly that certificate. */
 static Http.Opener opener(Network n,java.util.Map<String,String> pins){return u->{URLConnection c=n.openConnection(u);
   if(c instanceof HttpsURLConnection&&pins!=null&&pins.containsKey(u.getHost().toLowerCase(java.util.Locale.ROOT))){String want=pins.get(u.getHost().toLowerCase(java.util.Locale.ROOT));
    try{SSLContext ctx=SSLContext.getInstance("TLS");ctx.init(null,new TrustManager[]{new Pin(want)},null);((HttpsURLConnection)c).setSSLSocketFactory(ctx.getSocketFactory());((HttpsURLConnection)c).setHostnameVerifier((h,s)->true);}catch(Exception e){throw new IOException(e);}}
   return c;};}
 static String sha256(byte[] b)throws Exception{StringBuilder s=new StringBuilder();for(byte x:MessageDigest.getInstance("SHA-256").digest(b)){if(s.length()>0)s.append(':');s.append(String.format("%02X",x&0xff));}return s.toString();}
 /** Trust-on-first-use: accept only the one certificate the user approved for this portal. */
 static final class Pin implements X509TrustManager { final String want;Pin(String w){want=w;}
  public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{throw new CertificateException();}
  public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException{try{if(c==null||c.length==0||!sha256(c[0].getEncoded()).equals(want))throw new CertificateException("certificate changed");}catch(CertificateException e){throw e;}catch(Exception e){throw new CertificateException(e);}}
  public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];} }
 /** Read (only read) the certificate a portal presents, to show its fingerprint before the user decides. Sends no request body and no credentials. */
 static String fingerprint(Network n,String url){HttpsURLConnection c=null;try{c=(HttpsURLConnection)n.openConnection(new URL(url));final String[] seen={null};
   SSLContext ctx=SSLContext.getInstance("TLS");ctx.init(null,new TrustManager[]{new X509TrustManager(){public void checkClientTrusted(X509Certificate[] x,String a){}
     public void checkServerTrusted(X509Certificate[] x,String a)throws CertificateException{try{seen[0]=sha256(x[0].getEncoded());}catch(Exception e){}throw new CertificateException("read only");}public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}}},null);
   c.setSSLSocketFactory(ctx.getSocketFactory());c.setConnectTimeout(3000);c.setReadTimeout(3000);try{c.connect();}catch(IOException expected){}return seen[0];}catch(Exception e){return null;}finally{if(c!=null)c.disconnect();}}
}
