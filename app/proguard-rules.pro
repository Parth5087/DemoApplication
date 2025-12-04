# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
-keepclassmembers class fqcn.of.javascript.interface.for.webview {
   public *;
}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
-renamesourcefileattribute SourceFile

# =====================================================
# GSON SPECIFIC RULES (FIXES TypeToken ERROR)
# =====================================================

# Keep generic type information for Gson
-keepattributes Signature

# Keep TypeToken classes and their generic information (CRITICAL FIX)
-keep class * extends com.google.gson.reflect.TypeToken { *; }

# Keep Gson classes
-keep class com.google.gson.** { *; }
-keep class com.google.gson.stream.** { *; }

# Keep classes that use Gson annotations
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.* <fields>;
}

# Keep generic type information
-keep class * implements java.lang.reflect.Type { *; }

# Keep sun.misc.Unsafe for Gson
-keep class sun.misc.Unsafe { *; }
-dontwarn sun.misc.**

# =====================================================
# GENERAL ANDROID/R8 RULES
# =====================================================
-keepattributes *Annotation*,EnclosingMethod,Signature,InnerClasses
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeInvisibleAnnotations,RuntimeInvisibleParameterAnnotations

# Kotlin rules
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ObjectBox rules (minimal, as library includes most)
-keep class io.objectbox.** { *; }
-dontwarn io.objectbox.**

# Firebase / Crashlytics / Google Services rules
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-keep class com.crashlytics.** { *; }
-dontwarn com.crashlytics.**
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# For Firebase Crashlytics deobfuscation (upload mapping.txt via Firebase CLI or console)
# See: https://firebase.google.com/docs/crashlytics/get-deobfuscated-reports

# Additional common rules for reflection/desugaring
-keep class **$* { *; }  # For Kotlin lambdas/coroutines
#-keep class * extends java.util.ListResourceBundle {
#    protected Object[][] getContents();
#}

# If using enums with reflection (e.g., in Firebase)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Rules for AutoValue and annotation processors (compile-time only)
# These suppress warnings for javax.lang.model and related compiler APIs
# which are not needed at runtime but referenced in processor jars
-dontwarn javax.lang.model.**
-dontwarn javax.tools.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.**
-keep class com.google.auto.value.** { *; }  # Keep generated AutoValue classes

# Retrofit rules
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions

# OkHttp
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**

# Keep API interfaces and methods for Retrofit (prevents obfuscation of signatures)
-keepclasseswithmembers class * {
    @retrofit2.* <methods>;
}

-keepclasseswithmembers interface * {
    @retrofit2.* <methods>;
}

-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Keep request/response data classes to preserve generic types
-keep class com.uav.analytics.models.** { *; }
-keep class com.uav.analytics.domain.analytics.** { *; }