package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolBatchOversizeException
import dev.chungjungsoo.gptmobile.data.agent.ToolStreamGuard
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatToolCallDelta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class ChatCompletionsEventAssembler {
    private val toolGuard = ToolStreamGuard(ProviderToolBatchPolicy.PREFERRED_MAX_CALLS)
    private data class PendingCall(
        var callId: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder()
    )

    private val pending = sortedMapOf<Pair<Int, Int>, PendingCall>(compareBy({ it.first }, { it.second }))
    private val shape = ArrayDeque<String>()
    private var rejected: ToolBatchOversizeException? = null

    fun accept(
        content: String?,
        reasoning: String?,
        toolCalls: List<ChatToolCallDelta>?,
        finishReason: String?,
        choiceIndex: Int = 0
    ): List<ProviderEvent> {
        rejected?.let { throw it }
        val events = mutableListOf<ProviderEvent>()
        reasoning?.takeIf { it.isNotEmpty() }?.let { events += ProviderEvent.ThinkingDelta(it) }
        content?.takeIf { it.isNotEmpty() }?.let { events += ProviderEvent.TextDelta(it) }
        toolCalls.orEmpty().forEach { delta ->
            require(choiceIndex >= 0 && delta.index >= 0) { "TOOL_STREAM_INVALID: negative choice/tool index; dispatched=false" }
            val key = choiceIndex to delta.index
            if (shape.size == 12) shape.removeFirst()
            shape.addLast("choice=$choiceIndex,index=${delta.index},id=${!delta.id.isNullOrBlank()},name=${!delta.function?.name.isNullOrBlank()},argumentChars=${delta.function?.arguments?.length ?: 0}")
            try {
                toolGuard.slot("$choiceIndex:${delta.index}")
                toolGuard.identifier(delta.id)
                toolGuard.identifier(delta.function?.name)
                toolGuard.append(delta.function?.arguments.orEmpty())
            } catch (failure: dev.chungjungsoo.gptmobile.data.agent.ToolBatchOversizeException) {
                val incident = ToolBatchOversizeException(failure.threshold, failure.observedValue, failure.allowedValue, pending.values.count { !it.callId.isNullOrBlank() }, shape.joinToString(";"))
                rejected = incident
                pending.clear()
                throw incident
            }
            val call = pending.getOrPut(key) { PendingCall() }
            delta.id?.takeIf(String::isNotBlank)?.let { id ->
                check(call.callId == null || call.callId == id) { "TOOL_STREAM_INVALID: call ID changed within a choice/tool index; dispatched=false" }
                check(pending.none { (other, value) -> other != key && value.callId == id }) { "TOOL_STREAM_INVALID: duplicate call ID across stream slots; dispatched=false" }
                call.callId = id
            }
            delta.function?.name?.takeIf(String::isNotBlank)?.let { name ->
                // Compatible servers send names as fragments, repeated metadata, or
                // cumulative snapshots. Append only genuinely disjoint fragments.
                call.name = when (val current = call.name.orEmpty()) {
                    "" -> name
                    name -> current
                    else -> when {
                        name.startsWith(current) -> name
                        current.startsWith(name) -> current
                        else -> current + name
                    }
                }
                toolGuard.identifier(call.name)
            }
            delta.function?.arguments?.let { call.arguments.append(it) }
        }
        finishReason?.let { events += ProviderEvent.Usage(finishReason = it) }
        when (finishReason) {
            "tool_calls", "function_call", "stop" -> events += finish(choiceIndex)
            null -> Unit
            else -> events += discardIncomplete()
        }
        return events
    }

    /** Called only for a completed choice or an explicit SSE [DONE]. */
    fun finish(choiceIndex: Int? = null): List<ProviderEvent> {
        if (rejected != null) return emptyList()
        val completed = pending.filterKeys { choiceIndex == null || it.first == choiceIndex }
        val events = completed.values.map { call ->
            val name = call.name
            if (name.isNullOrBlank()) {
                ProviderEvent.Failed("Provider returned an incomplete function call.", errorCode = "TOOL_PROTOCOL_INVALID_FRAME")
            } else {
                // Some compatible servers omit IDs; preserve supplied IDs and replay generated ones.
                val callId = call.callId?.takeIf(String::isNotBlank) ?: "call_${java.util.UUID.randomUUID()}"
                toolCall(callId, name, call.arguments.toString()).single()
            }
        }
        completed.keys.forEach(pending::remove)
        return events
    }

    fun discardIncomplete(): List<ProviderEvent> {
        if (pending.isEmpty()) return emptyList()
        pending.clear()
        return listOf(ProviderEvent.Failed("Provider stopped before completing the tool call. No incomplete tool was run.", errorCode = "TOOL_PROTOCOL_INVALID_FRAME"))
    }
}

internal fun toolCall(callId: String, name: String, arguments: String): List<ProviderEvent> {
    if (callId.length > 256) throw ToolBatchOversizeException("CALL_ID_CHARACTERS", callId.length, 256)
    if (name.length > 256) throw ToolBatchOversizeException("TOOL_NAME_CHARACTERS", name.length, 256)
    if (arguments.length > 16 * 1024) throw ToolBatchOversizeException("ARGUMENT_CHARACTERS", arguments.length, 16 * 1024)
    return try {
        val parsed = Json.parseToJsonElement(arguments.ifBlank { "{}" })
        if (parsed is JsonObject) {
            listOf(ProviderEvent.ToolCall(callId, name, parsed))
        } else {
            listOf(ProviderEvent.Failed("Tool arguments must be a JSON object.", errorCode = "TOOL_PROTOCOL_INVALID_FRAME"))
        }
    } catch (_: Exception) {
        listOf(ProviderEvent.Failed("Tool arguments were not valid JSON.", errorCode = "TOOL_PROTOCOL_INVALID_FRAME"))
    }
}
