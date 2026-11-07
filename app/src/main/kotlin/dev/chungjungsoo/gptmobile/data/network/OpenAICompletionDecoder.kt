package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.provider.CompletionUsageNormalizer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Both buffered completions and SSE packets use the same optional-usage contract. */
internal fun decodeOpenAICompletion(json: Json, payload: String): ChatCompletionChunk {
    val response = json.parseToJsonElement(payload) as? JsonObject
        ?: throw SerializationException("Expected a completion object")
    // Gateway observations are optional metadata. A malformed receipt must never
    // discard valid answer text or tool calls; transport identity is checked separately.
    val timing = response["gateway_timing"]?.let {
        runCatching { json.decodeFromJsonElement(dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayGenerationTiming.serializer(), it) }.getOrNull()
    }
    val provenance = response["gateway_provenance"]?.let {
        runCatching { json.decodeFromJsonElement(dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayGenerationProvenance.serializer(), it) }.getOrNull()
    }
    val delivery = response["gateway_delivery"]?.let {
        runCatching { json.decodeFromJsonElement(dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayDeliveryTiming.serializer(), it) }.getOrNull()
    }
    val completion = JsonObject(response - setOf("gateway_timing", "gateway_provenance", "gateway_delivery"))
    return json.decodeFromJsonElement(ChatCompletionChunk.serializer(), CompletionUsageNormalizer.normalize(completion))
        .copy(gatewayTiming = timing, gatewayProvenance = provenance, gatewayDeliveryTiming = delivery)
}
