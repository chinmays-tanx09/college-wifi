package bot.paradox.wifi;
import android.content.Context;import android.net.Network;import android.os.Handler;import android.os.Looper;import android.webkit.*;
import org.json.JSONObject;import java.io.*;
/**
 * v5 FALLBACK for portals whose login form only exists after JavaScript runs. A hidden WebView, bound to the Wi-Fi
 * network, loads the saved portal page; requests to any host the user did not confirm for this network are blocked.
 * It finds the password field, the user field before it, ticks terms boxes, submits, then proves success the only
 * reliable way: generate_204 answering 204 on the same network. Result: "OK", "REFUSED:<page text>", or null.
 */
final class LoginEngine {
 interface Done { void result(String code); }
 final Context ctx;final Network network;final Store.NetProfile np;final Done done;final Handler h=new Handler(Looper.getMainLooper());
 WebView web;boolean ended,submitted;String user,pass;
 LoginEngine(Context c,Network n,Store.NetProfile np,String u,String p,Done d){ctx=c;network=n;this.np=np;user=u;pass=p;done=d;}
 boolean allowed(String url){String h=Http.host(url);return np.n.hosts.contains(h)||url.startsWith("data:")||url.startsWith("about:");}
 void start(){
  if(!Net.cm(ctx).bindProcessToNetwork(network)){finish(null);return;}
  try{web=new WebView(ctx);web.layout(0,0,1080,1800);WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setJavaScriptCanOpenWindowsAutomatically(false);s.setSaveFormData(false);
   web.setWebViewClient(new WebViewClient(){
    @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){if(submitted&&r.isForMainFrame())return !allowed(r.getUrl().toString())&&verifySoon();return !allowed(r.getUrl().toString());}
    @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){if(allowed(r.getUrl().toString()))return null;return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}
    @Override public void onReceivedSslError(WebView v,SslErrorHandler sh,android.net.http.SslError e){sh.cancel();finish(null);}
    @Override public void onPageFinished(WebView v,String url){if(ended)return;if(!submitted)fill(0);else verifySoon();}});
   web.loadUrl(np.n.loginUrl);h.postDelayed(()->{if(submitted)verify(0);else finish(null);},18000);}
  catch(Exception e){finish(null);}}
 boolean verifySoon(){h.postDelayed(()->verify(0),600);return true;}
 void fill(int attempt){if(ended||submitted||web==null)return;if(attempt>=24){finish(null);return;}
  String js="(function(){function vis(e){var r=e.getBoundingClientRect();return e.type!=='hidden'&&!e.disabled&&(r.width>0||r.height>0);}"+
   "var all=[...document.querySelectorAll('input')];var p=all.find(e=>e.type==='password'&&vis(e));if(!p)return 2;"+
   "var before=all.slice(0,all.indexOf(p)).filter(e=>vis(e)&&/^(text|email|tel|number|)$/.test(e.type));var re=/user|login|uname|email|uid|id|roll|account|mobile|phone|name/i;"+
   "var u=before.reverse().find(e=>re.test(e.name+' '+e.id+' '+e.placeholder))||before[0];if(!u)return 2;"+
   "function set(e,v){Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,v);e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}"+
   "set(u,"+JSONObject.quote(user)+");set(p,"+JSONObject.quote(pass)+");"+
   "all.filter(e=>e.type==='checkbox'&&!e.checked&&/accept|agree|terms|tos|policy|consent/i.test(e.name+' '+e.id+' '+((e.labels&&e.labels[0]&&e.labels[0].textContent)||''))).forEach(e=>e.click());"+
   "var f=p.form;var b=(f?[...f.querySelectorAll('button,input[type=submit],input[type=image]')]:[]).find(vis)||[...document.querySelectorAll('button,input[type=submit],a,[role=button]')].find(e=>vis(e)&&/log\\s*in|sign\\s*in|connect|submit|continue|login/i.test(e.textContent||e.value||''));"+
   "if(b){b.click();return 1;}if(f){f.requestSubmit?f.requestSubmit():f.submit();return 1;}return 3;})()";
  web.evaluateJavascript(js,v->{if(ended)return;if("1".equals(v)){submitted=true;user=null;pass=null;verifySoon();}else if("2".equals(v))h.postDelayed(()->fill(attempt+1),250);else finish(null);});}
 /** 204 on the Wi-Fi network = signed in. Otherwise read the page's visible notice as the portal's answer. */
 void verify(int n){if(ended)return;new Thread(()->{Portal.Live l=Drivers.probe(new Http(Net.opener(network,np.pins)).timeouts(1500,2500));h.post(()->{if(ended)return;
   if(l==Portal.Live.IN){finish("OK");return;}if(n<10){h.postDelayed(()->verify(n+1),500);return;}
   if(web==null){finish(null);return;}web.evaluateJavascript("(function(){var es=document.querySelectorAll('.error,.alert,.message,.msg,#error,#msg,#message,[role=alert],[role=status]');var t=[...es].map(e=>(e.textContent||'').trim()).find(Boolean);return t||document.body.innerText.slice(0,200);})()",v->finish("REFUSED:"+decode(v)));});}).start();}
 static String decode(String v){try{return new org.json.JSONArray("["+v+"]").getString(0).trim();}catch(Exception e){return "";}}
 void finish(String code){if(ended)return;ended=true;user=null;pass=null;if(web!=null){web.stopLoading();web.destroy();web=null;}h.removeCallbacksAndMessages(null);try{Net.cm(ctx).bindProcessToNetwork(null);}catch(Exception ignored){}done.result(code);}
}
