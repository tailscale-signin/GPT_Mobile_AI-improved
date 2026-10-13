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
    val pricing: OpenRouterPricing? = null,
    @SerialName("top_provider")
    val topProvider: OpenRouterTopProvider? = null
)

@Serializable
data class OpenRouterPricing(
    val prompt: String? = null,
    val completion: String? = null,
    @SerialName("input_cache_read") val inputCacheRead: String? = null,
    @SerialName("input_cache_write") val inputCacheWrite: String? = null,
    val overrides: kotlinx.serialization.json.JsonArray? = null,
    val request: String? = null,
    val image: String? = null,
    @SerialName("web_search") val webSearch: String? = null
)

@Serializable
data class OpenRouterTopProvider(
    @SerialName("context_length") val contextLength: Int? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null
)
