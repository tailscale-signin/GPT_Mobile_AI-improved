plugins { id("com.android.library") }

val ggufVulkan = providers.gradleProperty("ggufVulkan").map { it != "false" }.getOrElse(true)
val ggufInventoryTemplate = layout.projectDirectory.file("src/main/gguf-runtime-inventory.json.in")
val ggufInventoryAssets = layout.buildDirectory.dir("generated/ggufInventory/assets")
val generateGgufRuntimeInventory by tasks.registering {
    inputs.file(ggufInventoryTemplate)
    inputs.property("ggufVulkan", ggufVulkan)
    outputs.dir(ggufInventoryAssets)
    doLast {
        val backends = if (ggufVulkan) "[\"cpu\",\"gpu\"]" else "[\"cpu\"]"
        val output = ggufInventoryAssets.get().file("gguf-runtime-inventory.json").asFile
        output.parentFile.mkdirs()
        output.writeText(ggufInventoryTemplate.asFile.readText().replace("@GGUF_BACKENDS@", backends))
    }
}

android {
    namespace = "dev.melo.gguf"
    compileSdk = 37
    ndkVersion = "29.0.14206865"
    sourceSets.getByName("main").assets.srcDir(ggufInventoryAssets)
    defaultConfig {
        minSdk = 31
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-DGGUF_VULKAN=$ggufVulkan") }
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

tasks.named("preBuild") { dependsOn(generateGgufRuntimeInventory) }
