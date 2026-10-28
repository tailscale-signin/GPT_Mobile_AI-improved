@file:Suppress("UnstableApiUsage")

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.aboutLibraries
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.android.hilt)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.auto.license)
    jacoco
    kotlin(libs.plugins.kotlin.serialization.get().pluginId).version(libs.versions.kotlin)
    alias(libs.plugins.objectbox)
}

// Separate preview APK: the two QAIRT distributions must never be merged with pickFirst.
val geniexRuntime = providers.gradleProperty("geniexRuntime").orNull == "true"

/** Keep on-device embeddings while removing the SDK's remote statistics implementation. */
@CacheableTransform
abstract class PrivateMemoryRuntime : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val source = inputArtifact.get().asFile
        if (!source.name.contains("tasks-core")) {
            outputs.file(source)
            return
        }
        val destination = outputs.file(source.name)
        ZipInputStream(source.inputStream()).use { input ->
            ZipOutputStream(destination.outputStream()).use { output ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val content = input.readBytes()
                    output.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                    output.write(if (entry.name == "classes.jar") withoutTelemetry(content) else content)
                    output.closeEntry()
                }
            }
        }
    }

    private fun withoutTelemetry(content: ByteArray): ByteArray {
        val result = ByteArrayOutputStream()
        var factoryPatched = false
        ZipInputStream(ByteArrayInputStream(content)).use { input ->
            ZipOutputStream(result).use { output ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    var bytes = input.readBytes()
                    if (entry.name in REMOTE_LOGGERS) continue
                    if (entry.name == "com/google/mediapipe/tasks/core/logging/TasksStatsLoggerFactory.class") {
                        // The two class names have equal UTF-8 length. Only these constant-pool
                        // references change; method descriptors and bytecode offsets stay valid.
                        val original = bytes.toString(Charsets.ISO_8859_1)
                        check(original.contains("TasksStatsProtoLogger")) { "Embedding SDK logger changed; review its privacy integration." }
                        bytes = original.replace("TasksStatsProtoLogger", "TasksStatsDummyLogger").toByteArray(Charsets.ISO_8859_1)
                        check(bytes.size == original.length)
                        factoryPatched = true
                    }
                    output.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                    output.write(bytes)
                    output.closeEntry()
                }
            }
        }
        check(factoryPatched) { "Embedding SDK privacy transform did not replace its statistics factory." }
        return result.toByteArray()
    }

    companion object {
        private val REMOTE_LOGGERS = setOf(
            "com/google/mediapipe/tasks/core/logging/TasksStatsProtoLogger.class",
            "com/google/mediapipe/tasks/core/logging/RemoteLoggingClient.class",
            "com/google/mediapipe/tasks/core/logging/LoggingClient.class"
        )
    }
}

val privateMemoryAttribute = Attribute.of("dev.chungjungsoo.gptmobile.private-memory", Boolean::class.javaObjectType)
configurations.configureEach {
    if (name.endsWith("CompileClasspath") || name.endsWith("RuntimeClasspath")) attributes.attribute(privateMemoryAttribute, true)
    exclude(group = "com.google.android.datatransport")
}

dependencies {
    implementation(project(":local-gguf-runtime"))
    implementation("org.slf4j:slf4j-api:2.0.19")
    artifactTypes.maybeCreate("aar").attributes.attribute(privateMemoryAttribute, false)
    registerTransform(PrivateMemoryRuntime::class) {
        from.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar").attribute(privateMemoryAttribute, false)
        to.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "aar").attribute(privateMemoryAttribute, true)
    }
}

