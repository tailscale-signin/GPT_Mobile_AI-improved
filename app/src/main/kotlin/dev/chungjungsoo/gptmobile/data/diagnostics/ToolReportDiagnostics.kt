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
    private val reasons = setOf("BUDGET_EXHAUSTED", "TOOL_BATCH_OVERSIZE", "DUPLICATE_INVOCATION", "REPEATED_FAILURE", "BUDGET_CHECKPOINT_UNAVAILABLE", "UNSUPPORTED_TOOL_CONTRACT", "UNSUPPORTED_OPERATION", "UNKNOWN_TOOL", "SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_CURSOR_ALTERED", "TOOL_ARGUMENT_SCHEMA_REJECTED", "TOOL_ARGUMENT_CONSTRAINT_REJECTED", "ISSUE_SELECTOR_REQUIRED", "PR_SELECTOR_REQUIRED")
    private val phases = setOf("PREFLIGHT_REJECTED", "READ_FAILED", "OBSERVED", "OUTCOME_UNKNOWN", "PARTIAL_ACCEPTED", "COMPLETED", "FAILED", "PRECHECK_REJECTED")

    fun fields(event: ToolEvent?, result: AgentToolResult, precheckReason: String? = null): Map<String, Any?> = buildMap {
        if (event == null || event.callId != result.callId) return@buildMap
        put("diagnostic_area", "TOOL")
        put("diagnostic_capture_mode", "INCIDENT")
        put("run_id_hash", IssueReportContext.identifierHash("run", event.runId))
        put("call_id_hash", IssueReportContext.identifierHash("call", event.callId))
        put("tool_name_hash", IssueReportContext.identifierHash("tool-name", event.modelToolName))
        result.errorCode?.takeIf { it in errors }?.let { put("tool_error_code", it) }
        put("original_error_code_hash", IssueReportContext.identifierHash("tool-error-code", result.errorCode))
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
