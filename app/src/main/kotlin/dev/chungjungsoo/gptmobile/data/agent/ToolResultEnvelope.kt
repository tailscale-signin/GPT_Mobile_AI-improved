package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Model-facing control metadata is never inferred from the provider's prose. */
internal object ToolResultEnvelope {
    fun error(callId: String, code: String, message: String, dispatched: Boolean = false): AgentToolResult =
        AgentToolResult(callId, ToolResultContent.Text(message), true, errorCode = code, dispatched = dispatched)

    fun encode(tool: String, result: AgentToolResult): String = buildJsonObject {
        put("schema", "gptmobile.tool-result.v1")
        put("tool", tool)
        put("callId", result.callId)
        put("status", if (result.isError) "error" else "success")
        put(
            "execution",
            buildJsonObject {
                put("dispatched", result.dispatched)
                put(
                    "outcome",
                    when {
                        result.errorCode == "TIMEOUT_OUTCOME_UNKNOWN" -> "unknown"
                        !result.dispatched -> "not_executed"
                        result.isError -> "failed"
                        else -> "completed"
                    }
                )
            }
        )
        if (result.isError) {
            put(
                "error",
                buildJsonObject {
                    put(
                        "code",
                        result.errorCode ?: when {
                            result.outputBudgetExhausted || result.toolCallBudgetExhausted -> "BUDGET_EXHAUSTED"
                            else -> "PROVIDER_UNAVAILABLE"
                        }
                    )
                    put("retryableWithoutChanges", false)
                }
            )
        }
        put("compacted", result.retainedContent != null)
        put("evidenceAllowanceReached", result.outputBudgetExhausted)
        put("data", element(result.content))
    }.toString()

    fun element(content: ToolResultContent): JsonElement = when (content) {
        is ToolResultContent.Json -> content.value
        is ToolResultContent.Text -> runCatching { Json.parseToJsonElement(content.text) }.getOrElse { JsonPrimitive(content.text) }
        is ToolResultContent.ResourceLinks -> JsonArray(
            content.links.map { link ->
                buildJsonObject {
                    put("url", link.uri)
                    link.name?.let { put("name", it) }
                }
            }
        )
    }

    /** Keep complete records, metadata and qualifications. Never slice serialized JSON. */
    fun compact(content: ToolResultContent, maxBytes: Int): ToolResultContent {
        if (content is ToolResultContent.Text) {
            val parsed = runCatching { Json.parseToJsonElement(content.text) }.getOrNull()
            if (parsed != null && (parsed is JsonObject || parsed is JsonArray)) return compact(ToolResultContent.Json(parsed), maxBytes)
            val marker = if (maxBytes >= 128) "\n[Compacted evidence; full result retained.]" else ""
            return ToolResultContent.Text(truncateUtf8(content.text, (maxBytes - marker.toByteArray().size).coerceAtLeast(0)) + marker)
        }
        var value = element(content)
        fun size(element: JsonElement) = element.toString().toByteArray(Charsets.UTF_8).size

        // Shrink the largest result array first, preserving each surviving record in full.
        fun reduce(element: JsonElement): JsonElement? = when (element) {
            is JsonArray -> if (element.isNotEmpty()) JsonArray(element.dropLast(1)) else null
            is JsonObject -> element.entries.mapNotNull { (key, child) ->
                reduce(child)?.let { key to it }
            }.maxByOrNull { (key, reduced) -> size(element.getValue(key)) - size(reduced) }?.let { (key, reduced) ->
                JsonObject(element + (key to reduced))
            }
            else -> null
        }
        var steps = 0
        while (size(value) > maxBytes && steps++ < 512) value = reduce(value) ?: break
        if (size(value) > maxBytes) {
            value = buildJsonObject {
                put("evidenceOmitted", true)
                put("reason", "Complete record exceeds local evidence allowance; do not infer its contents.")
                if (value is JsonObject) {
                    (value as JsonObject)["notice"]?.takeIf { size(it) < maxBytes / 2 }?.let { put("notice", it) }
                    (value as JsonObject)["indexedFallback"]?.let { put("indexedFallback", it) }
                }
            }
        }
        return ToolResultContent.Json(value)
    }
}
