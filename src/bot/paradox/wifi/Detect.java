package bot.paradox.wifi;
import java.util.*;
/**
 * v5: find the captive portal on the current Wi-Fi with no hardcoded host. Probe generate_204 on the Wi-Fi network:
 * 204 = no portal (already online); 3xx = the Location is the portal; 200 with a page = follow its meta refresh or JS
 * redirect, or it IS the login page. Follow up to 6 hops, then fingerprint the final page. Pure Java.
 * (Android's own portal URL from the "Sign in to network" tap, EXTRA_CAPTIVE_PORTAL_URL, can be passed as the hint.)
 */
final class Detect {
 static String[] PROBES={"http://connectivitycheck.gstatic.com/generate_204","http://clients3.google.com/generate_204","http://captive.apple.com/hotspot-detect.html"};
 static final class Found { boolean online,reached;String loginUrl,html="",type,host,error;List<String> chain=new ArrayList<>();Set<String> hosts=new LinkedHashSet<>();
  boolean https(){return loginUrl!=null&&loginUrl.startsWith("https:");}
  String summary(){return online?"No portal: this Wi-Fi is already online":loginUrl==null?(error==null?"No portal found":error):Portal.name(type)+" at "+host;} }
 static Found run(Http h,String hint){Found f=new Found();
  List<String> starts=new ArrayList<>();if(hint!=null&&!hint.trim().isEmpty())starts.add(hint.trim());starts.addAll(Arrays.asList(PROBES));
  for(int si=0;si<starts.size();si++){String start=starts.get(si);boolean isHint=hint!=null&&!hint.trim().isEmpty()&&si==0;try{Http.Resp r=h.get(start);f.reached=true;f.chain.add(start);
    if(!isHint&&isOnline(start,r)){f.online=true;return f;}
    String url=start;int hops=6;
    while(hops-->0){String next=null;
     if(r.redirect())next=Http.resolve(r.url,r.location);
     else if(r.code==200&&!hasLoginForm(r.body,r.url)){String m=Html.metaRefresh(r.body);if(m==null)m=Html.jsRedirect(r.body);if(m!=null)next=Http.resolve(r.url,m);}
     if(next==null||f.chain.contains(next))break;f.chain.add(next);url=next;r=h.get(next);}
    if(r.code>=200&&r.code<500&&(r.code!=204)){f.loginUrl=url;f.html=r.body;f.type=Portal.fingerprint(url,r.body,f.chain);
     if(Portal.SOPHOS.equals(f.type)&&!url.contains("httpclient.html")){f.loginUrl=Http.origin(url)+"/httpclient.html";}
     f.host=Http.host(f.loginUrl);f.hosts.add(f.host);
     Html.Form lf=Drivers.loginForm(Html.forms(r.body,r.url),new Drivers.Net());if(lf!=null)f.hosts.add(Http.host(lf.action));
     if(Portal.OPNSENSE.equals(f.type)||Portal.SOPHOS.equals(f.type)||Portal.ARUBA.equals(f.type))f.hosts.add(Http.host(f.loginUrl));
     return f;}
   }catch(Exception e){f.error=e.getClass().getSimpleName().contains("SSL")?"The portal's security certificate isn't trusted":"Couldn't reach "+Http.host(start);if(isHint)continue;}
  }
  if(!f.reached&&f.error==null)f.error="No answer on this Wi-Fi";return f;}
 static boolean isOnline(String probe,Http.Resp r){if(probe.contains("generate_204"))return r.code==204;return r.code==200&&r.body.contains("<TITLE>Success</TITLE>")||r.body.trim().equalsIgnoreCase("<HTML><HEAD><TITLE>Success</TITLE></HEAD><BODY>Success</BODY></HTML>");}
 static boolean hasLoginForm(String html,String url){for(Html.Form f:Html.forms(html,url))if(f.hasPassword())return true;return false;}
}
