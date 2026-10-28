package bot.paradox.wifi;
import android.content.*;import android.security.keystore.*;import android.util.Base64;
import org.json.*;
import java.nio.charset.StandardCharsets;import java.security.KeyStore;import java.util.*;import javax.crypto.*;import javax.crypto.spec.GCMParameterSpec;
/**
 * v5: saved networks (SSID → detected portal, allowed hosts, learned recipe) each with several accounts.
 * Usernames and passwords are AES-GCM encrypted with a key that never leaves Android Keystore; each field is bound
 * to its account id (AAD), so ciphertexts can't be swapped. No backup. v4's single login migrates on first read.
 */
final class Store {
 static final String SSID_DEFAULT="IIIT DHARWAD-STUDENT", V4_PORTAL="http://172.16.16.16:8090/httpclient.html", V4_HOST="172.16.16.16";
 private static final String KEY="paradox_college_wifi_v1";
 static SharedPreferences p(Context c){return c.getSharedPreferences("settings",0);}
 static boolean enabled(Context c){return p(c).getBoolean("enabled",false);}
 static void enable(Context c,boolean b){p(c).edit().putBoolean("enabled",b).apply();}
 static void status(Context c,String s){p(c).edit().putString("status",s).putLong("status_time",System.currentTimeMillis()).apply();}
 static String status(Context c){return p(c).getString("status","Waiting for setup");}

 // ---------- one saved network ----------
 static final class NetProfile { final Drivers.Net n=new Drivers.Net();final List<Accounts.Account> accounts=new ArrayList<>();final Map<String,String[]> sealed=new HashMap<>();
  boolean httpAck;final Map<String,String> pins=new HashMap<>();long added;
  String ssid(){return n.ssid;} String portalName(){return Portal.name(n.type);} String host(){return Http.host(n.loginUrl);} boolean plainHttp(){return n.loginUrl.startsWith("http:");} }
 private static List<NetProfile> cache;
 static synchronized List<NetProfile> nets(Context c){if(cache!=null)return cache;migrate(c);List<NetProfile> out=new ArrayList<>();
  try{JSONArray a=new JSONArray(p(c).getString("nets","[]"));for(int i=0;i<a.length();i++)out.add(fromJson(a.getJSONObject(i)));}catch(Exception ignored){}
  cache=out;return out;}
 static synchronized NetProfile net(Context c,String ssid){if(ssid==null)return null;for(NetProfile n:nets(c))if(n.n.ssid.equals(ssid))return n;return null;}
 static synchronized NetProfile byHost(Context c,String host){for(NetProfile n:nets(c))if(n.n.hosts.contains(host))return n;return null;}
 static synchronized void saveNet(Context c,NetProfile np){List<NetProfile> l=nets(c);boolean found=false;for(int i=0;i<l.size();i++)if(l.get(i)==np||l.get(i).n.ssid.equals(np.n.ssid)){l.set(i,np);found=true;}if(!found){np.added=System.currentTimeMillis();l.add(np);}persist(c);}
 static synchronized void removeNet(Context c,String ssid){Iterator<NetProfile> it=nets(c).iterator();while(it.hasNext())if(it.next().n.ssid.equals(ssid))it.remove();persist(c);}
 private static void persist(Context c){JSONArray a=new JSONArray();for(NetProfile n:cache)try{a.put(toJson(n));}catch(Exception ignored){}p(c).edit().putString("nets",a.toString()).apply();}
 static boolean has(Context c){for(NetProfile n:nets(c))if(!n.accounts.isEmpty())return true;return false;}
 /** The network the UI talks about: the one in use, else the first saved one. */
 static String ssid(Context c){String s=p(c).getString("current_ssid",null);if(s!=null&&net(c,s)!=null)return s;List<NetProfile> l=nets(c);return l.isEmpty()?"your college Wi-Fi":l.get(0).n.ssid;}
 static NetProfile current(Context c){return net(c,ssid(c));}

 // ---------- accounts ----------
 static Accounts.Account addAccount(Context c,NetProfile np,String user,String pass)throws Exception{Accounts.Account a=new Accounts.Account(UUID.randomUUID().toString().substring(0,8),user);seal(np,a,user,pass);np.accounts.add(a);saveNet(c,np);return a;}
 static void updateAccount(Context c,NetProfile np,Accounts.Account a,String user,String pass)throws Exception{String[] old=np.sealed.get(a.id);String pw=pass;if(pw==null||pw.isEmpty()){String[] cr=creds(np,a);if(cr==null)throw new IllegalStateException();pw=cr[1];}
  a.user=user;a.state="";a.message="";a.until=0;seal(np,a,user,pw);saveNet(c,np);}
 static void seal(NetProfile np,Accounts.Account a,String user,String pass)throws Exception{np.sealed.put(a.id,new String[]{encrypt("acct:"+a.id+":u",user),encrypt("acct:"+a.id+":p",pass)});}
 /** {user, password}; the caller wipes the array after use. Null if the Keystore key is gone (e.g. after a restore). */
 static String[] creds(NetProfile np,Accounts.Account a){String[] s=np.sealed.get(a.id);if(s==null)return null;try{return new String[]{decrypt("acct:"+a.id+":u",s[0]),decrypt("acct:"+a.id+":p",s[1])};}catch(Exception e){return null;}}

