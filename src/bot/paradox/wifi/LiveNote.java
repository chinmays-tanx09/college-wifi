package bot.paradox.wifi;
/** What the ongoing notification says: one connectivity word as the title, live whole-device throughput as the text. Pure Java (JVM-tested). */
final class LiveNote {
 static final String CONNECTED="Connected",SIGNING_IN="Signing in\u2026",SIGNING_OUT="Signing out\u2026",SIGNED_OUT="Signed out",AWAY="Not on college Wi-Fi",NO_INTERNET="No internet";
 /** kind = Session.kind(status); onCollege = the college Wi-Fi network is current; validated = Android's VALIDATED for it (null = unknown). */
 static String title(String kind,boolean onCollege,Boolean validated){if(!onCollege)return AWAY;if(kind==null)kind="";
  switch(kind){case "CONNECTED":return CONNECTED;case "SIGNING_IN":return SIGNING_IN;case "SIGNING_OUT":return SIGNING_OUT;case "SIGNED_OUT":return SIGNED_OUT;
   default:return Boolean.TRUE.equals(validated)?CONNECTED:NO_INTERNET;}}
 /** bits per second → "12.4 Mbps" (>= 1 Mbps) or "640 kbps". */
 static String rate(double bps){if(bps<0)bps=0;if(bps>=1_000_000)return String.format(java.util.Locale.US,bps>=100_000_000?"%.0f Mbps":"%.1f Mbps",bps/1_000_000d);return String.format(java.util.Locale.US,"%.0f kbps",bps/1000d);}
 static String speed(double rxBps,double txBps){return "\u2193 "+rate(rxBps)+"  \u2191 "+rate(txBps);}
 /** Single line, shown as the notification title with no content text (Android's header already names the app). */
 static String line(String title,String speed){return speed==null?title:title+" · "+speed;}
 static boolean offline(String line){return line.startsWith(AWAY)||line.startsWith(NO_INTERNET)||line.startsWith(SIGNED_OUT);}
 /** Byte counters sampled dtMs apart → speed line, or null when the counters are unsupported/reset. */
 static String speedFrom(long rx0,long tx0,long rx1,long tx1,long dtMs){if(dtMs<=0||rx0<0||tx0<0||rx1<rx0||tx1<tx0)return null;double s=dtMs/1000d;return speed((rx1-rx0)*8/s,(tx1-tx0)*8/s);}
}
