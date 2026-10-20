import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension

plugins { id("com.android.library") }

// Match the app's preview ABI policy; GenieX packages ARM64 only.
val ggufAbis = if (providers.gradleProperty("geniexRuntime").orNull == "true") listOf("arm64-v8a") else listOf("arm64-v8a", "x86_64")
val ggufVulkan = providers.gradleProperty("ggufVulkan").map { it != "false" }.getOrElse(true)

abstract class GenerateGgufRuntimeInventory : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val template: RegularFileProperty

    @get:Input
    abstract val vulkanEnabled: Property<Boolean>

    @get:Input
    abstract val packagedAbis: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val backends = if (vulkanEnabled.get()) "[\"cpu\",\"gpu\"]" else "[\"cpu\"]"
        val output = outputDirectory.get().file("gguf-runtime-inventory.json").asFile
        output.parentFile.mkdirs()
        val abis = packagedAbis.get().joinToString(separator = ",", prefix = "[", postfix = "]") { "\"$it\"" }
        output.writeText(template.get().asFile.readText().replace("@GGUF_BACKENDS@", backends).replace("@GGUF_ABIS@", abis))
    }
}

val generateGgufRuntimeInventory = tasks.register<GenerateGgufRuntimeInventory>("generateGgufRuntimeInventory") {
    template.set(layout.projectDirectory.file("src/main/gguf-runtime-inventory.json.in"))
    vulkanEnabled.set(ggufVulkan)
    packagedAbis.set(ggufAbis)
    outputDirectory.set(layout.buildDirectory.dir("generated/ggufInventory/assets"))
}

extensions.configure<LibraryExtension> {
    namespace = "dev.melo.gguf"
    compileSdk = 37
    ndkVersion = "29.0.14206865"
    defaultConfig {
        minSdk = 31
        ndk { abiFilters += ggufAbis }
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

extensions.configure<LibraryAndroidComponentsExtension> {
    onVariants(selector().all()) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            generateGgufRuntimeInventory,
            GenerateGgufRuntimeInventory::outputDirectory
        )
    }
}
