-printmapping 'build/proguard-mapping.txt'
-ignorewarnings
-dontwarn **

#-verbose
#-addconfigurationdebugging

# https://github.com/Guardsquare/proguard/issues/302
-optimizations "!method/inlining/unique"
# https://github.com/Guardsquare/proguard/issues/307
# https://github.com/Guardsquare/proguard/issues/386
-optimizations "!method/specialization/*"
-optimizations "!field/specialization/*"

-keepattributes Signature,SourceFile,LineNumberTable,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

-renamesourcefileattribute SourceFile
-repackageclasses 'animurglar'
-allowaccessmodification

-keep public class * extends java.lang.Exception

# https://github.com/Guardsquare/proguard/issues/296#issuecomment-1321920023
# it removes original metadata and replaces with actual obfuscated one
-keep class kotlin.Metadata

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }

# Keep `Companion` object fields of serializable classes.
# This avoids serializer lookup through `getDeclaredClasses` as done for named companion objects.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
# Keep `serializer()` on companion objects (both default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
# Don't print notes about potential mistakes or omissions in the configuration for kotlinx-serialization classes
# See also https://github.com/Kotlin/kotlinx.serialization/issues/1900
-dontnote kotlinx.serialization.**
# Serialization core uses `java.lang.ClassValue` for caching inside these specified classes.
# If there is no `java.lang.ClassValue` (for example, in Android), then R8/ProGuard will print a warning.
# However, since in this case they will not be used, we can disable these warnings
-dontwarn kotlinx.serialization.internal.ClassValueReferences


# Allow R8 to optimize away the FastServiceLoader.
# Together with ServiceLoader optimization in R8
# this results in direct instantiation when loading Dispatchers.Main
-assumenosideeffects class kotlinx.coroutines.internal.MainDispatcherLoader {
    boolean FAST_SERVICE_LOADER_ENABLED return false;
}
-assumenosideeffects class kotlinx.coroutines.internal.FastServiceLoaderKt {
    boolean ANDROID_DETECTED return true;
}
# Disable support for "Missing Main Dispatcher", since we always have Android main dispatcher
-assumenosideeffects class kotlinx.coroutines.internal.MainDispatchersKt {
    boolean SUPPORT_MISSING return false;
}
# Statically turn off all debugging facilities and assertions
-assumenosideeffects class kotlinx.coroutines.DebugKt {
    boolean getASSERTIONS_ENABLED() return false;
    boolean getDEBUG() return false;
    boolean getRECOVER_STACK_TRACES() return false;
}
# Most of volatile fields are updated with AFU and should not be mangled
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
# Same story for the standard library's SafeContinuation that also uses AtomicReferenceFieldUpdater
-keepclassmembers class kotlin.coroutines.SafeContinuation {
    volatile <fields>;
}
-keep class kotlinx.coroutines.swing.** { *; }


-keepclassmembers enum * {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class org.apache.logging.** { *; }
-keep class org.apache.commons.logging.** { *; }
-keep class org.apache.log4j.** { *; }
-keep class org.slf4j.** { *; }


# Most of volatile fields are updated with AtomicFU and should not be mangled/removed
-keepclassmembers class io.ktor.** {
    volatile <fields>;
}
-keepclassmembernames class io.ktor.** {
    volatile <fields>;
}
-keepclassmembers class io.ktor.http.** { *; }
-keep class * implements io.ktor.client.HttpClientEngineContainer
-keep interface io.ktor.client.HttpClientEngineContainer

-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
# иначе логгер оказывается в общем пакете
-keepnames class okhttp3.OkHttpClient
-keepnames class okhttp3.internal.http2.Http2
-keepnames class okhttp3.internal.concurrent.TaskRunner

-keep class * implements com.arkivanov.decompose.mainthread.MainThreadChecker
-keep interface com.arkivanov.decompose.mainthread.MainThreadChecker

-keep class org.bytedeco.** {*;}
