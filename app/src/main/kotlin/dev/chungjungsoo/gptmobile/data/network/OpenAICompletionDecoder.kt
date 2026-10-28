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
    return json.decodeFromJsonElement(ChatCompletionChunk.serializer(), CompletionUsageNormalizer.normalize(response))
}
