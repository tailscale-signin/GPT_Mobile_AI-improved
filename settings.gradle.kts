pluginManagement {
    repositories {
        google()
        // On a qualified retry, resolve the entire plugin classpath from Central's
        // mirror. Plugin marker POMs also need their parents and transitive modules.
        if (providers.environmentVariable("GPTMOBILE_PLUGIN_RESOLUTION_RECOVERY").orNull == "1") {
            maven {
                name = "PublishedPluginRecovery"
                url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
            }
        }
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
        if (providers.environmentVariable("GPTMOBILE_PLUGIN_RESOLUTION_RECOVERY").orNull == "1") {
            maven {
                name = "PublishedDependencyRecovery"
                url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
            }
        }
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        exclusiveContent {
            forRepository {
                ivy {
                    name = "SherpaOnnxAndroidReleases"
                    url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
                    patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("com.k2fsa.sherpa.onnx", "sherpa-onnx-static-link-onnxruntime") }
        }
    }
}

rootProject.name = "GPTMobile"
include(":app")
include(":macrobenchmark")

include(":local-gguf-runtime")

include(":core-domain")
