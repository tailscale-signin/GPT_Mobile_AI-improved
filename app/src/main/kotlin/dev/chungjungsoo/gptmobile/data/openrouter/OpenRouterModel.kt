package dev.chungjungsoo.gptmobile.data.openrouter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpenRouterModelsResponse(
    val data: List<OpenRouterModelItem> = emptyList()
)

@Serializable
data class OpenRouterModelItem(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    @SerialName("context_length")
    val contextLength: Int? = null,
    val pricing: OpenRouterPricing? = null
)

@Serializable
data class OpenRouterPricing(
    val prompt: String? = null,
    val completion: String? = null
)
