package dev.chungjungsoo.gptmobile.data.model

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
    val context_length: Long? = null,
    val pricing: OpenRouterPricing? = null
)

@Serializable
data class OpenRouterPricing(
    val prompt: String? = null,
    val completion: String? = null
)
