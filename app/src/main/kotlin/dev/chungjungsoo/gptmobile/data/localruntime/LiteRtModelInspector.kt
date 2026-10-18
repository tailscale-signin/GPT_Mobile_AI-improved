package dev.chungjungsoo.gptmobile.data.localruntime

import com.google.ai.edge.litertlm.Modality
import com.google.ai.edge.litertlm.ModelInfo
import dev.chungjungsoo.gptmobile.BuildConfig
import java.io.File

/** Export metadata is a capability declaration, not an on-device qualification badge. */
@kotlinx.serialization.Serializable
data class LocalModelCapabilities(
    val contextTokens: Int? = null,
    val dynamicContext: Boolean = false,
    val backends: Set<String> = emptySet(),
    val vision: Boolean? = null,
    val tools: Boolean? = null,
    val thinking: Boolean? = null,
    val speculative: Boolean? = null,
    val soc: String? = null,
    val minRuntimeVersion: String? = null,
    val visionBackends: Set<String> = emptySet()
) {
    fun validate(spec: LocalEngineSpec) {
        try {
            validateConfiguration(spec)
        } catch (error: IllegalArgumentException) {
            throw LocalModelConfigurationException(error.message ?: "Unsupported model configuration", error)
        }
    }

    private fun validateConfiguration(spec: LocalEngineSpec) {
        require(contextTokens == null || spec.maxTokens <= contextTokens) { "Requested context ${spec.maxTokens} exceeds this export's $contextTokens-token ceiling." }
        require(backends.isEmpty() || spec.accelerator.lowercase() in backends) { "This artifact does not support ${spec.accelerator}. Choose a compatible variant; a backend switch cannot convert compiled weights." }
        require(!spec.isVisionEnabled || vision == true) { "Image support is absent or unverified for this export. Select and verify a compatible vision package." }
        require(!spec.isVisionEnabled || visionBackends.isEmpty() || spec.visionAccelerator in visionBackends) { "This export does not support the requested vision backend." }
        require(spec.speculativeDecoding != true || speculative == true) { "This export has no verified speculative-decoding support. Use Model default or Off." }
        if (spec.accelerator.equals("npu", true) && !soc.isNullOrBlank()) {
            require(QualcommSocSupport.canonicalSoc(soc) == QualcommSocSupport.canonicalSoc(android.os.Build.SOC_MODEL)) { "This NPU export targets $soc; it cannot run on ${android.os.Build.SOC_MODEL}." }
        }
        require(minRuntimeVersion == null || !runtimeVersionIsNewer(minRuntimeVersion, BuildConfig.LITERT_LM_VERSION)) { "This model requires LiteRT-LM $minRuntimeVersion; installed runtime is ${BuildConfig.LITERT_LM_VERSION}." }
    }
}

class LocalModelConfigurationException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

internal fun runtimeVersionIsNewer(required: String, installed: String): Boolean {
    fun parts(value: String) = value.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(required)
    val b = parts(installed)
    for (index in 0 until maxOf(a.size, b.size)) {
        val comparison = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
        if (comparison != 0) return comparison > 0
    }
    return false
}

internal object LiteRtModelInspector {
    private data class Cached(val identity: String?, val metadata: LocalModelCapabilities)
    private val cache = LinkedHashMap<String, Cached>()

    @Synchronized
    fun inspect(path: String): LocalModelCapabilities {
        val file = File(path).canonicalFile
        val key = file.path
        val identity = dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest.installedFileIdentity(file)
        cache[key]?.takeIf { identity != null && it.identity == identity }?.let { return it.metadata }
        dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest.validateInstalled(file)
        val validation = LocalModelValidator.validate(key)
        require(validation is ModelValidationResult.Valid) { "Model integrity preflight failed: $validation" }
        return ModelInfo.from(key).use { info ->
            require(info !is ModelInfo.Embedding) { "This is an embedding model. Choose a text-generation artifact for chat." }
            val llm = info as? ModelInfo.Llm
            LocalModelCapabilities(
                contextTokens = info.maxContextTokens().takeIf { it > 0 },
                dynamicContext = info.isDynamicContext(),
                backends = info.supportedBackends(Modality.TEXT).map { it.name.lowercase() }.toSet(),
                vision = info.inputModalities().vision,
                tools = llm?.supportsFunctionCalling(),
                thinking = llm?.supportsThinking(),
                speculative = llm?.hasSpeculativeDecodingSupport(),
                soc = info.socName(Modality.TEXT),
                minRuntimeVersion = info.minRuntimeVersion(),
                visionBackends = if (info.inputModalities().vision) info.supportedBackends(Modality.VISION).map { it.name.lowercase() }.toSet() else emptySet()
            )
        }.also {
            if (cache.size >= 32) cache.remove(cache.keys.first())
            if (identity != null && identity == dev.chungjungsoo.gptmobile.data.localmodel.PackageDigest.installedFileIdentity(file)) cache[key] = Cached(identity, it)
        }
    }
}
