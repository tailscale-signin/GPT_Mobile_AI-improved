package dev.chungjungsoo.gptmobile.data.ollama

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OllamaTagsResponse(
    val models: List<OllamaModelEntry> = emptyList()
)

@Serializable
data class OllamaModelEntry(
    val name: String,
    val modified_at: String? = null,
    val size: Long? = null,
    val digest: String? = null,
    val details: OllamaModelDetails? = null
)

@Serializable
data class OllamaModelDetails(
    val format: String? = null,
    val family: String? = null,
    val families: List<String>? = null,
    @SerialName("parameter_size")
    val parameterSize: String? = null,
    @SerialName("quantization_level")
    val quantizationLevel: String? = null
)

@Serializable
data class OllamaVersionResponse(
    val version: String? = null
)
