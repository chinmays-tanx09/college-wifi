package bot.paradox.wifi;
import java.util.*;
/**
 * v5.2 daily data usage: the pure math (JVM-tested, no Android). Days are LOCAL calendar days in the given zone,
 * so "today" resets at local midnight. Units are binary (1 GB = 1024 MB), the same as the Sophos user portal shows.
 * Nothing here leaves the phone.
 */
final class Usage {
 static final long KB=1024L,MB=KB*1024,GB=MB*1024,DAY_MS=86_400_000L;
 // ---------- days ----------
 static Calendar cal(long t,TimeZone tz){Calendar c=Calendar.getInstance(tz,Locale.US);c.setTimeInMillis(t);return c;}
 /** Local midnight at or before t. */
 static long dayStart(long t,TimeZone tz){Calendar c=cal(t,tz);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);return c.getTimeInMillis();}
 /** Next local midnight strictly after t (DST-safe: 23 h or 25 h days come out right). */
 static long nextMidnight(long t,TimeZone tz){Calendar c=cal(dayStart(t,tz),tz);c.add(Calendar.DAY_OF_YEAR,1);return c.getTimeInMillis();}
 /** Local midnight n days before the day of t. */
 static long daysBack(long t,int n,TimeZone tz){Calendar c=cal(dayStart(t,tz),tz);c.add(Calendar.DAY_OF_YEAR,-n);return c.getTimeInMillis();}
 /** "20261029" */
 static String dayKey(long t,TimeZone tz){Calendar c=cal(t,tz);return String.format(Locale.US,"%04d%02d%02d",c.get(Calendar.YEAR),c.get(Calendar.MONTH)+1,c.get(Calendar.DAY_OF_MONTH));}
 /** Last n day keys ending with today, oldest first. */
 static String[] lastDays(long now,int n,TimeZone tz){String[] out=new String[n];for(int i=0;i<n;i++)out[i]=dayKey(daysBack(now,n-1-i,tz),tz);return out;}
 /** Spread bytes counted between t0 and t1 over the local days they span, in proportion to time; totals are conserved exactly. */
 static LinkedHashMap<String,long[]> split(long t0,long t1,long down,long up,TimeZone tz){LinkedHashMap<String,long[]> out=new LinkedHashMap<>();if(down<0)down=0;if(up<0)up=0;
  if(t1<=t0){out.put(dayKey(t1,tz),new long[]{down,up});return out;}
  long span=t1-t0,a=t0,gotD=0,gotU=0;
  while(a<t1){long b=Math.min(nextMidnight(a,tz),t1);boolean last=b>=t1;long d,u;
   if(last){d=down-gotD;u=up-gotU;}else{double f=(double)(b-a)/span;d=Math.min(down-gotD,Math.round(down*((double)(b-t0)/span))-gotD);u=Math.min(up-gotU,Math.round(up*((double)(b-t0)/span))-gotU);if(d<0)d=0;if(u<0)u=0;}
   gotD+=d;gotU+=u;long[] cur=out.get(dayKey(a,tz));if(cur==null)out.put(dayKey(a,tz),new long[]{d,u});else{cur[0]+=d;cur[1]+=u;}a=b;}
  return out;}

 // ---------- per-day ledger (down, up) ----------
 static final class Ledger {
  final TreeMap<String,long[]> days=new TreeMap<>();
  void add(long t0,long t1,long down,long up,TimeZone tz){for(Map.Entry<String,long[]> e:split(t0,t1,down,up,tz).entrySet()){long[] c=days.get(e.getKey());if(c==null)days.put(e.getKey(),e.getValue().clone());else{c[0]+=e.getValue()[0];c[1]+=e.getValue()[1];}}}
  long[] get(String day){long[] v=days.get(day);return v==null?new long[]{0,0}:v.clone();}
  void set(String day,long down,long up){days.put(day,new long[]{Math.max(0,down),Math.max(0,up)});}
  /** Keep only the newest keep days (by key) on or before today. */
  void prune(String today,int keep){days.tailMap(today,false).clear();while(days.size()>keep)days.remove(days.firstKey());}
  String save(){StringBuilder s=new StringBuilder();for(Map.Entry<String,long[]> e:days.entrySet()){if(s.length()>0)s.append(';');s.append(e.getKey()).append(':').append(e.getValue()[0]).append(':').append(e.getValue()[1]);}return s.toString();}
  static Ledger load(String s){Ledger l=new Ledger();if(s==null||s.isEmpty())return l;for(String part:s.split(";")){String[] f=part.split(":");if(f.length!=3||f[0].length()!=8)continue;try{l.days.put(f[0],new long[]{Math.max(0,Long.parseLong(f[1])),Math.max(0,Long.parseLong(f[2]))});}catch(NumberFormatException ignored){}}return l;}
 }

 // ---------- campus sessions: when the phone was on the campus Wi-Fi (for the precise Android counter) ----------
 static final class Sessions {
  final ArrayList<long[]> list=new ArrayList<>(); // {start,end}; end==0 = still open
  boolean open(){return !list.isEmpty()&&list.get(list.size()-1)[1]==0;}
  void begin(long t){if(open())return;if(!list.isEmpty()){long[] last=list.get(list.size()-1);if(t-last[1]<60_000&&t>=last[1]){last[1]=0;return;}}list.add(new long[]{t,0});}
  void end(long t){if(!open())return;long[] last=list.get(list.size()-1);last[1]=Math.max(t,last[0]);}
  /** Pieces of every session that fall inside [from,to) (an open session runs to `now`). */
  List<long[]> clip(long from,long to,long now){List<long[]> out=new ArrayList<>();for(long[] s:list){long a=Math.max(s[0],from),b=Math.min(s[1]==0?now:s[1],to);if(b>a)out.add(new long[]{a,b});}return out;}
  void prune(long before,int max){Iterator<long[]> it=list.iterator();while(it.hasNext()){long[] s=it.next();if(s[1]!=0&&s[1]<before)it.remove();}
   while(list.size()>max){long[] a=list.get(0),b=list.get(1);b[0]=a[0];list.remove(0);}} // merge the oldest pair rather than lose time
  String save(){StringBuilder s=new StringBuilder();for(long[] x:list){if(s.length()>0)s.append(';');s.append(x[0]).append('-').append(x[1]);}return s.toString();}
  static Sessions load(String s){Sessions r=new Sessions();if(s==null||s.isEmpty())return r;for(String p:s.split(";")){String[] f=p.split("-");if(f.length!=2)continue;try{long a=Long.parseLong(f[0]),b=Long.parseLong(f[1]);if(a>0&&(b==0||b>=a))r.list.add(new long[]{a,b});}catch(NumberFormatException ignored){}}return r;}
 }

 // ---------- TrafficStats fallback: counter samples → bytes ----------
 /** Bytes between two cumulative counter readings; a reset (reboot) or unsupported counter gives 0, never a negative or a jump. */
 static long delta(long before,long after){if(before<0||after<0||after<before)return 0;long d=after-before;return d>64*GB?0:d;}

 // ---------- cap / remaining ----------
 static long remaining(long cap,long used){return cap<=0?-1:Math.max(0,cap-used);}
 static long over(long cap,long used){return cap<=0?0:Math.max(0,used-cap);}
 static float fraction(long cap,long used){if(cap<=0)return 0f;if(used<=0)return 0f;return (float)Math.min(1d,(double)used/cap);}
 /** 0, 80 or 100: the highest alert threshold reached. */
 static int level(long cap,long used){if(cap<=0)return 0;if(used>=cap)return 100;if(used*5>=cap*4)return 80;return 0;}
 /** Which alert to post now, given the highest one already posted today (0 = none): never twice, never a step backwards. */
 static int alertDue(long cap,long used,int alreadyToday){int l=level(cap,used);return l>alreadyToday?l:0;}

 // ---------- words ----------
 static String bytes(long b){if(b<0)b=0;if(b>=GB){double g=(double)b/GB;return String.format(Locale.US,g>=100?"%.0f GB":"%.1f GB",g).replace(".0 GB"," GB");}
  if(b>=MB){double m=(double)b/MB;return String.format(Locale.US,m>=100?"%.0f MB":"%.1f MB",m).replace(".0 MB"," MB");}
  if(b>=KB)return String.format(Locale.US,"%.0f KB",(double)b/KB);return b+" B";}
 /** Like bytes(), but in GB once the allowance is GB-sized and the amount is 100 MB or more ("0.8 GB left", not "819 MB left"). */
 static String bytes(long b,long scale){if(scale>=GB&&b>=100*MB&&b<GB)return String.format(Locale.US,"%.1f GB",(double)b/GB);return bytes(b);}
 /** The card's one line. */
 static String line(long used,long cap){if(cap<=0)return "Used "+bytes(used)+" today";if(used>cap)return "Used "+bytes(used,cap)+" · "+bytes(used-cap,cap)+" over";return "Used "+bytes(used,cap)+" · "+bytes(cap-used,cap)+" left today";}
 /** "2", "2 GB", "1.5gb", "500 MB", "500m" → bytes; a bare number means GB. 0 / empty / "off" → 0 (no cap). -1 = not understood. */
 static long parseCap(String s){if(s==null)return 0;String t=s.trim().toLowerCase(Locale.ROOT).replace(",","");if(t.isEmpty()||t.equals("off")||t.equals("none")||t.equals("0"))return 0;
  java.util.regex.Matcher m=java.util.regex.Pattern.compile("^([0-9]*\\.?[0-9]+)\\s*(gb|g|gib|mb|m|mib|tb|t)?$").matcher(t);if(!m.find())return -1;double v=Double.parseDouble(m.group(1));String u=m.group(2)==null?"gb":m.group(2);
  double mult=u.startsWith("t")?1024d*GB:u.startsWith("m")?MB:GB;double r=v*mult;if(r>=1e15||r<MB)return -1;return Math.round(r);}
}
