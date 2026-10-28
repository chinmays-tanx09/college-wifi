package bot.paradox.wifi;
import java.util.*;import java.nio.file.*;
/** v5.2: day rollover, TrafficStats deltas, cap/alert math, words, and the Sophos user-portal parser. Plain JVM, no Android. */
public class UsageTest {
 static int pass=0,fail=0;
 static void ok(boolean c,String what){if(c){pass++;}else{fail++;System.out.println("FAIL "+what);}}
 static void eq(Object a,Object b,String what){ok(Objects.equals(a,b),what+" expected <"+b+"> got <"+a+">");}
 static long at(TimeZone tz,int y,int mo,int d,int h,int mi){Calendar c=Calendar.getInstance(tz);c.clear();c.set(y,mo-1,d,h,mi,0);return c.getTimeInMillis();}
 public static void main(String[] a)throws Exception{
  TimeZone ist=TimeZone.getTimeZone("Asia/Kolkata"),ny=TimeZone.getTimeZone("America/New_York");
  // --- day keys and midnight
  long t=at(ist,2026,10,29,0,16);
  eq(Usage.dayKey(t,ist),"20261029","dayKey just after midnight IST");
  eq(Usage.dayKey(t-17*60_000L,ist),"20261028","dayKey just before midnight IST");
  eq(Usage.dayStart(t,ist),at(ist,2026,10,29,0,0),"dayStart");
  eq(Usage.nextMidnight(t,ist),at(ist,2026,10,30,0,0),"nextMidnight");
  eq(Usage.nextMidnight(at(ist,2026,10,29,0,0),ist),at(ist,2026,10,30,0,0),"nextMidnight from exactly midnight is the next one");
  eq(Usage.dayKey(t,TimeZone.getTimeZone("UTC")),"20261028","same instant is still Oct 28 in UTC: the zone decides the day");
  eq(String.join(",",Usage.lastDays(t,3,ist)),"20261027,20261028,20261029","lastDays oldest first");
  eq(Usage.dayKey(at(ist,2026,12,31,23,59)+120_000,ist),"20270101","year rollover");
  // DST: Nov 1 2026 is 25 h long in New York
  long d0=at(ny,2026,11,1,0,0);eq(Usage.nextMidnight(d0,ny)-d0,25*3600_000L,"DST fall-back day is 25 h");
  long s0=at(ny,2026,3,8,0,0);eq(Usage.nextMidnight(s0,ny)-s0,23*3600_000L,"DST spring-forward day is 23 h");
  // --- split across midnight, conserving totals
  LinkedHashMap<String,long[]> sp=Usage.split(at(ist,2026,10,28,23,30),at(ist,2026,10,29,0,30),1000,100,ist);
  eq(sp.size(),2,"split spans two days");eq(sp.get("20261028")[0],500L,"half the download before midnight");eq(sp.get("20261029")[0],500L,"half after");
  eq(sp.get("20261028")[1]+sp.get("20261029")[1],100L,"upload conserved");
  sp=Usage.split(at(ist,2026,10,28,23,0),at(ist,2026,10,29,0,0),777,3,ist);eq(sp.size(),1,"ending exactly at midnight stays in the old day");eq(sp.get("20261028")[0],777L,"all of it");
  sp=Usage.split(at(ist,2026,10,27,12,0),at(ist,2026,10,29,12,0),4801,0,ist);long sum=0;for(long[] v:sp.values())sum+=v[0];eq(sum,4801L,"3-day split conserves odd totals");eq(sp.size(),3,"3 days");
  sp=Usage.split(t,t,50,5,ist);eq(sp.get("20261029")[0],50L,"zero-length sample lands on its day");
  sp=Usage.split(t,t+1000,-5,-1,ist);eq(sp.get("20261029")[0],0L,"negative bytes clamp to 0");
  // --- ledger
  Usage.Ledger l=new Usage.Ledger();l.add(at(ist,2026,10,28,23,50),at(ist,2026,10,29,0,10),2*Usage.MB,0,ist);l.add(t,t+60_000,Usage.MB,Usage.KB,ist);
  eq(l.get("20261028")[0],Usage.MB,"ledger yesterday");eq(l.get("20261029")[0],2*Usage.MB,"ledger today accumulates");eq(l.get("20261029")[1],Usage.KB,"ledger today up");
  eq(l.get("20261030")[0],0L,"empty day is 0 (fresh day after midnight)");
  Usage.Ledger l2=Usage.Ledger.load(l.save());eq(l2.save(),l.save(),"ledger round-trip");
  eq(Usage.Ledger.load("garbage;20261029:5:x;20261029:7:8").get("20261029")[0],7L,"ledger load skips junk");
  for(int i=0;i<12;i++)l.set(String.format("202610%02d",i+1),i,i);l.set("20261105",9,9);l.prune("20261029",8);
  eq(l.days.size(),8,"prune keeps 8 days");ok(!l.days.containsKey("20261105"),"prune drops future days (clock moved back)");ok(l.days.containsKey("20261029"),"prune keeps today");
  // --- sessions
  Usage.Sessions s=new Usage.Sessions();s.begin(1000);s.begin(2000);eq(s.list.size(),1,"begin twice = one session");s.end(5000);s.begin(30_000);eq(s.list.size(),1,"reconnect within a minute continues the session");eq(s.list.get(0)[1],0L,"reopened");
  s.end(40_000);s.begin(200_000);ok(s.open(),"open");List<long[]> c=s.clip(3000,250_000,220_000);eq(c.size(),2,"clip 2 pieces");eq(c.get(0)[0],3000L,"clip start");eq(c.get(1)[1],220_000L,"open session runs to now");
  eq(Usage.Sessions.load(s.save()).save(),s.save(),"sessions round-trip");
  Usage.Sessions m=new Usage.Sessions();for(int i=0;i<10;i++){m.begin(i*1_000_000L+1);m.end(i*1_000_000L+500_000);}m.prune(0,4);eq(m.list.size(),4,"prune merges down to max");eq(m.list.get(0)[0],1L,"merged keeps earliest start");
  m.prune(9_000_000L,10);eq(m.list.size(),1,"prune drops sessions that ended before the window");
  // --- TrafficStats deltas
  eq(Usage.delta(100,350),250L,"delta");eq(Usage.delta(500,20),0L,"counter reset (reboot) is 0, not negative");eq(Usage.delta(-1,20),0L,"unsupported counter is 0");eq(Usage.delta(0,100*Usage.GB),0L,"absurd jump ignored");
  // --- cap math
  long cap=2*Usage.GB;
  eq(Usage.remaining(cap,(long)(1.2*Usage.GB)),cap-(long)(1.2*Usage.GB),"remaining");eq(Usage.remaining(cap,3*Usage.GB),0L,"remaining floors at 0");eq(Usage.remaining(0,5),-1L,"no cap = no remaining");
  eq(Usage.over(cap,3*Usage.GB),Usage.GB,"over");eq(Usage.fraction(cap,Usage.GB),0.5f,"fraction");eq(Usage.fraction(cap,9*Usage.GB),1f,"fraction caps at 1");eq(Usage.fraction(0,9),0f,"fraction no cap");
  eq(Usage.level(cap,(long)(1.59*Usage.GB)),0,"79.5% no alert");eq(Usage.level(cap,(long)(1.6*Usage.GB)+1),80,"80%");eq(Usage.level(cap,cap),100,"100% exactly");
  eq(Usage.alertDue(cap,(long)(1.7*Usage.GB),0),80,"first 80 alert");eq(Usage.alertDue(cap,(long)(1.8*Usage.GB),80),0,"80 not repeated");eq(Usage.alertDue(cap,cap,80),100,"then 100");eq(Usage.alertDue(cap,cap,100),0,"100 not repeated");
  eq(Usage.alertDue(cap,3*Usage.GB,0),100,"jump straight to 100 posts one alert");eq(Usage.alertDue(0,3*Usage.GB,0),0,"no cap no alert");
  // --- words
  eq(Usage.bytes(0),"0 B","0");eq(Usage.bytes(900),"900 B","B");eq(Usage.bytes(5*Usage.KB),"5 KB","KB");eq(Usage.bytes((long)(3.4*Usage.MB)),"3.4 MB","MB");eq(Usage.bytes(820*Usage.MB),"820 MB","MB big");
  eq(Usage.bytes((long)(1.2*Usage.GB)),"1.2 GB","GB");eq(Usage.bytes(2*Usage.GB),"2 GB","whole GB");eq(Usage.bytes(13312*Usage.MB),"13 GB","13312 MB = 13 GB");
  eq(Usage.line((long)(1.2*Usage.GB),cap),"Used 1.2 GB · 0.8 GB left today","card line");eq(Usage.line(Usage.GB,0),"Used 1 GB today","no cap line");eq(Usage.line(3*Usage.GB,cap),"Used 3 GB · 1 GB over","over line");
  eq(Usage.parseCap("2"),2*Usage.GB,"cap 2");eq(Usage.parseCap("1.5 GB"),(long)(1.5*Usage.GB),"cap 1.5 GB");eq(Usage.parseCap("500mb"),500*Usage.MB,"cap 500mb");eq(Usage.parseCap("off"),0L,"off");eq(Usage.parseCap(""),0L,"empty");
  eq(Usage.parseCap("abc"),-1L,"junk");eq(Usage.parseCap("0.0001"),-1L,"too small");eq(Usage.parseCap("13,312 MB"),13312*Usage.MB,"comma");
  // --- Sophos user portal parser
  String html=new String(Files.readAllBytes(Paths.get("test/fixtures/userportal_internet_usage.html")),"UTF-8");
  PortalUsage.Quota q=PortalUsage.parse(html);ok(q!=null,"portal fixture parses");
  if(q!=null){eq(q.allotted,13312*Usage.MB,"allotted 13312 MB");eq(q.upToLast,0L,"up to last 0");eq(q.current,Math.round(13.59*Usage.MB),"current 13.59 MB");eq(q.total,Math.round(13.59*Usage.MB),"total");
   eq(q.remaining,Math.round(13298.41*Usage.MB),"remaining 13298.41 MB");eq(q.down,Math.round(12.57*Usage.MB),"cycle download");eq(q.up,Math.round(1.02*Usage.MB),"cycle upload");eq(q.period,"daily","daily cycle");
   eq(PortalUsage.line(q),"Used 13.6 MB of 13 GB today · 13 GB left","portal line");eq(PortalUsage.Quota.load(q.save()).save(),q.save(),"quota round-trip");}
  String txt=new String(Files.readAllBytes(Paths.get("test/fixtures/userportal_internet_usage.txt")),"UTF-8");
  PortalUsage.Quota qt=PortalUsage.parse(txt);ok(qt!=null&&qt.remaining==Math.round(13298.41*Usage.MB)&&qt.allotted==13312*Usage.MB,"portal innerText parses (header without corner cell)");
  // variants: upper case, GB units, no corner cell in HTML, remaining N.A. → computed, weekly
  String v="<table><TR><TH>ALLOTTED</TH><TH>UP TO LAST SESSION</TH><TH>CURRENT SESSION</TH><TH>TOTAL</TH><TH>REMAINING</TH></TR><tr><td>Cycle Total Network Traffic</td><td>2 GB</td><td>1,024.00 MB</td><td>0.5 GB</td><td>1.5 GB</td><td>N.A.</td></tr></table><p>CurrentWeeklyCycle usage information</p>";
  PortalUsage.Quota qv=PortalUsage.parse(v);ok(qv!=null,"variant parses");if(qv!=null){eq(qv.total,(long)(1.5*Usage.GB),"GB units + shifted header");eq(qv.left(),(long)(0.5*Usage.GB),"remaining computed when N.A.");eq(qv.period,"weekly","weekly");eq(PortalUsage.line(qv),"Used 1.5 GB of 2 GB this cycle · 0.5 GB left","weekly line");}
  String un="<table><tr><th></th><th>Allotted</th><th>Up to last session</th><th>Current session</th><th>Total</th><th>Remaining</th></tr><tr><td>Cycle total network traffic</td><td>Unlimited</td><td>100 MB</td><td>20 MB</td><td>120 MB</td><td>Unlimited</td></tr></table>CurrentDailyCycle";
  PortalUsage.Quota qu=PortalUsage.parse(un);ok(qu!=null&&qu.cap()==0&&qu.used()==120*Usage.MB,"unlimited allowance: used shown, no cap");if(qu!=null)eq(PortalUsage.line(qu),"Used 120 MB today","unlimited line");
  ok(PortalUsage.parse("<html><form><input name=username><input type=password></form>Sign in</html>")==null,"login page → null");
  ok(PortalUsage.parse("")==null&&PortalUsage.parse(null)==null,"empty → null");
  String na="<table><tr><th></th><th>Allotted</th><th>Up to last session</th><th>Current session</th><th>Total</th><th>Remaining</th></tr><tr><td>Cycle total network traffic</td><td>N.A.</td><td>N.A.</td><td>N.A.</td><td>N.A.</td><td>N.A.</td></tr></table>";
  ok(PortalUsage.parse(na)==null,"all N.A. → null (nothing to show)");
  eq(PortalUsage.value("13312 MB"),13312*Usage.MB,"value MB");eq(PortalUsage.value("N.A."),PortalUsage.NA,"value NA");eq(PortalUsage.value("-"),PortalUsage.NA,"value dash");eq(PortalUsage.value("Unlimited"),PortalUsage.UNLIMITED,"value unlimited");
  eq(PortalUsage.value("512 KB"),512*Usage.KB,"value KB");eq(PortalUsage.value("7"),7*Usage.MB,"bare number = MB");eq(PortalUsage.value("1.5GB"),(long)(1.5*Usage.GB),"value no space");
  System.out.println("UsageTest: "+pass+" passed, "+fail+" failed");if(fail>0)System.exit(1);}
}
