package bot.paradox.wifi;
import android.content.Context;import android.net.Network;import android.os.Handler;import android.os.Looper;import android.webkit.*;
import org.json.JSONObject;import java.io.*;
/**
 * v5.2: reads "Internet usage" from the campus Sophos User Portal (https://<portal host>:4443/userportal/) with the
 * account the app already signs in with. A hidden WebView, bound to the campus Wi-Fi, may talk to the portal host only.
 * The portal's self-signed certificate is trusted for that host alone, pinned on first use (a changed certificate
 * stops it). It signs in like a person would, opens "Internet usage", waits for the daily-cycle table and hands the
 * page to PortalUsage. Never clicks Logout. Built from a screenshot of the page; UNTESTED against the live portal.
 * Result: (page HTML, null) or (null, reason).
 */
final class UserPortalEngine {
 interface Done { void result(String page,String err); }
 static final int PORT=4443;static final long DEADLINE_MS=28_000;
 final Context ctx;final Network network;final String host;final Done done;final Handler h=new Handler(Looper.getMainLooper());
 WebView web;boolean ended,submitted,navigated,chain;final Runnable stepR=this::step;String user,pass;long submittedAt;int polls;
 UserPortalEngine(Context c,Network n,String host,String u,String p,Done d){ctx=c;network=n;this.host=host;user=u;pass=p;done=d;}
 static String url(String host){return "https://"+host+":"+PORT+"/userportal/";}
 boolean allowed(String url){return host.equalsIgnoreCase(Http.host(url))||url.startsWith("about:")||url.startsWith("data:");}
 void start(){
  if(network!=null&&!Net.cm(ctx).bindProcessToNetwork(network)){finish(null,"couldn't use the campus Wi-Fi");return;}
  try{web=new WebView(ctx);web.layout(0,0,1080,2200);WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setJavaScriptCanOpenWindowsAutomatically(false);s.setSaveFormData(false);
   web.setWebViewClient(new WebViewClient(){
    @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return !allowed(r.getUrl().toString());}
    @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){if(allowed(r.getUrl().toString()))return null;return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}
    @Override public void onReceivedSslError(WebView v,SslErrorHandler sh,android.net.http.SslError e){
     try{if(!host.equalsIgnoreCase(Http.host(e.getUrl()))){sh.cancel();finish(null,"certificate from another host");return;}
      java.security.cert.X509Certificate x=e.getCertificate().getX509Certificate();String fp=x==null?null:Net.sha256(x.getEncoded());if(fp==null){sh.cancel();finish(null,"no certificate");return;}
      String pin=Store.p(ctx).getString("u_portal_pin",null);
      if(pin==null){Store.p(ctx).edit().putString("u_portal_pin",fp).apply();sh.proceed();return;} // first use: remember this portal's certificate
      if(pin.equals(fp)){sh.proceed();return;}
      sh.cancel();finish(null,"the user portal's certificate changed · not sending your login");}catch(Exception ex){sh.cancel();finish(null,"certificate check failed");}}
    @Override public void onPageFinished(WebView v,String url){if(!ended&&!chain){chain=true;h.post(stepR);}}});
   web.loadUrl(url(host));h.postDelayed(()->finish(null,submitted?"signed in, but the usage table never showed":"the user portal didn't load"),DEADLINE_MS);}
  catch(Exception e){finish(null,"couldn't open the user portal");}}
 /** One look at the page. The portal is a JavaScript app, so also polled every 0.7 s until the deadline. */
 void step(){if(ended||web==null)return;polls++;
  String js="(function(){function vis(e){var r=e.getBoundingClientRect();return e.type!=='hidden'&&!e.disabled&&(r.width>0||r.height>0);}"+
   "var t=(document.body&&document.body.innerText||'').toLowerCase().replace(/[^a-z]/g,'');"+
   "if(t.indexOf('cycletotalnetworktraffic')>=0)return 'DATA:'+document.documentElement.outerHTML;"+
   "var all=[...document.querySelectorAll('input')];var p=all.find(e=>e.type==='password'&&vis(e));"+
   "if(p){if("+submitted+")return 'LOGIN:'+([...document.querySelectorAll('.error,.alert,.message,.msg,#msg,#message,[role=alert],.errorMessage,#errorMessage')].map(e=>(e.textContent||'').trim()).find(Boolean)||'');"+
   " var before=all.slice(0,all.indexOf(p)).filter(e=>vis(e)&&/^(text|email|tel|number|)$/.test(e.type));var re=/user|login|uname|email|uid|id|roll|name/i;"+
   " var u=before.reverse().find(e=>re.test(e.name+' '+e.id+' '+e.placeholder))||before[0];if(!u)return 'WAIT';"+
   " function set(e,v){Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,v);e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}"+
   " set(u,"+JSONObject.quote(user==null?"":user)+");set(p,"+JSONObject.quote(pass==null?"":pass)+");"+
   " var f=p.form;var b=(f?[...f.querySelectorAll('button,input[type=submit],input[type=button],input[type=image]')]:[]).find(vis)||[...document.querySelectorAll('button,input[type=submit],input[type=button],a,[role=button]')].find(e=>vis(e)&&/log\\s*in|sign\\s*in|submit|login/i.test(e.textContent||e.value||''));"+
   " if(b){b.click();return 'SUBMITTED';}if(f){f.requestSubmit?f.requestSubmit():f.submit();return 'SUBMITTED';}return 'WAIT';}"+
   "if(!"+navigated+"){var m=[...document.querySelectorAll('a,li,span,div,td,button')].filter(e=>vis(e)&&/^\\s*internet\\s*usage\\s*$/i.test(e.textContent||''));"+
   " if(m.length){var e=m[m.length-1];var a=e.closest('a')||e;a.click();return 'NAV';}}"+
   "return 'WAIT';})()";
  if(submitted&&!navigated&&polls>200){finish(null,"stuck");return;}
  web.evaluateJavascript(js,v->{if(ended)return;String r=LoginEngine.decode(v);
   if(r.startsWith("DATA:")){finish(r.substring(5),null);return;}
   if(r.equals("SUBMITTED")){submitted=true;submittedAt=System.currentTimeMillis();user=null;pass=null;}
   else if(r.equals("NAV"))navigated=true;
   else if(r.startsWith("LOGIN:")&&System.currentTimeMillis()-submittedAt>4000){String m=r.substring(6);finish(null,"the user portal refused the login"+(m.isEmpty()?"":": "+(m.length()>80?m.substring(0,80):m)));return;}
   h.removeCallbacks(stepR);h.postDelayed(stepR,700);});}
 void finish(String page,String err){if(ended)return;ended=true;user=null;pass=null;if(web!=null){web.stopLoading();web.destroy();web=null;}h.removeCallbacksAndMessages(null);if(network!=null)try{Net.cm(ctx).bindProcessToNetwork(null);}catch(Exception ignored){}done.result(page,err);}
}
