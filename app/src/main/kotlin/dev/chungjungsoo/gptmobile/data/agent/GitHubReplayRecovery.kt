package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Keep executable continuation arguments intact when replaying a large GitHub result. */
internal object GitHubReplayRecovery {
    fun envelope(content: ToolResultContent): JsonObject? = when (content) {
        is ToolResultContent.Json -> content.value as? JsonObject
        is ToolResultContent.Text -> runCatching { Json.parseToJsonElement(content.text) as? JsonObject }.getOrNull()
        else -> null
    }?.takeIf { (it["schema"] as? JsonPrimitive)?.content?.startsWith("github.") == true }

    fun compact(content: ToolResultContent, maxChars: Int): ToolResultContent? {
        val source = envelope(content) ?: return null
        val controls = source.filterKeys { it in setOf("schema", "operation", "status", "local_page_complete", "continuation_call", "remote_continuations", "correction_call", "previous_call_id", "metadata") }
        val result = controls.toMutableMap()
        result["replay_compacted"] = JsonPrimitive(true)
        // Never splice the middle of a cursor or a JSON argument object.
        if (JsonObject(result).toString().length > maxChars) {
            return ToolResultContent.Text("GitHub result omitted from replay: its continuation exceeds the available replay budget. Do not invent a cursor or repeat a mutation; inspect retained evidence or request a narrower immutable read.")
        }
        val preview = (source["data"] ?: source["fragments"] ?: source["message"])?.toString().orEmpty()
        val available = ((maxChars - JsonObject(result).toString().length - 32) / 6).coerceAtLeast(0)
        if (available > 0) result["consumed_preview"] = JsonPrimitive(preview.take(available))
        return ToolResultContent.Json(JsonObject(result))
    }

    fun repeated(call: ProviderEvent.ToolCall, exchanges: List<AgentToolExchange>, message: String): AgentToolResult? {
        val operation = GitHubOperationTool.operationName(call.name) ?: return null
        val key = toolRepeatKey(call)
        val previous = exchanges.asReversed().firstNotNullOfOrNull { exchange ->
            val id = exchange.calls.lastOrNull { toolRepeatKey(it) == key }?.callId
            exchange.results.lastOrNull { it.callId == id && !it.isError }
        } ?: return null
        val original = envelope(previous.content)
        return AgentToolResult(
            call.callId,
            ToolResultContent.Json(
                buildJsonObject {
                    put("schema", "github.repeat.v1")
                    put("operation", operation)
                    put("status", "REPEAT_SUPPRESSED")
                    put("message", message)
                    put("previous_call_id", previous.callId)
                    original?.get("continuation_call")?.let { put("continuation_call", it) }
                    original?.get("remote_continuations")?.let { put("remote_continuations", it) }
                    put("retained_result_preview", previous.content.toString().take(1200))
                    put("recovery", "Use the exact continuation_call above when present. The earlier call already completed; no action was repeated. Use a different unread range for additional source evidence.")
                }
            ),
            isError = true,
            dispatched = false,
            errorCode = "REPEAT_SUPPRESSED"
        )
    }
}
