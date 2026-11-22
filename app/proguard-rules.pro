# kotlinx.serialization models (library ships its own rules; these keep saved history and routes intact).
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep,includedescriptorclasses class com.vknxworks.wakectrl.**$$serializer { *; }
-keepclassmembers class com.vknxworks.wakectrl.** {
    *** Companion;
}
-keepclasseswithmembers class com.vknxworks.wakectrl.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Google-free: Guava's listenablefuture jar is excluded from the build (see app/build.gradle.kts).
# AndroidX's concurrent-futures still names its interface in classes WakeCtrl never uses.
-dontwarn com.google.common.util.concurrent.ListenableFuture

# Release builds drop every android.util.Log call (from the app and its libraries), so no log line can reach
# the phone's system log from a release build, even one added later by mistake.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
    public static boolean isLoggable(java.lang.String, int);
}
