# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

-dontoptimize
-dontwarn **
-ignorewarnings

# -------------------------------------------------------------
# Aggressive Code & Logging Stripping for Performance
# -------------------------------------------------------------
# Strip Android Log calls completely in release builds to eliminate string allocation & CPU cycles
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

# -------------------------------------------------------------
# Reactive Streams & Netty
# -------------------------------------------------------------
-keep class reactor.** { *; }
-keep interface reactor.** { *; }
-keep class io.micrometer.** { *; }
-keep interface io.micrometer.** { *; }
-dontwarn io.micrometer.**
-dontwarn reactor.**

# AppAuth (Hugging Face OAuth redirect / token exchange)
-keep class net.openid.appauth.** { *; }
-keep interface net.openid.appauth.** { *; }
-dontwarn net.openid.appauth.**

# Ignore missing optional logging dependencies used by Netty
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**

# -------------------------------------------------------------
# Ktor & Coroutines
# -------------------------------------------------------------
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }
-keep interface io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep interface kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# -------------------------------------------------------------
# Model Context Protocol (MCP) Kotlin SDK
# -------------------------------------------------------------
-dontwarn io.modelcontextprotocol.**
-keep class io.modelcontextprotocol.** { *; }
-keep interface io.modelcontextprotocol.** { *; }

# -------------------------------------------------------------
# LiteRT-LM runtime & TensorFlow Lite / Edge
# -------------------------------------------------------------
-dontwarn com.google.ai.edge.**
-keep class com.google.ai.edge.** { *; }
-keep interface com.google.ai.edge.** { *; }
-keep class com.google.android.gms.tflite.** { *; }
-keep interface com.google.android.gms.tflite.** { *; }
-dontwarn com.google.android.gms.tflite.**
# MediaPipe's AAR does not include consumer rules. Its native code also calls
# Java callbacks, Packet.create/getNativeHandle/release, exception constructors
# and SerializedMessage fields by name. Keeping native methods alone lets R8
# rename/remove these Java entry points and causes an ART abort on first input.
# Keep the complete SDK boundary, including callback implementations. The
# privacy transform removes remote loggers before R8 sees these classes.
-keep class com.google.mediapipe.** { *; }
-keep interface com.google.mediapipe.** { *; }
# MediaPipe's model options use protobuf-lite reflection (including fileContent_).
# Preserve generated message fields in minified APKs, as required by protobuf-lite.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
# Don't optimize LiteRT-LM native binding JNI classes
-keepclasseswithmembernames,includedescriptorclasses class com.google.ai.edge.** {
    native <methods>;
}

# -------------------------------------------------------------
# Hilt / Dagger & WorkManager
# -------------------------------------------------------------
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.hilt.work.HiltWorkerFactory { *; }
-dontwarn androidx.hilt.work.**
-keep class dagger.hilt.** { *; }
-keep interface dagger.hilt.** { *; }
-dontwarn dagger.hilt.**

# -------------------------------------------------------------
# Kotlinx Serialization
# -------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses, EnclosingMethod
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclasseswithmembers class * {
    public static *** Companion;
}
-keepclassmembers class * implements kotlinx.serialization.KSerializer {
    public static *** INSTANCE;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# -------------------------------------------------------------
# Kotlin Metadata & Reflection Safeguards
# -------------------------------------------------------------
-keepattributes Signature, Exceptions
-keepclassmembers class kotlin.Metadata { *; }

# SharedPreferences JSON uses these field names across app upgrades. Gson accesses
# them reflectively, so release shrinking must preserve the fields and constructor.
-keep class dev.chungjungsoo.gptmobile.llama.AdvancedSettings { *; }

# Preserve line numbers and source files for release stack traces
-keepattributes SourceFile,LineNumberTable
