package bot.paradox.wifi;
import java.util.*;
/** Minimal JSON reader (objects → Map, arrays → List, numbers → Double) so update manifests parse the same on the phone and in JVM tests. */
final class MiniJson {
 final String s;int i;MiniJson(String s){this.s=s;}
 static Object parse(String s){MiniJson p=new MiniJson(s==null?"":s);p.ws();Object v=p.value();return v;}
 void ws(){while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;}
 Object value(){ws();if(i>=s.length())throw new IllegalArgumentException("end");char c=s.charAt(i);
  if(c=='{'){i++;Map<String,Object> m=new LinkedHashMap<>();ws();if(s.charAt(i)=='}'){i++;return m;}while(true){ws();String k=str();ws();expect(':');m.put(k,value());ws();if(s.charAt(i)==','){i++;continue;}expect('}');return m;}}
  if(c=='['){i++;List<Object> l=new ArrayList<>();ws();if(s.charAt(i)==']'){i++;return l;}while(true){l.add(value());ws();if(s.charAt(i)==','){i++;continue;}expect(']');return l;}}
  if(c=='"')return str();if(s.startsWith("true",i)){i+=4;return Boolean.TRUE;}if(s.startsWith("false",i)){i+=5;return Boolean.FALSE;}if(s.startsWith("null",i)){i+=4;return null;}
  int st=i;while(i<s.length()&&"+-0123456789.eE".indexOf(s.charAt(i))>=0)i++;if(st==i)throw new IllegalArgumentException("bad json at "+i);return Double.valueOf(s.substring(st,i));}
 void expect(char c){if(i>=s.length()||s.charAt(i)!=c)throw new IllegalArgumentException("expected "+c+" at "+i);i++;}
 String str(){expect('"');StringBuilder b=new StringBuilder();while(true){char c=s.charAt(i++);if(c=='"')return b.toString();if(c=='\\'){char e=s.charAt(i++);switch(e){case 'n':b.append('\n');break;case 't':b.append('\t');break;case 'r':b.append('\r');break;case 'b':b.append('\b');break;case 'f':b.append('\f');break;
    case 'u':b.append((char)Integer.parseInt(s.substring(i,i+4),16));i+=4;break;default:b.append(e);}}else b.append(c);}}
 @SuppressWarnings("unchecked") static Map<String,Object> obj(Object o){return o instanceof Map?(Map<String,Object>)o:new HashMap<String,Object>();}
 @SuppressWarnings("unchecked") static List<Object> arr(Object o){return o instanceof List?(List<Object>)o:new ArrayList<Object>();}
 static String str(Map<String,Object> m,String k){Object v=m.get(k);return v==null?"":v instanceof Double&&((Double)v)%1==0?String.valueOf(((Double)v).longValue()):String.valueOf(v);}
}