extensions.configure<ApplicationExtension> {
    namespace = "dev.chungjungsoo.gptmobile"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    // AGP's built-in Kotlin compiler does not consume Kotlin files added to Java source roots.
    sourceSets.getByName("main").kotlin.directories += if (geniexRuntime) "src/geniex/kotlin" else "src/litertOnly/kotlin"
    defaultConfig {
        buildConfigField("boolean", "LOCAL_INFERENCE_PROCESS", (providers.gradleProperty("localInferenceProcess").orNull != "false").toString())
        applicationId = if (geniexRuntime) "dev.melo.gptmobile.improved.geniex" else "dev.melo.gptmobile.improved"
        buildConfigField("boolean", "GENIEX_ENABLED", geniexRuntime.toString())
        buildConfigField("boolean", "GGUF_ENABLED", (providers.gradleProperty("ggufRuntime").orNull != "false").toString())
        val ggufVulkan = providers.gradleProperty("ggufVulkan").map { it != "false" }.getOrElse(true)
        buildConfigField("boolean", "GGUF_VULKAN_ENABLED", ggufVulkan.toString())
        buildConfigField("boolean", "GGUF_OPENCL_ENABLED", providers.gradleProperty("ggufOpencl").map { it != "false" }.getOrElse(true).toString())
        buildConfigField("String", "GENIEX_VERSION", "\"${libs.versions.geniex.get()}\"")
        // Independent rollback controls. Mandatory parser/admission hard caps remain enabled.
        buildConfigField("boolean", "COMBINED_SECTION_RECOVERY", providers.gradleProperty("combinedSectionRecovery").map { (it != "false").toString() }.getOrElse("true"))
        // Available as an explicit per-conversation choice; existing and new chats stay strict by default.
        buildConfigField("boolean", "COMBINED_APPEND_CONTINUATIONS", providers.gradleProperty("combinedAppendContinuations").map { (it != "false").toString() }.getOrElse("true"))
        buildConfigField("boolean", "MCP_ROUTE_RECOVERY", providers.gradleProperty("mcpRouteRecovery").map { (it != "false").toString() }.getOrElse("true"))
        buildConfigField("boolean", "TOOL_STORM_FINALIZATION", providers.gradleProperty("toolStormFinalization").map { (it != "false").toString() }.getOrElse("true"))
        minSdk = 31
        targetSdk = 37
        // Interim release: Play-ready artifact gates remain mandatory while full
        // Unified Reliability v2 milestone acceptance is still in progress.
        versionCode = 131
        versionName = "0.9.63.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Public OAuth client configuration; never put client secrets in an APK.
        val hfClientId = providers.gradleProperty("HF_OAUTH_CLIENT_ID").orElse(providers.environmentVariable("HF_OAUTH_CLIENT_ID")).getOrElse("")
        val hfRedirect = providers.gradleProperty("HF_OAUTH_REDIRECT_URI").orElse(providers.environmentVariable("HF_OAUTH_REDIRECT_URI")).getOrElse("")
        require(hfClientId.all { it.isLetterOrDigit() || it in "-_" }) { "Invalid HF OAuth client ID" }
        require(hfRedirect.isEmpty() || Regex("[a-z][a-z0-9+.-]*://[A-Za-z0-9/_.-]+").matches(hfRedirect)) { "Invalid HF OAuth redirect URI" }
        manifestPlaceholders["appAuthRedirectScheme"] = hfRedirect.substringBefore(":").ifEmpty { "gptmobile-hf-unconfigured" }
        buildConfigField("String", "APP_COMMIT", "\"${providers.environmentVariable("GITHUB_SHA").orElse(providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.map { it.trim() }).getOrElse("unknown")}\"")
        buildConfigField("String", "LITERT_LM_VERSION", "\"${libs.versions.litertlm.get()}\"")
        buildConfigField("String", "QAIRT_VERSION", "\"${if (geniexRuntime) "bundled-geniex-${libs.versions.geniex.get()}" else libs.versions.qnn.get()}\"")
        buildConfigField("String", "HF_OAUTH_CLIENT_ID", "\"$hfClientId\"")
        buildConfigField("String", "HF_OAUTH_REDIRECT_URI", "\"$hfRedirect\"")
        val registeredGitHubClientId = "Iv23liZ80mi4wzhJIrWM"
        val githubClientId = providers.gradleProperty("GITHUB_OAUTH_CLIENT_ID").orElse(providers.environmentVariable("GITHUB_OAUTH_CLIENT_ID")).getOrElse(registeredGitHubClientId)
        require(githubClientId.all { it.isLetterOrDigit() || it in "-_." }) { "Invalid GitHub OAuth client ID" }
        buildConfigField("String", "GITHUB_OAUTH_CLIENT_ID", "\"$githubClientId\"")
        val githubAppSlug = providers.gradleProperty("GITHUB_APP_SLUG").orElse(providers.environmentVariable("GITHUB_APP_SLUG")).getOrElse(if (githubClientId == registeredGitHubClientId) "gpt-mobile-ai-workspace" else "")
        require(githubAppSlug.isEmpty() || githubAppSlug.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,98}[a-z0-9])?"))) { "Invalid GitHub App slug" }
        buildConfigField("String", "GITHUB_APP_SLUG", "\"$githubAppSlug\"")
        val githubBroker = providers.gradleProperty("GITHUB_AUTH_BROKER_ORIGIN").orElse(providers.environmentVariable("GITHUB_AUTH_BROKER_ORIGIN")).getOrElse("").trimEnd('/')
        val githubBrokerUri = githubBroker.takeIf(String::isNotEmpty)?.let(::URI)
        require(githubBrokerUri == null || (githubBrokerUri.scheme == "https" && githubBrokerUri.host != null && githubBrokerUri.userInfo == null && githubBrokerUri.port in setOf(-1, 443) && githubBrokerUri.path.isNullOrEmpty() && githubBrokerUri.query == null && githubBrokerUri.fragment == null && githubClientId.isNotEmpty())) { "GitHub public sign-in requires the registered client ID and a fixed HTTPS broker origin." }
        val githubCallbackHost = providers.gradleProperty("GITHUB_AUTH_CALLBACK_HOST").orElse(providers.environmentVariable("GITHUB_AUTH_CALLBACK_HOST")).getOrElse(githubBrokerUri?.host ?: "github-auth.invalid")
        require(githubCallbackHost.length <= 253 && githubCallbackHost.split('.').all { it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) }) { "Invalid GitHub verified callback host." }
        require(githubBrokerUri == null || !githubCallbackHost.endsWith(".invalid")) { "Configure a project-owned verified HTTPS callback host before enabling public sign-in." }
        buildConfigField("String", "GITHUB_AUTH_BROKER_ORIGIN", "\"$githubBroker\"")
        buildConfigField("String", "GITHUB_AUTH_CALLBACK_URI", "\"https://$githubCallbackHost/oauth/github/callback\"")
        manifestPlaceholders["githubAuthCallbackHost"] = githubCallbackHost
        manifestPlaceholders["githubPublicAuthEnabled"] = githubBroker.isNotBlank().toString()
        // App owner confirmed LLM7 integration approval on 2026-09-26.
        // A build can explicitly disable the integration if approval changes.
        buildConfigField("boolean", "FREE_LLM7_APPROVED", providers.gradleProperty("freeLlm7Approved").map { (it == "true").toString() }.getOrElse("true"))

        ndk {
            // Target 64-bit modern high-performance ABIs (eliminates 32-bit legacy overhead)
            abiFilters += if (geniexRuntime) listOf("arm64-v8a") else listOf("arm64-v8a", "x86_64")
        }
    }

    splits {
        abi {
            isEnable = providers.gradleProperty("enableAbiSplits").orNull != "false"
            reset()
            include(*(if (geniexRuntime) arrayOf("arm64-v8a") else arrayOf("arm64-v8a", "x86_64")))
            isUniversalApk = true
        }
    }

    androidResources {
        generateLocaleConfig = true
        noCompress += "tflite"
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Existing lint debt is recorded after review; new errors fail this gate.
        warning += "MissingTranslation"
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildTypes.create("benchmark") {
        initWith(buildTypes.getByName("release"))
        signingConfig = signingConfigs.getByName("debug")
        matchingFallbacks += listOf("release")
        isDebuggable = false
        isMinifyEnabled = false
        isShrinkResources = false
    }
    // Exercise the production R8/native boundary without release signing keys
    // or touching an installed app's data. Normal device tests remain debug.
    if (providers.gradleProperty("nativeMemorySmoke").orNull == "true") {
        buildTypes.create("nativeSmoke") {
            initWith(buildTypes.getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".nativesmoke"
            matchingFallbacks += listOf("release")
            // The runner/test APK also uses shared APIs that the app alone can
            // remove (for example kotlin.LazyKt). These roots are test-only.
            proguardFiles("app-native-smoke-rules.pro")
            // The runner selects tests by their source class name. R8 also
            // shrinks the test APK, so keep its reflection/discovery entry point.
            testProguardFiles("native-smoke-test-rules.pro")
        }
        testBuildType = "nativeSmoke"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Thousands of Robolectric tests retain SDK class loaders. Keep
                // normal-suite garbage from triggering production heap admission
                // in later reader tests; the separate 256 MiB gate stays explicit.
                it.maxHeapSize = "1024m"
                it.maxParallelForks = 1
                it.forkEvery = 64
                // Robolectric's real-descriptor interceptor needs these JDK 21 test-only APIs.
                it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
                it.systemProperty("room.schemas", file("schemas").absolutePath)
                it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
                it.testLogging {
                    events("passed", "skipped", "failed", "standardError")
                }
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
        jniLibs {
            // Extract native libraries to nativeLibraryDir on installation so Qualcomm FastRPC cDSP can load libQnnHtpV79Skel.so directly from the filesystem
            useLegacyPackaging = true
            // Keep pre-stripped native libraries without triggering stripping warnings
            keepDebugSymbols += setOf(
                "**/libLiteRt.so",
                "**/libLiteRtClGlAccelerator.so",
                "**/libLiteRtDispatch_Qualcomm.so",
                "**/libLiteRtCompilerPlugin_Qualcomm.so",
                "**/liblitertlm_jni.so",
                "**/liblitertlm_apple_framework.so",
                "**/libYnnpack*.so",
                "**/libdatastore_shared_counter.so",
                "**/libandroidx.graphics.path.so",
                "**/libQnn*.so"
            )
            // LLM packages are AOT compiled. Keep one QAIRT version from qnn-runtime;
            // do not mix it with checked-in HTP stubs/skeletons via pickFirsts.
            // libQnnHtpPrepare is part of Qualcomm's documented LiteRT dispatch
            // runtime, including for precompiled contexts, and must be packaged.
            excludes += setOf("**/libQnnDsp*.so", "**/libQnnGpu.so")
            if (geniexRuntime) excludes += "**/libLiteRtDispatch_Qualcomm.so"
        }
    }
}

@CacheableTask
abstract class PrepareMemoryModel : DefaultTask() {
    @get:Input abstract val sourceUrl: Property<String>

    @get:Input abstract val expectedSha256: Property<String>

    @get:Input abstract val offline: Property<Boolean>

    @get:Optional @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val localModel: RegularFileProperty

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val target = outputDirectory.file("memory/universal_sentence_encoder.tflite").get().asFile
        fun checksum(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        if (target.isFile && checksum(target) == expectedSha256.get()) return
        target.parentFile.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.part")
        try {
            val input = if (localModel.isPresent) {
                localModel.get().asFile.inputStream()
            } else {
                check(!offline.get()) { "Memory model is not cached. Build online once or provide -PmemoryModelFile=/path/to/universal_sentence_encoder.tflite." }
                URI(sourceUrl.get()).toURL().openConnection().apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                }.getInputStream()
            }
            input.use { source ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= 6_120_274) { "Unexpected memory model size." }
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(checksum(temporary) == expectedSha256.get()) { "Memory model checksum mismatch; refusing to package it." }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}

val prepareMemoryModel = tasks.register<PrepareMemoryModel>("prepareMemoryModel") {
    sourceUrl.set("https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/1/universal_sentence_encoder.tflite")
    expectedSha256.set("89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749")
    offline.set(gradle.startParameter.isOffline)
    providers.gradleProperty("memoryModelFile").orNull?.let { localModel.set(file(it)) }
    outputDirectory.set(layout.buildDirectory.dir("generated/memoryAssets"))
}

extensions.configure<ApplicationAndroidComponentsExtension> {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareMemoryModel, PrepareMemoryModel::outputDirectory)
        variant.androidTest?.sources?.assets?.addStaticSourceDirectory("$projectDir/schemas")
        if (variant.buildType in setOf("release", "benchmark", "nativeSmoke")) {
            // Compress DEX in directly distributed APKs to keep the existing download
            // budgets without dropping a runtime or ABI. App-bundle packaging is separate.
            variant.packaging.dex.useLegacyPackaging.set(true)
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// ObjectBox 5.4.2 retains Project in its preparation task. Keep incremental/build
// caches enabled, but tell Gradle honestly that this task cannot be serialized.
tasks.matching { it.name == "objectboxPrepareBuild" }.configureEach {
    notCompatibleWithConfigurationCache("ObjectBox preparation currently retains Gradle Project")
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    val fileFilter = setOf(
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Test*.*",
        "android/**/*.*",
        "**/*_Hilt*.class",
        "**/Hilt_*.class",
        "**/*_Factory.class",
        "**/*_MembersInjector.class"
    )

    // AGP 9's built-in Kotlin compiler no longer writes tmp/kotlin-classes/debug.
    // Follow the compiler outputs so a toolchain/output-directory change cannot
    // silently produce a report with execution sessions but no application classes.
    val kotlinClasses = tasks.named<KotlinCompile>("compileDebugKotlin").flatMap { it.destinationDirectory }
    val javaClasses = tasks.named<JavaCompile>("compileDebugJavaWithJavac").flatMap { it.destinationDirectory }
    val mainSources = files(
        "$projectDir/src/main/java",
        "$projectDir/src/main/kotlin"
    )

    sourceDirectories.setFrom(mainSources)
    classDirectories.setFrom(
        listOf(kotlinClasses, javaClasses).map { directory ->
            directory.map { it.asFileTree.matching { exclude(fileFilter) } }
        }
    )
    executionData.setFrom(
        fileTree(buildDir) {
            include(
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
                "jacoco/testDebugUnitTest.exec"
            )
        }
    )
}

dependencies {
    implementation("androidx.window:window:1.5.1")
    // Local memory only: no model provider, hosted vector store or remote embedding SDK.
    implementation(libs.langchain4j.memory) {
        exclude(group = "org.apache.opennlp", module = "opennlp-tools")
        exclude(group = "io.smallrye.reactive", module = "mutiny-zero")
    }
    implementation(libs.mediapipe.text)
    // Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.health.connect)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.viewmodel)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.material.views)

    // Location
    implementation(libs.maplibre)

    // SplashScreen
    implementation(libs.splashscreen)

    // DataStore
    implementation(libs.androidx.datastore)

    // Dependency Injection
    implementation(libs.hilt)
    implementation(libs.androidx.lifecycle.runtime.compose.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.work.runtime.ktx)

    // Ktor
    implementation(libs.ktor.content.negotiation)
    implementation(libs.ktor.core)
    implementation(libs.ktor.client.encoding)
    implementation(libs.jsoup)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.logging)
    implementation(libs.ktor.serialization)
    implementation(libs.mcp.kotlin.sdk.client)

    // OAuth browser flow
    implementation(libs.androidx.browser)
    implementation(libs.openid.appauth)

    // Document text extraction for cloud and on-device models
    implementation(libs.pdfbox)
    implementation(libs.poi)
    implementation(libs.poi.scratchpad)

    // JSON parsing
    implementation(libs.gson)

    // On-device LiteRT-LM serving
    // AndroidX Compose, Activity, Collection and tracing are already supplied by this app.
    // Avoid parallel JetBrains Compose wrappers selecting a second platform dependency graph.
    implementation("dev.chrisbanes.haze:haze-android:1.7.3") { isTransitive = false }
    implementation(libs.litertlm)

    // QAIRT host libraries and matching HTP stubs/skeletons for LiteRT-LM NPU dispatch.
    if (geniexRuntime) {
        implementation(libs.geniex.android)
    } else {
        implementation(libs.qnn.runtime)
    }

    // License page UI
    implementation(libs.auto.license.core)
    implementation(libs.auto.license.ui)

    // Markdown
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.code)

    // Navigation
    implementation(libs.hilt.navigation)
    implementation(libs.androidx.navigation)

    // Room
    implementation(libs.room)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    // Serialization
    implementation(libs.kotlin.serialization)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.mockito.core)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

aboutLibraries {
    // Remove the "generated" timestamp to allow for reproducible builds
    export {
        excludeFields.add("generated")
    }
}

// Ship the same reviewed hash manifest used by APK CI, without duplicating pinned identities.
val qnnAuditAssets = tasks.register<Copy>("prepareQnnAuditAssets") {
    from(rootProject.file("docs/audits/local-runtime-native-libraries.json"))
    into(layout.buildDirectory.dir("generated/qnnAuditAssets"))
}
extensions.configure<ApplicationExtension> {
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/qnnAuditAssets").get().asFile)
}
tasks.named("preBuild").configure { dependsOn(qnnAuditAssets) }
