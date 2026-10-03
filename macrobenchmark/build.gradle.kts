plugins {
    id("com.android.test")
}

android {
    namespace = "dev.chungjungsoo.gptmobile.benchmark"
    compileSdk = 37
    defaultConfig {
        minSdk = 31
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    targetProjectPath = ":app"
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    experimentalProperties["android.experimental.self-instrumenting"] = true
}
androidComponents { beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" } }
dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.core)
}
