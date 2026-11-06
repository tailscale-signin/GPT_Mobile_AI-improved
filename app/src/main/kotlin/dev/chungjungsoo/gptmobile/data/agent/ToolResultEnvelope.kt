package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Model-facing control metadata is never inferred from the provider's prose. */
internal object ToolResultEnvelope {
    val unknownOutcomeCodes = setOf("TIMEOUT_OUTCOME_UNKNOWN", "DISPATCH_OUTCOME_UNKNOWN", "TOOL_OUTCOME_UNKNOWN", "TOOL_RESULT_ID_MISMATCH", "RECONCILIATION_REQUIRED", "GATEWAY_OUTCOME_UNKNOWN", "GATEWAY_TOOL_OUTCOME_UNKNOWN", "REMOTE_OUTCOME_UNKNOWN")

    fun isAuthorizationRejection(code: String?): Boolean = code?.startsWith("PERSONAL_DATA_") == true || code in setOf("AUTH_REQUIRED", "AUTH_FAILED", "UNAUTHORIZED", "FORBIDDEN", "PERMISSION_DENIED", "PERMISSION_REVOKED", "AUTHENTICATION_FAILED", "ACCESS_FORBIDDEN", "GITHUB_ENDPOINT_PERMISSION_REQUIRED", "GITHUB_SSO_REQUIRED", "GITHUB_FORBIDDEN_UNKNOWN")

    fun isPrecheckRejection(code: String?): Boolean = isAuthorizationRejection(code) || code in setOf("INVALID_ARGUMENTS", "UNSUPPORTED_TOOL_CONTRACT", "UNSUPPORTED_OPERATION", "UNKNOWN_TOOL", "DUPLICATE_ACTION", "NOT_DISPATCHED")

    fun executionState(result: AgentToolResult): String = when {
        result.errorCode in unknownOutcomeCodes -> "UNKNOWN_OUTCOME"
        searchReceiptState(result) != null -> requireNotNull(searchReceiptState(result))
        result.isError && !result.dispatched -> "PRECHECK_REJECTED"
        result.isError -> "FAILED"
        else -> "COMPLETED"
    }

    /** Only our structured search adapter supplies this receipt; arbitrary payload prose is ignored. */
    private fun searchReceiptState(result: AgentToolResult): String? {
        val receipt = result.executionReceipt ?: return null
        if (receipt["schema"] != JsonPrimitive("gptmobile.search-operation.v1") || receipt["callId"] != JsonPrimitive(result.callId)) return null
        return when {
            !result.isError && receipt["state"] == JsonPrimitive("COMPLETED") -> "COMPLETED"
            result.isError && receipt["state"] == JsonPrimitive("PRECHECK_REJECTED") && receipt["requestSent"] == JsonPrimitive(false) -> "PRECHECK_REJECTED"
            result.isError && receipt["state"] == JsonPrimitive("UNKNOWN_OUTCOME") && receipt["requestSent"] == JsonNull && receipt["bodyReceived"] == JsonPrimitive(false) -> "UNKNOWN_OUTCOME"
            else -> null
        }
    }

    private fun category(code: String): String = when {
        code in unknownOutcomeCodes -> "OUTCOME_UNKNOWN"
        code in setOf("INVALID_ARGUMENTS", "TOOL_BATCH_OVERSIZE", "UNSUPPORTED_TOOL_CONTRACT", "TOOL_EXCHANGE_INVALID") -> "CONTRACT"
        code.startsWith("PERSONAL_DATA_") || code in setOf("PERMISSION_DENIED", "PERMISSION_REVOKED", "AUTH_REQUIRED", "AUTH_FAILED", "UNAUTHORIZED", "FORBIDDEN", "AUTHENTICATION_FAILED", "ACCESS_FORBIDDEN") -> "AUTHORIZATION"
        code == "BUDGET_EXHAUSTED" -> "BUDGET"
        code in setOf("PARSING_ERROR", "SERIALIZATION_ERROR") -> "PARSING"
        code in setOf("DNS_RESOLUTION_FAILED", "NETWORK_TIMEOUT", "NETWORK_DISCONNECTED", "CONNECTION_ABORTED", "TRANSPORT_EOF", "TLS_FAILURE", "DNS_FAILED", "DNS_RECOVERY", "CONNECTION_FAILED", "STREAM_INTERRUPTED", "TIMEOUT") -> "TRANSPORT"
        code in setOf("READ_DEADLINE", "READ_FAILED", "PROVIDER_UNAVAILABLE", "SERVICE_UNAVAILABLE", "UNAVAILABLE", "SEARCH_PROVIDERS_UNAVAILABLE", "RATE_LIMITED") -> "AVAILABILITY"
        else -> "TOOL_FAILURE"
    }

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
                put("state", executionState(result))
                put("retryable", ToolFailureMetadata.retryable(ToolFailureMetadata.normalize(result)))
                // Exact GitHub continuation recipes are durable verifier evidence,
                // not part of the bounded model-visible display page.
                result.executionReceipt?.let { put("receipt", JsonObject(it - "retained_remote_continuations")) }
                put(
                    "outcome",
                    when {
                        executionState(result) == "UNKNOWN_OUTCOME" -> "unknown"
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
                    val code = requireNotNull(ToolFailureMetadata.normalize(result).errorCode)
                    put(
                        "code",
                        code
                    )
                    put("category", category(code))
                    put("reconciliationRequired", code in unknownOutcomeCodes || result.executionReceipt?.get("requires_reconciliation") == JsonPrimitive(true))
                    put("retryableWithoutChanges", false)
                }
            )
        }
        put("compacted", result.retainedContent != null)
        put("evidenceAllowanceReached", result.outputBudgetExhausted)
        put("data", element(result.content))
        if (result.assistMetadata.isNotEmpty()) put("assist", JsonArray(result.assistMetadata))
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
        if (element(content).toString().toByteArray(Charsets.UTF_8).size <= maxBytes) return content
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
