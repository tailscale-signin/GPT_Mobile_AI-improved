# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-dontwarn **
-ignorewarnings

# Keep reactive streams dependencies
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

# Ktor & Coroutines
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }

# Model Context Protocol Kotlin SDK
-dontwarn io.modelcontextprotocol.**
-keep class io.modelcontextprotocol.** { *; }

# LiteRT-LM runtime
-dontwarn com.google.ai.edge.**
-keep class com.google.ai.edge.** { *; }

# Kotlinx Serialization
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
