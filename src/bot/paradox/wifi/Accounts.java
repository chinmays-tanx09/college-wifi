package bot.paradox.wifi;
import java.util.*;
/**
 * v5: several logins per network with automatic failover. Order = the user's order (first = primary).
 * A wrong password or an expired account is parked until the user edits it (one attempt, never a lockout loop).
 * A device/session limit is retried on the next Wi-Fi connect (or after 15 min); a data/time quota after the next
 * local midnight (most campus quotas are daily). Pure Java.
 */
final class Accounts {
 static long SESSION_RETRY_MS=15*60_000L;
 static final class Account { String id,user;String state="",message="";long stateAt,until;
  Account(String id,String user){this.id=id;this.user=user;}
  boolean parked(){return "WRONG_PASSWORD".equals(state)||"EXPIRED".equals(state);}
  boolean limited(long now,boolean freshConnect){if("LIMIT_SESSION".equals(state))return !freshConnect&&now<until;if("LIMIT_QUOTA".equals(state)||"REFUSED".equals(state))return now<until;return false;} }
 /** Accounts to try, in order, for one sign-in chain. Primary first unless it is parked or still limited. */
 static List<Account> candidates(List<Account> ordered,long now,boolean freshConnect){List<Account> out=new ArrayList<>();for(Account a:ordered)if(!a.parked()&&!a.limited(now,freshConnect))out.add(a);return out;}
 /** v5.1: a manual "Sign in now" tap tries every account that isn't parked once, limits included (the user asked; one attempt each, no loop). */
 static List<Account> candidates(List<Account> ordered,long now,boolean freshConnect,boolean manual){if(!manual)return candidates(ordered,now,freshConnect);List<Account> out=new ArrayList<>();for(Account a:ordered)if(!a.parked())out.add(a);return out;}
 /** Record what the portal said about this account. nextReset = the next local midnight after now. */
 static void mark(Account a,Portal.Result r,long now,long nextReset){a.stateAt=now;a.message=Portal.shortMsg(r.message);
  switch(r.kind){case OK:a.state="";a.until=0;a.message="";break;
   case WRONG_PASSWORD:a.state="WRONG_PASSWORD";a.until=Long.MAX_VALUE;break;
   case EXPIRED:a.state="EXPIRED";a.until=Long.MAX_VALUE;break;
   case LIMIT_SESSION:a.state="LIMIT_SESSION";a.until=now+SESSION_RETRY_MS;break;
   case LIMIT_QUOTA:a.state="LIMIT_QUOTA";a.until=Math.max(nextReset,now+SESSION_RETRY_MS);break;
   case REFUSED:a.state="REFUSED";a.until=now+SESSION_RETRY_MS;break; // unknown non-success: try the next one, come back later
   default:break;}}
 /** Short label for the account list chip. */
 static String chip(Account a,boolean active,long now,java.text.DateFormat tf){
  if("WRONG_PASSWORD".equals(a.state))return "Wrong password";if("EXPIRED".equals(a.state))return "Expired";
  if(("LIMIT_SESSION".equals(a.state)||"LIMIT_QUOTA".equals(a.state)||"REFUSED".equals(a.state))&&now<a.until)return ("REFUSED".equals(a.state)?"Refused ":"Limit reached ")+(tf==null?"":tf.format(new Date(a.stateAt)));
  return active?"Active":"";}
 static int indexOf(List<Account> l,String id){for(int i=0;i<l.size();i++)if(l.get(i).id.equals(id))return i;return -1;}
}
