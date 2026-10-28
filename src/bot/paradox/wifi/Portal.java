package bot.paradox.wifi;
import java.util.*;
/** v5: portal-independent vocabulary. What a sign-in attempt came back with, how to read a portal's words, and what kind of portal a page is. Pure Java. */
final class Portal {
 enum Kind { OK, WRONG_PASSWORD, LIMIT_SESSION, LIMIT_QUOTA, EXPIRED, REFUSED, UNREADABLE }
 enum Live { IN, OUT, UNKNOWN }
 static final class Result { final Kind kind;final String message;
  Result(Kind k,String m){kind=k;message=m==null?"":m.trim();}
  boolean ok(){return kind==Kind.OK;}
  /** The account itself was refused (move on to the next account); UNREADABLE is not one of these. */
  boolean refused(){return kind!=Kind.OK&&kind!=Kind.UNREADABLE;}
  @Override public String toString(){return kind+(message.isEmpty()?"":": "+message);} }

 // ---------- reading a portal's words ----------
 static final String[] WRONG={"invalid user","invalid username","invalid login","invalid credential","incorrect password","incorrect user","wrong password","wrong username",
  "password is incorrect","password incorrect","bad password","authentication failed","authentication failure","auth failed","firewall authentication failed","login failed. please check",
  "could not log you on","login failed","make sure your password is correct","user not found","unknown user","no such user","invalid password","username or password","user name or password","not authorized"};
 static final String[] SESSION={"maximum login limit","max login limit","login limit","maximum number of login","maximum number of devices","max devices","device limit","devices allowed",
  "maximum number of sessions","concurrent","simultaneous","no more sessions","already logged in from","already logged in on","already in use","maximum number of users","session limit",
  "too many sessions","too many devices","limit of devices","user is already logged in"};
 static final String[] QUOTA={"data transfer limit","data limit","data quota","quota","volume limit","traffic limit","bandwidth limit","usage limit","download limit","upload limit",
  "time limit","surfing time","allotted time","uptime limit","access time","time quota","exceeded","exhausted","used up","limit reached","limit exceeded","limit has been reached"};
 static final String[] EXPIRED={"account has expired","account expired","has expired","validity","account is disabled","account disabled","account locked","account is locked","not active","inactive","suspended"};
 static final String[] SUCCESS={"you are signed in","you have successfully logged in","successfully logged in","you are logged in","you are now logged in","login successful","logged in successfully",
  "authentication successful","successfully authenticated","you are connected","you're connected","connected to the internet"};
 static boolean any(String m,String[] keys){for(String k:keys)if(m.contains(k))return true;return false;}
 /** A portal's refusal text → kind. Specific limit/expiry phrases first (a limit misread as a wrong password would park a good account forever), then wrong-password words, else REFUSED (unknown: try the next account). */
 static Kind refusal(String text){String m=text==null?"":text.toLowerCase(Locale.ROOT);
  if(any(m,EXPIRED))return Kind.EXPIRED;if(any(m,SESSION))return Kind.LIMIT_SESSION;if(any(m,QUOTA))return Kind.LIMIT_QUOTA;if(any(m,WRONG))return Kind.WRONG_PASSWORD;return Kind.REFUSED;}
 static boolean looksSignedIn(String text){String m=text==null?"":text.toLowerCase(Locale.ROOT);if(m.contains("not logged")||m.contains("not signed")||m.contains("log in again"))return false;return any(m,SUCCESS);}
 static String shortMsg(String m){if(m==null)return "";m=m.replaceAll("\\s+"," ").trim();return m.length()>140?m.substring(0,137)+"\u2026":m;}

 // ---------- what kind of portal is this? ----------
 static final String SOPHOS="sophos",FORTINET="fortinet",PFSENSE="pfsense",OPNSENSE="opnsense",MIKROTIK="mikrotik",ARUBA="aruba",CISCO_WLC="ciscowlc",MERAKI="meraki",RUCKUS="ruckus",NOMADIX="nomadix",GENERIC="generic";
 static String name(String type){if(type==null)return "Unknown portal";switch(type){case SOPHOS:return "Sophos / Cyberoam";case FORTINET:return "FortiGate";case PFSENSE:return "pfSense";case OPNSENSE:return "OPNsense";
  case MIKROTIK:return "MikroTik Hotspot";case ARUBA:return "Aruba";case CISCO_WLC:return "Cisco WLC";case MERAKI:return "Cisco Meraki";case RUCKUS:return "Ruckus";case NOMADIX:return "Nomadix";default:return "Login page";}}
 /** Fingerprint from the final login-page URL and its HTML (and every URL seen on the way). */
 static String fingerprint(String url,String html,List<String> chain){String u=(url==null?"":url).toLowerCase(Locale.ROOT),h=(html==null?"":html).toLowerCase(Locale.ROOT);
  StringBuilder all=new StringBuilder(u);if(chain!=null)for(String c:chain)all.append(' ').append(c.toLowerCase(Locale.ROOT));String a=all.toString();
  if(a.contains("/httpclient.html")||h.contains("login.xml")||h.contains("cyberoam")||(h.contains("sophos")&&h.contains("producttype"))||a.matches(".*:8090/.*"))return SOPHOS;
  if(a.contains("fgtauth")||h.contains("4tredir")||(h.contains("name=\"magic\"")||h.contains("name=magic"))||h.contains("fortinet")||h.contains("fortigate"))return FORTINET;
  if(h.contains("/api/captiveportal/access")||h.contains("opnsense"))return OPNSENSE;
  if(h.contains("auth_user")&&h.contains("auth_pass")||h.contains("$portal_action$")||(a.matches(".*:800[23]/.*")&&a.contains("zone=")))return PFSENSE;
  if(h.contains("chap-challenge")||h.contains("hexmd5(")||h.contains("$(link-login")||h.contains("mikrotik")||h.contains("document.sendin")||h.contains("name=\"sendin\""))return MIKROTIK;
  if(a.contains("network-auth.com")||h.contains("meraki"))return MERAKI;
  if(a.contains("/cgi-bin/login")||a.contains("securelogin.arubanetworks.com")||h.contains("arubanetworks")||h.contains("clearpass")||h.contains("cmd=authenticate"))return ARUBA;
  if(h.contains("buttonclicked")&&h.contains("err_flag")||a.contains("1.1.1.1/login.html")||a.contains("/fs/customwebauth"))return CISCO_WLC;
  if(a.contains("ruckus")||h.contains("ruckus")||a.contains(":9997/")||a.contains("/user/_allowuser.jsp")||a.contains("subscriberportal"))return RUCKUS;
  if(h.contains("nomadix")||a.contains("nomadix"))return NOMADIX;
  return GENERIC;}
}
