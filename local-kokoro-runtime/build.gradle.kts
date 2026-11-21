import com.android.build.api.dsl.LibraryExtension

plugins { id("com.android.library") }

extensions.configure<LibraryExtension> {
    namespace = "dev.melo.kokoro"
    compileSdk = 37
    ndkVersion = "29.0.14206865"
    defaultConfig {
        minSdk = 31
        ndk {
            abiFilters += if (providers.gradleProperty("geniexRuntime").orNull == "true") listOf("arm64-v8a") else listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_static" }
        }
        consumerProguardFiles("consumer-rules.pro")
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
