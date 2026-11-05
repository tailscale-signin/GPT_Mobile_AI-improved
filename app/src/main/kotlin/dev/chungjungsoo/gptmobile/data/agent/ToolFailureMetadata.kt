package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.network.NetworkFailureKind
import dev.chungjungsoo.gptmobile.data.network.error.ErrorClassification
import dev.chungjungsoo.gptmobile.data.network.networkFailureKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Fixed diagnostic metadata travels with the original receipt, including compacted/replayed results. */
internal object ToolFailureMetadata {
    private val unavailableCode = setOf("", "none", "null", "unknown")

    fun normalize(result: AgentToolResult): AgentToolResult {
        if (!result.isError) return result
        val code = result.errorCode?.trim()?.takeUnless { it.lowercase(java.util.Locale.ROOT) in unavailableCode }
            ?: if (result.outputBudgetExhausted || result.toolCallBudgetExhausted) "BUDGET_EXHAUSTED" else classify(Exception(text(result.content)))
        return result.copy(errorCode = code)
    }

    fun classify(error: Throwable): String {
        val classified = ErrorClassification.classify(error)
        if (classified.errorCode in ToolResultEnvelope.unknownOutcomeCodes) return classified.errorCode
        val network = networkFailureKind(error)
        if (network != NetworkFailureKind.UNKNOWN) return network.code
        val details = generateSequence(error) { it.cause }.take(16).joinToString(" ") { it.javaClass.simpleName + " " + it.message.orEmpty() }.lowercase(java.util.Locale.ROOT)
        return when {
            "unknownhostexception" in details || "unable to resolve host" in details || "name or service not known" in details || "dns resolution" in details -> "DNS_RESOLUTION_FAILED"
            "unexpected end of stream" in details || "eofexception" in details -> "TRANSPORT_EOF"
            "connection abort" in details || "connection reset" in details || "socketexception" in details -> "CONNECTION_ABORTED"
            "serializationexception" in details || "jsondecodingexception" in details || "malformedjsonexception" in details -> "PARSING_ERROR"
            classified.errorCode != "UNKNOWN" -> classified.errorCode
            else -> "TOOL_FAILURE"
        }
    }

    fun receipt(result: AgentToolResult, tool: String, diagnosticCode: String? = null, replaySafe: Boolean? = null): AgentToolResult {
        val normalized = normalize(result)
        if (!normalized.isError) return normalized
        val classified = if (replaySafe == null) normalized else normalized.copy(executionReceipt = JsonObject(normalized.executionReceipt.orEmpty() + ("replay_safe" to JsonPrimitive(replaySafe))))
        val fields = buildJsonObject {
            put("schema", "gptmobile.tool-execution.v1")
            put("call_id", normalized.callId)
            put("tool", tool)
            put("error_code", requireNotNull(normalized.errorCode))
            diagnosticCode?.let { put("cause_code", it) }
            put("execution_state", ToolResultEnvelope.executionState(normalized))
            put("dispatched", normalized.dispatched)
            put("phase", if (normalized.dispatched) "tool_execution" else "tool_precheck")
            put("retryable", retryable(classified))
            put("requires_reconciliation", normalized.errorCode in ToolResultEnvelope.unknownOutcomeCodes)
        }
        // Domain receipts carry stronger remote-state evidence and must keep every existing field.
        return normalized.copy(executionReceipt = JsonObject(fields + classified.executionReceipt.orEmpty()))
    }

    fun retryable(result: AgentToolResult): Boolean = result.isError &&
        result.errorCode !in ToolResultEnvelope.unknownOutcomeCodes &&
        (result.executionReceipt?.get("replay_safe") == JsonPrimitive(true) || result.errorCode in setOf("READ_DEADLINE", "READ_FAILED")) &&
        result.executionReceipt?.get("requires_reconciliation") != JsonPrimitive(true) &&
        result.errorCode in setOf("READ_DEADLINE", "READ_FAILED", "PROVIDER_UNAVAILABLE", "DNS_RESOLUTION_FAILED", "NETWORK_TIMEOUT", "NETWORK_DISCONNECTED", "CONNECTION_ABORTED", "TRANSPORT_EOF", "SERVICE_UNAVAILABLE", "DNS_FAILED", "DNS_RECOVERY", "CONNECTION_FAILED", "STREAM_INTERRUPTED", "TIMEOUT", "RATE_LIMITED", "UNAVAILABLE", "SEARCH_PROVIDERS_UNAVAILABLE")

    private fun text(content: ToolResultContent): String = when (content) {
        is ToolResultContent.Text -> content.text.take(8192)
        is ToolResultContent.Json -> content.value.toString().take(8192)
        is ToolResultContent.ResourceLinks -> "Tool returned an error with resource references"
    }
}
