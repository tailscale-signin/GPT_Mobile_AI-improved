pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            // Resolve the published implementation directly, without a second
            // plugin-marker lookup. Its JAR/module hashes remain verified.
            if (requested.id.id == "org.jetbrains.kotlin.multiplatform" && requested.version != null) {
                useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
            }
            if (requested.id.id == "io.objectbox" && requested.version != null) {
                useModule("io.objectbox:objectbox-gradle-plugin:${requested.version}")
            }
            if (requested.id.id == "com.google.dagger.hilt.android" && requested.version != null) {
                useModule("com.google.dagger:hilt-android-gradle-plugin:${requested.version}")
            }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "GPTMobile"
include(":app")
include(":macrobenchmark")

include(":local-gguf-runtime")

include(":core-domain")
