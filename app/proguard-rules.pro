# XX Email R8 rules

# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.xxemail.data.api.**$$serializer { *; }
-keepclassmembers class dev.xxemail.data.api.** { *** Companion; }
-keepclasseswithmembers class dev.xxemail.data.api.** { kotlinx.serialization.KSerializer serializer(...); }

# --- Retrofit / OkHttp ---
-keepattributes Signature, Exceptions
-keepclassmembers,allowshrinking,allowobfuscation interface * { @retrofit2.http.* <methods>; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- jakarta.mail / Angus Mail (reflection-heavy MIME machinery) ---
-keep class jakarta.mail.** { *; }
-keep class org.eclipse.angus.** { *; }
-keep class jakarta.activation.** { *; }
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn javax.mail.**
# Desktop-only Angus paths we never invoke on Android (no SMTP/IMAP/SASL transports,
# no AWT image DataHandlers, no GraalVM native-image agent):
-dontwarn java.awt.**
-dontwarn javax.security.sasl.**
-dontwarn javax.security.auth.callback.**
-dontwarn org.graalvm.**

# --- AppAuth ---
-keep class net.openid.appauth.** { *; }
-dontwarn net.openid.appauth.**
