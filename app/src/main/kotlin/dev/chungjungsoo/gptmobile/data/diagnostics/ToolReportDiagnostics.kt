package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Only the persisted call supplies identity and argument shape; result prose never supplies either. */
internal object ToolReportDiagnostics {
    fun fields(event: ToolEvent?, result: AgentToolResult, precheckReason: String? = null): Map<String, Any?> = buildMap {
        if (event == null || event.callId != result.callId) return@buildMap
        put("diagnostic_area", "TOOL")
        put("diagnostic_capture_mode", "INCIDENT")
        put("run_id_hash", IssueReportContext.identifierHash("run", event.runId))
        put("call_id_hash", IssueReportContext.identifierHash("call", event.callId))
        put("tool_name_hash", IssueReportContext.identifierHash("tool-name", event.modelToolName))
        put("original_error_code_hash", IssueReportContext.identifierHash("tool-error-code", result.errorCode))
        val reason = (result.executionReceipt?.get("reason_code") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: (result.executionReceipt?.get("reasonCode") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: precheckReason
        put("original_reason_code_hash", IssueReportContext.identifierHash("tool-reason-code", reason))
        val arguments = event.arguments.takeIf { it.length <= MAX_ARGUMENT_CHARS }
            ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        if (arguments != null) {
            val action = (arguments["action"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            put("tool_action_hash", IssueReportContext.identifierHash("tool-action", action))
            shapeHash(arguments)?.let { put("argument_shape_hash", it) }
        }
    }

    /** The digest covers field names and JSON types, never scalar argument values. */
    internal fun shapeHash(arguments: JsonObject): String? {
        var nodes = 0
        var complete = true
        fun shape(value: JsonElement, depth: Int): String {
            if (++nodes > 256 || depth > 12) {
                complete = false
                return ""
            }
            return when (value) {
                JsonNull -> "null"
                is JsonObject -> value.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (key, child) ->
                    // Names may themselves contain private data. They are retained only inside this digest input.
                    JsonPrimitive(key).toString() + ":" + shape(child, depth + 1)
                }
                is JsonArray -> value.joinToString(prefix = "[", postfix = "]") { shape(it, depth + 1) }
                is JsonPrimitive -> when {
                    value.isString -> "string"
                    value.booleanOrNull != null -> "boolean"
                    else -> "number"
                }
            }
        }
        val structure = shape(arguments, 0)
        return structure.takeIf { complete }?.let {
            MessageDigest.getInstance("SHA-256").digest("gptmobile.tool-argument-shape.v1:$it".toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }

    private const val MAX_ARGUMENT_CHARS = 65_536
}
