package bot.paradox.wifi;
/** Developer card + Buy me a coffee. Links stay hidden while their constant is empty. */
final class Dev {
 static final String NAME="Chinmay Shastry";
 static final String INITIAL="C";
 static final String UPI_ID="chinmayshastry2005@oksbi";
 /** LinkedIn handle (e.g. "chinmay-shastry") or full URL. Empty = row hidden. */
 static String LINKEDIN="https://www.linkedin.com/in/chinmay-shastry-a0a76b324";
 /** What the row shows instead of the raw profile slug. */
 static String LINKEDIN_LABEL="chinmay-shastry";
 static final String GITHUB="https://github.com/chinmays-tanx09/paradox-wifi";
 static final String INSTAGRAM="";
 static final String EMAIL="";
 static final int[] AMOUNTS={29,49,99};
 /** UPI deep link (NPCI linking spec): payee, name, note, INR; amount only when a quick chip was tapped. */
 static String upi(int amount){String u="upi://pay?pa="+UPI_ID+"&pn=Chinmay%20Shastry&tn=Buy%20me%20a%20coffee%20%E2%80%94%20College%20WiFi&cu=INR";
  if(amount>0)u+="&am="+amount+".00";return u;}
 static String url(String kind,String v){if(v==null||v.trim().isEmpty())return null;v=v.trim();if(v.startsWith("http"))return v;String h=v.startsWith("@")?v.substring(1):v;
  switch(kind){case "linkedin":return "https://www.linkedin.com/in/"+h;case "github":return "https://github.com/"+h;case "instagram":return "https://instagram.com/"+h;case "email":return "mailto:"+v;default:return null;}}
 static String label(String kind,String v){if("linkedin".equals(kind)&&LINKEDIN_LABEL!=null&&!LINKEDIN_LABEL.isEmpty())return LINKEDIN_LABEL;return shown(v);}
 static String shown(String v){if(v==null)return "";v=v.trim();if(v.startsWith("http")){v=v.replaceFirst("^https?://(www\\.)?","");int i=v.indexOf('/');if(i>0&&v.length()>i+1)v=v.substring(i+1);if(v.endsWith("/"))v=v.substring(0,v.length()-1);if(v.startsWith("in/"))v=v.substring(3);}return v;}
}
