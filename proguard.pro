# Paradox Wi-Fi v5 release shrinking/obfuscation (R8). Components are kept by the aapt-generated rules (build/aapt_rules.txt).
-keepattributes Signature,InnerClasses,EnclosingMethod
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.job.JobService
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-repackageclasses ''
-allowaccessmodification
-dontwarn javax.annotation.**
