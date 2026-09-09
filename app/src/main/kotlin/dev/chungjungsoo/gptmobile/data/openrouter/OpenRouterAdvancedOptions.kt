package dev.chungjungsoo.gptmobile.data.openrouter

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenRouter provider routing configuration.
 * See https://openrouter.ai/docs/provider-routing
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class OpenRouterProviderRouting(
    @SerialName("order")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val order: List<String>? = null,

    @SerialName("allow_fallbacks")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val allowFallbacks: Boolean? = null,

    @SerialName("require_parameters")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val requireParameters: Boolean? = null,

    @SerialName("data_collection")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val dataCollection: String? = null,

    @SerialName("ignore")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val ignore: List<String>? = null,

    @SerialName("quantizations")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val quantizations: List<String>? = null,

    @SerialName("sort")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val sort: String? = null
)

/**
 * OpenRouter reasoning configuration.
 * Controls reasoning tokens and effort for models supporting reasoning/thinking.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class OpenRouterReasoning(
    @SerialName("effort")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val effort: String? = null,

    @SerialName("max_tokens")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val maxTokens: Int? = null,

    @SerialName("exclude")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val exclude: Boolean? = null
)

/**
 * OpenRouter plugin configuration, e.g. web search plugin.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class OpenRouterPlugin(
    @SerialName("id")
    val id: String,

    @SerialName("max_results")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val maxResults: Int? = null,

    @SerialName("search_prompt")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val searchPrompt: String? = null
)
