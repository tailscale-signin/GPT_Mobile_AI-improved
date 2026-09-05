# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

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
-keep class io.micrometer.** { *; }
-dontwarn io.micrometer.**
-dontwarn reactor.**

# AppAuth (Hugging Face OAuth redirect / token exchange)
-keep class net.openid.appauth.** { *; }
-dontwarn net.openid.appauth.**

# Ignore missing optional logging dependencies used by Netty
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**

# -------------------------------------------------------------
# Ktor & Coroutines
# -------------------------------------------------------------
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }

# -------------------------------------------------------------
# Model Context Protocol (MCP) Kotlin SDK
# -------------------------------------------------------------
-dontwarn io.modelcontextprotocol.**
-keep class io.modelcontextprotocol.** { *; }

# -------------------------------------------------------------
# LiteRT-LM runtime
# -------------------------------------------------------------
-dontwarn com.google.ai.edge.**
-keep class com.google.ai.edge.** { *; }

# -------------------------------------------------------------
# Kotlinx Serialization
# -------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class * {
    companion object *;
}
-keepclasseswithmembers class * {
    public static *** Companion;
}

# Preserve line numbers and source files for release stack traces
-keepattributes SourceFile,LineNumberTable
