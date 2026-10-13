package dev.chungjungsoo.gptmobile.data.localruntime

import android.content.Context
import android.os.Build
import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object RuntimeEvidenceIdentity {
    fun hash(value: String): String = PackageDigest.sha256(value.toByteArray(Charsets.UTF_8))

    suspend fun key(context: Context, spec: LocalEngineSpec, sampler: String = "runtime_default", workload: String = "plain_text"): RuntimeQualificationKey = withContext(Dispatchers.IO) {
        val cache = File(context.noBackupFilesDir, "model-identity-cache")
        val artifact = artifactDigest(File(spec.modelPath), cache)
        val artifactRuntime = if (spec.modelPath.endsWith("manifest.json")) dev.chungjungsoo.gptmobile.data.localmodel.ArtifactManifestStore.read(File(spec.modelPath)).runtime else null
        val scope = RuntimeBinaryScope.resolve(artifactRuntime, spec.accelerator, spec.visionAccelerator.takeIf { spec.isVisionEnabled }, BuildConfig.GENIEX_ENABLED)
        val libraryDirectories = listOfNotNull(context.applicationInfo.nativeLibraryDir, spec.litertDispatchLibDir).distinct()
        val dispatch = libraryDirectories.flatMapIndexed { index, path ->
            File(path).listFiles().orEmpty().filter { scope.includesLibrary(it.name) }.map { file ->
                "$index:${file.name}:" + PackageDigest.journalIdentity(file, cache)
            }
        }.sorted().joinToString("|")
        RuntimeQualificationKey(
            deviceIdentity = hash("${Build.MANUFACTURER}|${Build.MODEL}|${Build.SOC_MODEL}|${android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)}"),
            firmwareDigest = hash(Build.FINGERPRINT),
            runtimeIdentity = scope.identity(RuntimeBinaryVersions(BuildConfig.LITERT_LM_VERSION, BuildConfig.QAIRT_VERSION, BuildConfig.GENIEX_VERSION, RuntimeCapabilityRegistry.GGUF_REVISION)),
            dispatchDigest = hash(dispatch),
            artifactDigest = artifact,
            backend = spec.accelerator,
            visionBackend = spec.visionAccelerator.takeIf { spec.isVisionEnabled },
            contextTokens = spec.maxTokens,
            samplerIdentity = sampler,
            speculativeDecoding = spec.speculativeDecoding,
            workload = workload,
            cpuThreads = spec.cpuThreads,
            cacheEnabled = spec.cacheEnabled
        )
    }

    internal fun artifactDigest(model: File, cache: File): String {
        val manifestDigest = PackageDigest.validateInstalledCached(model, cache)
        if (!model.name.endsWith("manifest.json")) return manifestDigest
        val manifest = dev.chungjungsoo.gptmobile.data.localmodel.ArtifactManifestStore.read(model)
        require(manifest.files.isNotEmpty() && manifest.files.size <= 256 && manifest.files.map { it.path.lowercase() }.distinct().size == manifest.files.size) { "Invalid artifact file inventory" }
        val actualFiles = manifest.files.sortedBy { it.path }.map { declared ->
            val file = dev.chungjungsoo.gptmobile.data.localmodel.ModelArtifactManifest.safeFile(requireNotNull(model.parentFile), declared.path)
            require(file.isFile && file.length() == declared.sizeBytes && declared.sizeBytes > 0) { "Missing or truncated model asset" }
            val actual = PackageDigest.journalIdentity(file, cache)
            require(actual.equals(declared.sha256, ignoreCase = true)) { "Model asset checksum mismatch; native initialization blocked" }
            declared.path + ":" + actual
        }
        return hash(manifestDigest + "|" + actualFiles.joinToString("|"))
    }

    fun plan(key: RuntimeQualificationKey): LocalExecutionPlan = LocalExecutionPlan(
        id = hash(key.toString()),
        artifactDigest = key.artifactDigest,
        runtimeFamily = key.runtimeIdentity,
        languageBackend = key.backend,
        visionBackend = key.visionBackend,
        speculativeDecoding = key.speculativeDecoding,
        contextTokens = key.contextTokens,
        tokenizerTemplateIdentity = key.artifactDigest
    )
}
