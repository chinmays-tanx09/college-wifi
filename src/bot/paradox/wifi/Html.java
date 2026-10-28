package bot.paradox.wifi;
import java.util.*;import java.util.regex.*;
/** Tolerant, dependency-free HTML reading for portal pages: forms, inputs, meta refresh, JS redirects, visible text. Pure Java. */
final class Html {
 static final class Input { String tag="input",name,type="text",value="",id="",placeholder="";boolean checked;
  boolean is(String t){return type.equalsIgnoreCase(t);} }
 static final class Form { String action,method="GET",id="",name="",raw="";List<Input> inputs=new ArrayList<>();
  Input password(){for(Input i:inputs)if(i.is("password"))return i;return null;}
  Input named(String n){for(Input i:inputs)if(n.equalsIgnoreCase(i.name))return i;return null;}
  boolean hasPassword(){return password()!=null;} }
 static final Pattern FORM=Pattern.compile("<form\\b([^>]*)>(.*?)(?:</form\\s*>|(?=<form\\b)|\\z)",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
 static final Pattern FIELD=Pattern.compile("<(input|button|select|textarea)\\b([^>]*)>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
 static final Pattern ATTR=Pattern.compile("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*(?:=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+)))?",Pattern.DOTALL);
 static Map<String,String> attrs(String s){Map<String,String> m=new HashMap<>();Matcher a=ATTR.matcher(s);while(a.find()){String v=a.group(3)!=null?a.group(3):a.group(4)!=null?a.group(4):a.group(5)!=null?a.group(5):"";m.put(a.group(1).toLowerCase(Locale.ROOT),unescape(v));}return m;}
 static String unescape(String s){return s.replace("&quot;","\"").replace("&#39;","'").replace("&#039;","'").replace("&lt;","<").replace("&gt;",">").replace("&nbsp;"," ").replace("&amp;","&");}
 static String stripComments(String html){return html.replaceAll("(?s)<!--.*?-->","");}
 /** What a browser parses as markup: comments and script/template bodies removed (a <form> inside a JS string is not a form). */
 static String markup(String html){return stripComments(html).replaceAll("(?is)<(script|template|noscript)\\b[^>]*>.*?</\\1\\s*>"," ");}
 static List<Form> forms(String html,String baseUrl){List<Form> out=new ArrayList<>();if(html==null)return out;html=markup(html);Matcher f=FORM.matcher(html);
  while(f.find()){Form form=new Form();Map<String,String> a=attrs(f.group(1));form.raw=f.group(2);form.method=a.getOrDefault("method","GET").toUpperCase(Locale.ROOT);form.id=a.getOrDefault("id","");form.name=a.getOrDefault("name","");
   String act=a.get("action");form.action=act==null||act.trim().isEmpty()||act.trim().startsWith("javascript")||act.contains("$(")||act.contains("$PORTAL")?baseUrl:Http.resolve(baseUrl,act);
   Matcher m=FIELD.matcher(f.group(2));while(m.find()){Map<String,String> ia=attrs(m.group(2));Input in=new Input();in.tag=m.group(1).toLowerCase(Locale.ROOT);in.name=ia.get("name");
    in.type=in.tag.equals("input")?ia.getOrDefault("type","text").toLowerCase(Locale.ROOT):in.tag.equals("button")?ia.getOrDefault("type","submit").toLowerCase(Locale.ROOT):in.tag;
    in.value=ia.getOrDefault("value","");in.id=ia.getOrDefault("id","");in.placeholder=ia.getOrDefault("placeholder","");in.checked=ia.containsKey("checked");form.inputs.add(in);}
   out.add(form);}
  return out;}
 static final Pattern META=Pattern.compile("<meta[^>]+http-equiv\\s*=\\s*[\"']?refresh[\"']?[^>]*content\\s*=\\s*[\"']\\s*\\d*\\s*;?\\s*(?:url\\s*=\\s*)?['\"]?([^\"'>]+)",Pattern.CASE_INSENSITIVE);
 static final Pattern META2=Pattern.compile("<meta[^>]+content\\s*=\\s*[\"']\\s*\\d*\\s*;\\s*url\\s*=\\s*['\"]?([^\"'>]+)[^>]*http-equiv\\s*=\\s*[\"']?refresh",Pattern.CASE_INSENSITIVE);
 static String metaRefresh(String html){if(html==null)return null;Matcher m=META.matcher(html);if(m.find())return unescape(m.group(1).trim());m=META2.matcher(html);return m.find()?unescape(m.group(1).trim()):null;}
 static final Pattern JS=Pattern.compile("(?:window\\.|document\\.|top\\.|self\\.)?location(?:\\.href)?\\s*=\\s*[\"']([^\"']+)[\"']|location\\.(?:replace|assign)\\(\\s*[\"']([^\"']+)[\"']\\s*\\)",Pattern.CASE_INSENSITIVE);
 static String jsRedirect(String html){if(html==null)return null;Matcher m=JS.matcher(stripComments(html));while(m.find()){String u=m.group(1)!=null?m.group(1):m.group(2);if(u!=null&&!u.startsWith("#")&&!u.toLowerCase(Locale.ROOT).startsWith("javascript"))return u.replace("\\/","/");}return null;}
 /** Visible text, roughly: scripts/styles removed, tags stripped, whitespace collapsed. */
 static String text(String html){if(html==null)return "";String s=html.replaceAll("(?is)<(script|style|noscript)\\b.*?</\\1>"," ").replaceAll("(?s)<!--.*?-->"," ").replaceAll("(?s)<[^>]+>"," ");return unescape(s).replaceAll("\\s+"," ").trim();}
 static String title(String html){Matcher m=Pattern.compile("<title[^>]*>(.*?)</title>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(html==null?"":html);return m.find()?unescape(m.group(1)).replaceAll("\\s+"," ").trim():"";}
}
