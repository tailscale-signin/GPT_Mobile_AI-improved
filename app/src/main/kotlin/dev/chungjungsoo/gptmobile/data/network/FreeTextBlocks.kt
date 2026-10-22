package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Choice
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Delta
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Vireonix sometimes JSON-encodes its content blocks inside the content string. */
internal fun Flow<ChatCompletionChunk>.normalizeFreeTextBlocks(provider: FreeAiProvider?): Flow<ChatCompletionChunk> {
    if (provider != FreeAiProvider.VIREONIX) return this
    return flow {
        val buffers = mutableMapOf<Int, FreeTextBlockBuffer>()
        collect { chunk ->
            if (chunk.error != null || chunk.streamFinished) {
                buffers.forEach { (index, buffer) ->
                    buffer.finish().takeIf { it.isNotEmpty() }?.let { emit(ChatCompletionChunk(choices = listOf(Choice(index = index, delta = Delta(content = it))))) }
                }
            }
            emit(
                chunk.copy(
                    choices = chunk.choices?.map { choice ->
                        val buffer = buffers.getOrPut(choice.index) { FreeTextBlockBuffer() }
                        val delta = choice.effectiveDelta
                        val text = buffer.append(delta.content.orEmpty()) + if (choice.finishReason != null) buffer.finish() else ""
                        if (choice.message != null) choice.copy(message = delta.copy(content = text)) else choice.copy(delta = delta.copy(content = text))
                    }
                )
            )
        }
        buffers.forEach { (index, buffer) ->
            buffer.finish().takeIf { it.isNotEmpty() }?.let { emit(ChatCompletionChunk(choices = listOf(Choice(index = index, delta = Delta(content = it))))) }
        }
    }
}

internal class FreeTextBlockBuffer {
    private val pending = StringBuilder()
    private var plain = false
    fun append(text: String): String {
        if (plain) return text
        pending.append(text)
        val prefix = pending.toString().trimStart()
        if (pending.length > 262144 || prefix.isNotEmpty() && !prefix.startsWith("[")) return finish()
        return ""
    }

    fun finish(): String {
        val raw = pending.toString()
        pending.clear()
        plain = true
        val blocks = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return raw
        if (blocks.isEmpty() ||
            blocks.any { block ->
                block !is JsonObject ||
                    block.keys.any { it !in setOf("type", "text") } ||
                    (block["type"] as? JsonPrimitive)?.contentOrNull !in setOf("text", "output_text") ||
                    (block["text"] as? JsonPrimitive)?.isString != true
            }
        ) {
            return raw
        }
        return blocks.joinToString("") { ((it as JsonObject)["text"] as JsonPrimitive).content }
    }
}
