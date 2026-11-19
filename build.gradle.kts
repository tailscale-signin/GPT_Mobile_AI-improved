// Top-level build file where you can add configuration options common to all sub-projects/modules.
// These constraints cover the Android build plugins, which run outside the APK dependency graph.
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
    dependencies.constraints {
        listOf(
            "com.google.guava:guava:33.7.2-jre",
            "com.squareup.okio:okio:3.18.2",
            "org.apache.commons:commons-lang3:3.18.0",
            "org.bitbucket.b_c:jose4j:0.9.6",
            "org.bouncycastle:bcpkix-jdk18on:1.86",
            "org.bouncycastle:bcprov-jdk18on:1.86",
            "org.jdom:jdom2:2.0.6.1"
        ).forEach { add("classpath", it) }
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.objectbox) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.hilt) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.ksp) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.auto.license).version(libs.versions.autoLicense) apply false
    kotlin(libs.plugins.kotlin.serialization.get().pluginId).version(libs.versions.kotlin).apply(false)
}