 // ---------- JSON (no secrets in clear) ----------
 static JSONObject toJson(NetProfile np)throws JSONException{JSONObject o=new JSONObject();Drivers.Net n=np.n;o.put("ssid",n.ssid).put("type",n.type).put("loginUrl",n.loginUrl).put("base",n.base).put("httpAck",np.httpAck).put("added",np.added);
  o.put("hosts",new JSONArray(n.hosts));try{o.put("recipeEnc",encrypt("recipe:"+n.ssid,new JSONObject(n.recipe).toString()));}catch(Exception e){/* recipe is re-learned on the next sign-in */}o.put("pins",new JSONObject(np.pins));JSONArray ac=new JSONArray();
  for(Accounts.Account a:np.accounts){String[] s=np.sealed.get(a.id);JSONObject j=new JSONObject().put("id",a.id).put("state",a.state).put("message",a.message).put("stateAt",a.stateAt).put("until",a.until);if(s!=null)j.put("u",s[0]).put("p",s[1]);ac.put(j);}
  o.put("accounts",ac);return o;}
 static NetProfile fromJson(JSONObject o)throws JSONException{NetProfile np=new NetProfile();Drivers.Net n=np.n;n.ssid=o.optString("ssid");n.type=o.optString("type",Portal.GENERIC);n.loginUrl=o.optString("loginUrl");n.base=o.optString("base");np.httpAck=o.optBoolean("httpAck");np.added=o.optLong("added");
  JSONArray h=o.optJSONArray("hosts");if(h!=null)for(int i=0;i<h.length();i++)n.hosts.add(h.getString(i));
  JSONObject r=null;try{if(o.has("recipeEnc"))r=new JSONObject(decrypt("recipe:"+n.ssid,o.optString("recipeEnc")));}catch(Exception ignored){}if(r==null)r=o.optJSONObject("recipe");if(r!=null){Iterator<String> k=r.keys();while(k.hasNext()){String key=k.next();n.recipe.put(key,r.optString(key));}}
  JSONObject pi=o.optJSONObject("pins");if(pi!=null){Iterator<String> k=pi.keys();while(k.hasNext()){String key=k.next();np.pins.put(key,pi.optString(key));}}
  JSONArray ac=o.optJSONArray("accounts");if(ac!=null)for(int i=0;i<ac.length();i++){JSONObject j=ac.getJSONObject(i);String id=j.optString("id");String user="?";
   try{user=decrypt("acct:"+id+":u",j.optString("u"));}catch(Exception ignored){if(MainActivity.PREVIEW)user=j.optString("preview_u","?");}Accounts.Account a=new Accounts.Account(id,user);a.state=j.optString("state");a.message=j.optString("message");a.stateAt=j.optLong("stateAt");a.until=j.optLong("until");
   np.accounts.add(a);np.sealed.put(id,new String[]{j.optString("u"),j.optString("p")});}
  return np;}

 /** v4 → v5: the single saved login becomes account 1 of the IIIT Dharwad network (Sophos at 172.16.16.16:8090). */
 private static void migrate(Context c){SharedPreferences sp=p(c);if(sp.contains("nets")||!sp.contains("u")||!sp.contains("p"))return;
  try{String user=decrypt("username",sp.getString("u","")),pass=decrypt("password",sp.getString("p",""));NetProfile np=new NetProfile();
   np.n.ssid=sp.getString("ssid",SSID_DEFAULT);np.n.type=Portal.SOPHOS;np.n.loginUrl=V4_PORTAL;np.n.base="http://172.16.16.16:8090";np.n.hosts.add(V4_HOST);np.httpAck=true;np.added=System.currentTimeMillis();
   Accounts.Account a=new Accounts.Account("a1",user);seal(np,a,user,pass);np.accounts.add(a);JSONArray arr=new JSONArray();arr.put(toJson(np));
   sp.edit().putString("nets",arr.toString()).putString("current_ssid",np.n.ssid).remove("u").remove("p").putString("migrated_v4","1").apply();pass=null;}
  catch(Exception e){sp.edit().putString("nets","[]").putString("migration_failed","1").apply();}}

 private static SecretKey key() throws Exception {KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);if(ks.containsAlias(KEY))return ((KeyStore.SecretKeyEntry)ks.getEntry(KEY,null)).getSecretKey();KeyGenerator g=KeyGenerator.getInstance("AES","AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(KEY,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());return g.generateKey();}
 private static String encrypt(String field,String value)throws Exception{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(field.getBytes(StandardCharsets.UTF_8));byte[] iv=cipher.getIV(), ct=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));return Base64.encodeToString(iv,Base64.NO_WRAP)+":"+Base64.encodeToString(ct,Base64.NO_WRAP);}
 private static String decrypt(String field,String value)throws Exception{String[] a=value.split(":",2);if(a.length!=2)throw new IllegalArgumentException();Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(a[0],Base64.NO_WRAP)));cipher.updateAAD(field.getBytes(StandardCharsets.UTF_8));return new String(cipher.doFinal(Base64.decode(a[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);}
 /** "Remove everything": all networks, accounts, timelines and the Keystore key. */
 static synchronized void clear(Context c){enable(c,false);cache=null;p(c).edit().remove("nets").remove("current_ssid").remove("active_id").remove("active_user").remove("diagnostics").remove("timeline").remove("logout_timeline").remove("suppressed_net").remove("last_check").remove("last_check_time").remove("last_signin_ms").remove("match_note").apply();
  try{KeyStore k=KeyStore.getInstance("AndroidKeyStore");k.load(null);k.deleteEntry(KEY);}catch(Exception ignored){}try{android.webkit.CookieManager.getInstance().removeAllCookies(null);android.webkit.WebStorage.getInstance().deleteAllData();}catch(Exception ignored){}}
 /** For the screenshot renderer: drop the in-memory cache so a test can rewrite prefs. */
 static synchronized void resetCache(){cache=null;}
}
