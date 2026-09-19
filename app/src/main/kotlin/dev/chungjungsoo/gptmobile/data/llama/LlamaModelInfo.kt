package dev.chungjungsoo.gptmobile.data.llama

import java.text.NumberFormat
import java.util.Locale

/**
 * Model specification data model representing a Llama model retrieved from router mode or catalog.
 */
data class LlamaModelInfo(
    val id: String,
    val name: String,
    val parameterCountMillions: Long = 0,
    val contextWindowTokens: Int = 4096,
    val quantization: String = "Q4_K_M",
    val status: LlamaModelStatus = LlamaModelStatus.AVAILABLE,
    val loadPercentage: Int? = null,
    val isRecent: Boolean = false
) {
    val formattedParameters: String
        get() {
            if (parameterCountMillions <= 0) return ""
            return "${NumberFormat.getNumberInstance(Locale.US).format(parameterCountMillions)}M"
        }

    val formattedContextWindow: String
        get() = "${NumberFormat.getNumberInstance(Locale.US).format(contextWindowTokens)} tokens"

    val formattedLoad: String?
        get() = loadPercentage?.let { "$it% loaded" }
}

enum class LlamaModelStatus {
    AVAILABLE,
    LOADING,
    UNAVAILABLE
}

/**
 * Model option received from a Llama router or discovery endpoint.
 */
data class LlamaRouterModelOption(
    val id: String,
    val name: String = "",
    val contextLength: Int? = null
)

/**
 * Router configuration for Llama server mode.
 */
data class LlamaRouterConfig(
    val baseUrl: String = "",
    val routerEndpointPath: String = "/v1/models",
    val timeoutSeconds: Int = 5,
    val retryAttempts: Int = 3,
    val preferLowerLoad: Boolean = true,
    val enableLoadBalancing: Boolean = true,
    val maxConcurrentRequests: Int = 4
)

private val PARAM_COUNT_REGEX = Regex("""(?i)(\d+(?:\.\d+)?)\s*[bB]""")
private val QUANTIZATION_REGEX = Regex("""(?i)(Q\d+_[A-Z0-9_]+|f16|f32|bf16|int8|int4)""")

/**
 * Mapper utility to transform raw router responses into [LlamaModelInfo].
 */
object LlamaModelMapper {

    fun fromRouterModel(routerModel: LlamaRouterModelOption): LlamaModelInfo {
        val parsedParams = parseParameterCount(routerModel.id + " " + routerModel.name)
        val parsedQuant = parseQuantization(routerModel.id + " " + routerModel.name)

        return LlamaModelInfo(
            id = routerModel.id,
            name = routerModel.name.ifBlank { routerModel.id },
            parameterCountMillions = parsedParams,
            contextWindowTokens = routerModel.contextLength ?: 4096,
            quantization = parsedQuant,
            status = LlamaModelStatus.AVAILABLE,
            loadPercentage = null
        )
    }

    private fun parseParameterCount(text: String): Long {
        val match = PARAM_COUNT_REGEX.find(text) ?: return 0
        val num = match.groupValues[1].toDoubleOrNull() ?: return 0
        return (num * 1000).toLong()
    }

    private fun parseQuantization(text: String): String =
        QUANTIZATION_REGEX.find(text)?.groupValues?.get(1)?.uppercase() ?: "Q4_K_M"
}
