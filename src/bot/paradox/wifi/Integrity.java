package bot.paradox.wifi;
import android.content.*;import android.content.pm.*;
/**
 * v5 tamper check: the app runs only when it is signed with the developer's certificate. A modified APK has to be
 * re-signed with another key, so its certificate digest differs and every entry point fails closed (no sign-in,
 * no credential decryption, no network). This raises the bar against casual repackaging; it is not unbreakable
 * (someone patching the bytecode can remove the check), and it is no substitute for keeping the signing key private.
 */
final class Integrity {
 static final String EXPECTED="5EC7E78BF430B14F8218BD7E4E8F4EA7B3E053AB76BA9879F07FD1F22BCF043B";
 private static Boolean cached;
 static synchronized boolean ok(Context c){if(cached!=null)return cached;boolean r=false;
  try{PackageInfo pi=c.getPackageManager().getPackageInfo(c.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);SigningInfo si=pi.signingInfo;
   Signature[] s=si==null?null:si.hasMultipleSigners()?si.getApkContentsSigners():si.getSigningCertificateHistory();
   if(s!=null&&s.length>0){r=true;for(Signature x:si.hasMultipleSigners()?s:new Signature[]{s[s.length-1]}){String d=Net.sha256(x.toByteArray()).replace(":","");if(!d.equalsIgnoreCase(EXPECTED))r=false;}}}
  catch(Exception e){r=false;}cached=r;return r;}
}
