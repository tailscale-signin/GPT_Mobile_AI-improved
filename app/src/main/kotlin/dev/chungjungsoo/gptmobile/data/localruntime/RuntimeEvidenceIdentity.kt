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
        val artifact = PackageDigest.validateInstalledCached(File(spec.modelPath), cache)
        val dispatch = context.applicationInfo.nativeLibraryDir?.let(::File)?.listFiles().orEmpty()
            .filter { it.name.contains("Dispatch", true) || it.name.startsWith("libQnn") || it.name.contains("litertlm", true) }
            .sortedBy { it.name }.joinToString("|") { it.name + ":" + PackageDigest.journalIdentity(it, cache) }
        RuntimeQualificationKey(
            deviceIdentity = hash("${Build.MANUFACTURER}|${Build.MODEL}|${Build.SOC_MODEL}|${android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)}"),
            firmwareDigest = hash(Build.FINGERPRINT),
            runtimeIdentity = "${BuildConfig.LITERT_LM_VERSION}|${BuildConfig.QAIRT_VERSION}|${BuildConfig.GENIEX_ENABLED}",
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
