package bot.paradox.wifi;
import java.util.*;import java.util.regex.*;
/**
 * v5.2: reads the Sophos User Portal "Internet usage" page (https://<portal>:4443/userportal/, the page students see
 * as "User portal for <roll no>"). The quota we want is the "Current<Daily>Cycle usage information" table:
 * rows "Cycle upload / download / total network traffic", columns Allotted · Up to last session · Current session ·
 * Total · Remaining, values like "13312 MB", "13.59 MB" or "N.A.". Tolerant on purpose: labels match case- and
 * space-insensitively, units KB/MB/GB/TB (binary, as Sophos shows them), HTML tables or the page's plain text.
 * Pure Java (JVM-tested against a fixture rebuilt from a real screenshot). Built from one screenshot; untested live.
 */
final class PortalUsage {
 static final long NA=-1,UNLIMITED=Long.MAX_VALUE;
 static final class Quota {
  long allotted=NA,upToLast=NA,current=NA,total=NA,remaining=NA,down=NA,up=NA;String period="daily";
  boolean ok(){return total>=0||(allotted>0&&allotted!=UNLIMITED&&remaining>=0);}
  /** Used in this cycle (total, else allotted - remaining). */
  long used(){if(total>=0)return total;if(allotted>0&&allotted!=UNLIMITED&&remaining>=0)return Math.max(0,allotted-remaining);return NA;}
  /** The cycle's allowance, or 0 when none/unlimited (then there is nothing to count down from). */
  long cap(){return allotted>0&&allotted!=UNLIMITED?allotted:0;}
  long left(){if(remaining>=0&&remaining!=UNLIMITED)return remaining;long c=cap(),u=used();return c>0&&u>=0?Math.max(0,c-u):NA;}
  boolean daily(){return "daily".equals(period);}
  String save(){return allotted+","+upToLast+","+current+","+total+","+remaining+","+down+","+up+","+period;}
  static Quota load(String s){if(s==null)return null;String[] f=s.split(",");if(f.length!=8)return null;Quota q=new Quota();try{q.allotted=Long.parseLong(f[0]);q.upToLast=Long.parseLong(f[1]);q.current=Long.parseLong(f[2]);q.total=Long.parseLong(f[3]);q.remaining=Long.parseLong(f[4]);q.down=Long.parseLong(f[5]);q.up=Long.parseLong(f[6]);q.period=f[7];}catch(Exception e){return null;}fixup(q);return q.ok()?q:null;}
 }
 static String norm(String s){return s==null?"":s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");}
 /** "13312 MB" → bytes; "N.A." / "-" / "" → NA; "Unlimited" → UNLIMITED. A bare number is MB (the portal's unit). */
 static long value(String s){if(s==null)return NA;String t=s.trim().toLowerCase(Locale.ROOT).replace(",","").replace("\u00a0"," ");
  if(t.startsWith("unlimited"))return UNLIMITED;Matcher m=Pattern.compile("([0-9]*\\.?[0-9]+)\\s*(bytes|byte|b|kb|kib|k|mb|mib|m|gb|gib|g|tb|tib|t)?\\b").matcher(t);if(!m.find())return NA;
  double v=Double.parseDouble(m.group(1));String u=m.group(2)==null?"mb":m.group(2);double mult;
  switch(u.charAt(0)){case 'k':mult=Usage.KB;break;case 'm':mult=Usage.MB;break;case 'g':mult=Usage.GB;break;case 't':mult=1024d*Usage.GB;break;default:mult=1;}
  return Math.round(v*mult);}
 static String cellText(String h){String t=h.replaceAll("(?is)<br\\s*/?>"," ").replaceAll("(?s)<[^>]*>"," ");
  t=t.replace("&nbsp;"," ").replace("&#160;"," ").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&#39;","'");return t.replaceAll("\\s+"," ").trim();}
 /** Rows of cells, in document order, from HTML tables; or from plain text (tab / 2+ spaces / "|" separated). */
 static List<List<String>> rows(String page){List<List<String>> out=new ArrayList<>();
  if(Pattern.compile("(?i)<t[dh][\\s>]").matcher(page).find()){Matcher r=Pattern.compile("(?is)<tr[^>]*>(.*?)(?=<tr[\\s>]|</table|$)").matcher(page);
   while(r.find()){List<String> cells=new ArrayList<>();Matcher c=Pattern.compile("(?is)<t[dh][^>]*>(.*?)(?=<t[dh][\\s>]|</tr|$)").matcher(r.group(1));while(c.find())cells.add(cellText(c.group(1)));if(!cells.isEmpty())out.add(cells);}
   if(!out.isEmpty())return out;}
  String text=page.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>"," ").replaceAll("(?i)</(p|div|tr|li|h[1-6])>|<br\\s*/?>","\n").replaceAll("(?s)<[^>]*>","\t");
  for(String line:text.split("\\r?\\n")){List<String> cells=new ArrayList<>();for(String c:line.split("\\t|\\s{2,}|\\|")){String t=cellText(c);if(!t.isEmpty())cells.add(t);}if(!cells.isEmpty())out.add(cells);}
  return out;}
 /** The page → this cycle's quota, or null when the page doesn't carry the cycle table (login page, error, other layout). */
 static Quota parse(String page){if(page==null||page.isEmpty())return null;String flat=norm(page);
  Quota q=new Quota();if(flat.contains("dailycycle"))q.period="daily";else if(flat.contains("weeklycycle"))q.period="weekly";else if(flat.contains("monthlycycle"))q.period="monthly";else if(flat.contains("yearlycycle"))q.period="yearly";else q.period="daily";
  List<List<String>> rows=rows(page);int[] col=null;boolean any=false;
  for(int i=0;i<rows.size();i++){List<String> r=rows.get(i);String first=norm(r.get(0));
   if(isHeader(r)){col=columns(r);continue;}
   String kind=first.startsWith("cycletotal")?"total":first.startsWith("cycledownload")?"down":first.startsWith("cycleupload")?"up":null;
   if(kind==null)continue;int[] c=col==null?new int[]{1,2,3,4,5}:shift(col,r.size());
   long allotted=at(r,c[0]),upto=at(r,c[1]),cur=at(r,c[2]),tot=at(r,c[3]),rem=at(r,c[4]);
   if("total".equals(kind)){q.allotted=allotted;q.upToLast=upto;q.current=cur;q.total=tot;q.remaining=rem;any=true;}
   else if("down".equals(kind))q.down=tot;else q.up=tot;}
  if(!any&&q.down>=0&&q.up>=0){q.total=q.down+q.up;any=true;}
  if(!any)return null;if(q.total<0&&q.upToLast>=0&&q.current>=0)q.total=q.upToLast+q.current;
  fixup(q);return q.ok()?q:null;}
 /** v5.3: a real portal read (Oct 29) put the cap in Total and the used amount in Allotted ("Used 13 GB of 1.7 GB").
  *  The cap is always the largest of allotted/total/remaining and used + left = cap, so re-seat them when they disagree. */
 static void fixup(Quota q){long a=q.allotted,t=q.total,r=q.remaining;
  if(a>0&&a!=UNLIMITED&&t>=0&&r>=0&&r!=UNLIMITED){long cap=Math.max(a,Math.max(t,r));long tol=Math.max(5L*Usage.MB,cap/50);
   if(Math.abs(a-(t+r))>tol){long left=r!=cap?r:Math.min(a,t);q.allotted=cap;q.remaining=left;q.total=Math.max(0,cap-left);}}
  else if(a>0&&a!=UNLIMITED&&t>a){long x=a;q.allotted=t;q.total=x;}
  if(q.down<=0&&q.up<=0){q.down=NA;q.up=NA;}}
 static boolean isHeader(List<String> r){boolean a=false,rem=false;for(String c:r){String n=norm(c);if(n.equals("allotted"))a=true;if(n.equals("remaining"))rem=true;}return a&&rem;}
 /** Column index (in the header row) of Allotted, Up to last session, Current session, Total, Remaining. */
 static int[] columns(List<String> h){int[] c={-1,-1,-1,-1,-1};for(int i=0;i<h.size();i++){String n=norm(h.get(i));
   if(n.equals("allotted"))c[0]=i;else if(n.startsWith("uptolast"))c[1]=i;else if(n.startsWith("current"))c[2]=i;else if(n.equals("total"))c[3]=i;else if(n.equals("remaining"))c[4]=i;}
  int[] def={1,2,3,4,5};for(int i=0;i<5;i++)if(c[i]<0)c[i]=def[i];c=c.clone();int extra=h.size();return new int[]{c[0],c[1],c[2],c[3],c[4],extra};}
 /** A header without the empty corner cell is one short of the data rows: shift right. */
 static int[] shift(int[] col,int rowSize){int hdr=col[5];int s=rowSize>hdr?rowSize-hdr:0;return new int[]{col[0]+s,col[1]+s,col[2]+s,col[3]+s,col[4]+s};}
 static long at(List<String> r,int i){return i>=0&&i<r.size()?value(r.get(i)):NA;}
 /** "Used 13.6 MB of 13 GB today · 13 GB left" (daily) or "… this cycle". */
 static String line(Quota q){long u=q.used(),c=q.cap(),l=q.left();String when=q.daily()?"today":"this cycle";
  if(c<=0)return "Used "+Usage.bytes(u)+" "+when;return "Used "+Usage.bytes(u,c)+" of "+Usage.bytes(c)+" "+when+" · "+Usage.bytes(Math.max(0,l),c)+" left";}
}
