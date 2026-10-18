package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentBlock
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentStartResponseChunk
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.ContentStopResponseChunk
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.MessageDeltaResponseChunk
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.MessageResponse
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.MessageResponseChunk
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.MessageStartResponseChunk
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.MessageStopResponseChunk
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.StopReasonDelta
import dev.chungjungsoo.gptmobile.data.dto.anthropic.response.UsageDelta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Both transports share the assembler, replay signatures, usage and tool validation. */
internal fun anthropicCompleteResponseChunks(body: String): List<MessageResponseChunk> {
    val json = Json { ignoreUnknownKeys = true }
    val root = json.parseToJsonElement(body).jsonObject
    val blocks = root["content"]?.jsonArray ?: error("Anthropic message has no content array.")
    val metadata = JsonObject(root + ("content" to JsonArray(emptyList())))
    val message = json.decodeFromJsonElement(MessageResponse.serializer(), metadata)
    val reason = requireNotNull(message.stopReason) { "Anthropic complete response has no terminal stop reason." }
    return buildList {
        add(MessageStartResponseChunk(message))
        blocks.forEachIndexed { index, block ->
            add(ContentStartResponseChunk(index, json.decodeFromJsonElement(ContentBlock.serializer(), block)))
            add(ContentStopResponseChunk(index))
        }
        add(MessageDeltaResponseChunk(StopReasonDelta(reason, message.stopSequence), UsageDelta(message.usage.outputTokens)))
        add(MessageStopResponseChunk)
    }
}
