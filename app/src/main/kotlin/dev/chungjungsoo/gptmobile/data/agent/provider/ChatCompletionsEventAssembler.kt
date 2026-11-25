package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolBatchOversizeException
import dev.chungjungsoo.gptmobile.data.agent.ToolStreamGuard
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatToolCallDelta
import dev.chungjungsoo.gptmobile.data.repository.GroqReasoningParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ChatCompletionsEventAssembler {
    // Fragment slots consume memory even before they become executable calls.
    var reportedModelId: String? = null
        private set

    fun observeModel(id: String?) {
        if (id != null && id != reportedModelId) reportedModelId = dev.chungjungsoo.gptmobile.data.diagnostics.reportedModelIdentifier(id)
    }

    private val toolGuard = ToolStreamGuard()
    private data class PendingCall(
        var callId: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder()
    )

    private val pending = sortedMapOf<Pair<Int, Int>, PendingCall>(compareBy({ it.first }, { it.second }))
    private val shape = ArrayDeque<String>()
    private var rejected: ToolBatchOversizeException? = null
    private var completedCalls = 0
    private val contentParsers = mutableMapOf<Int, GroqReasoningParser>()

    private fun textEvents(states: List<ApiState>): List<ProviderEvent> = states.mapNotNull { state ->
        when (state) {
            is ApiState.Thinking -> ProviderEvent.ThinkingDelta(state.thinkingChunk)
            is ApiState.Success -> ProviderEvent.TextDelta(state.textChunk, state.lineage.copy(reportedModelId = reportedModelId))
            else -> null
        }
    }

    private fun flushContent(choiceIndex: Int? = null): List<ProviderEvent> =
        contentParsers.keys.filter { choiceIndex == null || it == choiceIndex }.flatMap { index ->
            textEvents(requireNotNull(contentParsers.remove(index)).flush())
        }

    fun accept(
        content: String?,
        reasoning: String?,
        toolCalls: List<ChatToolCallDelta>?,
        finishReason: String?,
        choiceIndex: Int = 0
    ): List<ProviderEvent> {
        rejected?.let { throw it }
        require(choiceIndex >= 0) { "TOOL_STREAM_INVALID: negative choice index; dispatched=false" }
        val events = mutableListOf<ProviderEvent>()
        if (!content.isNullOrEmpty() || !reasoning.isNullOrEmpty()) {
            events += textEvents(contentParsers.getOrPut(choiceIndex) { GroqReasoningParser() }.append(content, reasoning))
        }
        toolCalls.orEmpty().forEach { delta ->
            require(choiceIndex >= 0 && delta.index >= 0) { "TOOL_STREAM_INVALID: negative choice/tool index; dispatched=false" }
            val key = choiceIndex to delta.index
            if (key !in pending && delta.id.isNullOrBlank() && delta.function?.name.isNullOrBlank() && delta.function?.arguments.isNullOrBlank()) return@forEach
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
            else -> events += discardIncomplete(choiceIndex)
        }
        return events
    }

    /** Called only for a completed choice or an explicit SSE [DONE]. */
    fun finish(choiceIndex: Int? = null): List<ProviderEvent> {
        if (rejected != null) return emptyList()
        val text = flushContent(choiceIndex)
        val completed = pending.filterKeys { choiceIndex == null || it.first == choiceIndex }
        val events = completed.values.map { call ->
            val name = call.name
            if (name.isNullOrBlank()) {
                invalidToolFrame("Provider returned an incomplete function call.", "MISSING_FUNCTION_NAME")
            } else {
                // Some compatible servers omit IDs; preserve supplied IDs and replay generated ones.
                val callId = call.callId?.takeIf(String::isNotBlank) ?: "call_${java.util.UUID.randomUUID()}"
                toolCall(callId, name, call.arguments.toString(), reportedModelId).single()
            }
        }
        completed.keys.forEach(pending::remove)
        val invalid = events.filterIsInstance<ProviderEvent.Failed>()
        if (invalid.isNotEmpty()) return text + invalid
        val count = completedCalls + events.size
        val limit = ProviderToolBatchPolicy.PREFERRED_MAX_CALLS
        if (count > limit) {
            val incident = ToolBatchOversizeException("COMPLETE_CALL_COUNT", count, limit, count, shape.joinToString(";"))
            rejected = incident
            pending.clear()
            throw incident
        }
        completedCalls = count
        return text + events
    }

    fun discardIncomplete(choiceIndex: Int? = null): List<ProviderEvent> {
        val text = flushContent(choiceIndex)
        val discarded = pending.keys.filter { choiceIndex == null || it.first == choiceIndex }
        if (discarded.isEmpty()) return text
        discarded.forEach(pending::remove)
        return text + invalidToolFrame("Provider stopped before completing the tool call. No incomplete tool was run.", "UNFINISHED_STREAM")
    }
}

/** A closed parser-owned reason. Never retain tool arguments or provider fragments. */
internal fun invalidToolFrame(message: String, kind: String): ProviderEvent.Failed = ProviderEvent.Failed(
    message,
    errorCode = "TOOL_PROTOCOL_INVALID_FRAME",
    failureDetails = buildJsonObject {
        put("tool_protocol_frame_kind", kind)
        put("failed_stage", "response_stream")
    }
)

internal fun toolCall(callId: String, name: String, arguments: String, reportedModelId: String? = null): List<ProviderEvent> {
    if (callId.length > 256) throw ToolBatchOversizeException("CALL_ID_CHARACTERS", callId.length, 256)
    if (name.length > 256) throw ToolBatchOversizeException("TOOL_NAME_CHARACTERS", name.length, 256)
    if (arguments.length > 16 * 1024) throw ToolBatchOversizeException("ARGUMENT_CHARACTERS", arguments.length, 16 * 1024)
    return try {
        val parsed = Json.parseToJsonElement(arguments.ifBlank { "{}" })
        if (parsed is JsonObject) {
            listOf(ProviderEvent.ToolCall(callId, name, parsed, reportedModelId = reportedModelId))
        } else {
            listOf(invalidToolFrame("Tool arguments must be a JSON object.", "NON_OBJECT_ARGUMENTS"))
        }
    } catch (_: Exception) {
        listOf(invalidToolFrame("Tool arguments were not valid JSON.", "MALFORMED_JSON"))
    }
}
