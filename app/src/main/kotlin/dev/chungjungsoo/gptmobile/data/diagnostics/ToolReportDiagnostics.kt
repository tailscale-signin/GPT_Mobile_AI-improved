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
    private val actions = setOf("status", "list", "headers", "context", "comments", "reviews", "threads", "history", "commit", "branch", "changes", "checks", "workflows", "runs", "jobs", "logs", "failure", "read", "search", "create", "update", "close", "reopen", "review", "diff", "summary", "files", "tree", "get", "rerun", "rerun_failed_jobs", "cancel", "dispatch")
    private val errors = setOf("APP_ERROR", "TOOL_FAILURE", "INVALID_ARGUMENTS", "TOOL_UNAVAILABLE", "SAVED_VIEW_UNAVAILABLE", "GITHUB_VALIDATION_FAILED", "HTTP_ERROR", "GITHUB_NOT_FOUND_OR_INACCESSIBLE", "GITHUB_REVIEW_BLOCKED", "PRECHECK_REJECTED", "TOOL_OUTCOME_UNKNOWN", "UNKNOWN_TOOL", "GITHUB_CONFLICT", "GITHUB_PRIMARY_RATE_LIMITED", "GITHUB_SECONDARY_RATE_LIMITED", "GITHUB_FORBIDDEN_UNKNOWN", "GITHUB_ENDPOINT_PERMISSION_REQUIRED")
    private val reasons = setOf("BUDGET_EXHAUSTED", "TOOL_BATCH_OVERSIZE", "DUPLICATE_INVOCATION", "REPEATED_FAILURE", "BUDGET_CHECKPOINT_UNAVAILABLE", "UNSUPPORTED_TOOL_CONTRACT", "UNSUPPORTED_OPERATION", "UNKNOWN_TOOL", "SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_CURSOR_ALTERED", "TOOL_ARGUMENT_SCHEMA_REJECTED", "TOOL_ARGUMENT_CONSTRAINT_REJECTED", "ISSUE_SELECTOR_REQUIRED", "PR_SELECTOR_REQUIRED", "SAVED_VIEW_CONTEXT_MISMATCH")
    private val phases = setOf("PREFLIGHT_REJECTED", "READ_FAILED", "OBSERVED", "OUTCOME_UNKNOWN", "PARTIAL_ACCEPTED", "COMPLETED", "FAILED", "PRECHECK_REJECTED")

    fun fields(event: ToolEvent?, result: AgentToolResult, precheckReason: String? = null): Map<String, Any?> = buildMap {
        if (event == null || event.callId != result.callId) return@buildMap
        put("diagnostic_area", "TOOL")
        put("diagnostic_capture_mode", "INCIDENT")
        put("run_id_hash", IssueReportContext.identifierHash("run", event.runId))
        put("call_id_hash", IssueReportContext.identifierHash("call", event.callId))
        put("tool_name_hash", IssueReportContext.identifierHash("tool-name", event.modelToolName))
        result.errorCode?.takeIf { it in errors }?.let { put("tool_error_code", it) }
        if (event.modelToolName == "read_url" &&
            result.errorCode in setOf("DNS_FAILED", "DNS_RECOVERY") &&
            result.executionReceipt?.get("schema") == JsonPrimitive("gptmobile.search-operation.v1")
        ) {
            put("tool_error_code", "DNS_RESOLUTION_FAILED")
            put("url_reader_dns_state", if (result.errorCode == "DNS_RECOVERY") "RECOVERY_COOLDOWN" else "LOOKUP_FAILED")
        }
        put("original_error_code_hash", IssueReportContext.identifierHash("tool-error-code", result.errorCode))
        if (result.executionReceipt?.containsKey("mcp_failure_phase") == true) {
            put("mcp_connection_hash", IssueReportContext.identifierHash("mcp-connection", event.connectionUidSnapshot))
        }
        val reason = (result.executionReceipt?.get("reason_code") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: (result.executionReceipt?.get("reasonCode") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: precheckReason
        reason?.takeIf { it in reasons }?.let { put("tool_reason_code", it) }
        (result.executionReceipt?.get("phase") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?.takeIf { it in phases }?.let { put("tool_dispatch_phase", it) }
        put("original_reason_code_hash", IssueReportContext.identifierHash("tool-reason-code", reason))
        val arguments = event.arguments.takeIf { it.length <= MAX_ARGUMENT_CHARS }
            ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        if (arguments != null) {
            val action = (arguments["action"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            action?.takeIf { it in actions }?.let { put("tool_action", it) }
            put("tool_action_hash", IssueReportContext.identifierHash("tool-action", action))
            action?.takeIf { it in IssueReportIncidentCapsule.actions }?.let { put("native_tool_action", it) }
            shapeHash(arguments)?.let { put("argument_shape_hash", it) }
            putAll(typeShape(arguments, "argument"))
        }
        val receipt = result.executionReceipt
        for (key in listOf("github_transport_kind", "github_transport_wait_ms", "read_range_count", "read_invalid_window_count", "read_window_rejections")) {
            receipt?.get(key)?.let { put(key, it) }
        }
        if (event.modelToolName.substringBefore("__").startsWith("github_")) {
            if (result.errorCode in setOf("GITHUB_TRANSIENT_NETWORK", "GITHUB_TRANSIENT_SERVER", "GITHUB_CIRCUIT_OPEN") &&
                receipt?.get("schema") == JsonPrimitive("github.dispatch.v1")
            ) {
                for ((source, target) in listOf(
                    "read_requests" to "github_transport_read_attempts",
                    "unobserved_read_requests" to "github_transport_unanswered_reads",
                    "read_response_observations" to "github_transport_http_responses"
                )) {
                    receipt[source]?.let { put(target, it) }
                }
            }
            (arguments?.get("per_page") as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull()
                ?.takeIf { it in 0..16_000_000 }?.let { put("argument_page_size", it) }
        }
        if (result.outputBudgetExhausted || result.toolCallBudgetExhausted) {
            put(
                "tool_budget_kind",
                when {
                    result.outputBudgetExhausted && result.toolCallBudgetExhausted -> "BOTH"
                    result.outputBudgetExhausted -> "RESULT_BYTES"
                    else -> "CALLS"
                }
            )
            put("tool_budget_phase", if (result.dispatched) "AFTER_DISPATCH" else "BEFORE_DISPATCH")
            if (receipt?.get("schema") == JsonPrimitive("github.dispatch.v1") &&
                receipt["phase"] == JsonPrimitive("OBSERVED") &&
                receipt["parent_result_successful"] == JsonPrimitive(true)
            ) {
                put("tool_execution_observation", "REMOTE_READ_SUCCEEDED")
            }
            result.toolResultBudgetUsedBytes?.let { put("tool_result_bytes_used", it) }
            result.toolResultBudgetLimitBytes?.let { put("tool_result_bytes_limit", it) }
            result.toolCallBudgetUsed?.let { put("tool_budget_calls_used", it) }
            result.toolCallBudgetLimit?.let { put("tool_budget_calls_limit", it) }
        }
        when (event.modelToolName.substringBefore("__")) {
            "device_location" -> if (result.errorCode == "PERMISSION_DENIED" && !result.dispatched) put("tool_permission_scope", "ANDROID_LOCATION")
            "github_watch_checks" -> if (result.errorCode == "PERMISSION_DENIED" && !result.dispatched) put("tool_permission_scope", "GITHUB_LOCAL_GRANT")
        }
        val resolved = receipt?.get("resolved_arguments") as? JsonObject
        resolved?.let {
            shapeHash(it)?.let { hash -> put("resolved_argument_shape_hash", hash) }
            putAll(typeShape(it, "resolved_argument"))
        }
        val validationFields = listOf("missing_fields", "invalid_fields", "validation_field_names").flatMap { key ->
            (receipt?.get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.contentOrNull }
        }.filter { it in IssueReportIncidentCapsule.fieldNames }.distinct().sorted().take(8)
        if (validationFields.isNotEmpty()) put("validation_field_names", validationFields)
    }

    /** Public schema names and JSON types only; values and unrecognized names never leave the client. */
    private fun typeShape(arguments: JsonObject, prefix: String): Map<String, Any?> {
        val fields = arguments.entries.asSequence().take(256).filter { it.key in IssueReportIncidentCapsule.fieldNames }.sortedBy { it.key }.take(8).toList()
        if (fields.isEmpty()) return emptyMap()
        return mapOf(
            "${prefix}_field_names" to fields.map { it.key },
            "${prefix}_field_types" to fields.map { (_, value) ->
                when (value) {
                    JsonNull -> "NULL"
                    is JsonObject -> "OBJECT"
                    is JsonArray -> "ARRAY"
                    is JsonPrimitive -> when {
                        value.isString -> "STRING"
                        value.booleanOrNull != null -> "BOOLEAN"
                        else -> "NUMBER"
                    }
                }
            }
        )
    }

    /** The digest covers field names and JSON types, never scalar argument values. */
    internal fun shapeHash(arguments: JsonObject): String? {
        var nodes = 0
        var complete = true
        fun shape(value: JsonElement, depth: Int): String {
            if (!complete || ++nodes > 256 || depth > 12 || value is JsonObject && value.size > 256 || value is JsonArray && value.size > 256) {
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
