package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProgressiveToolCatalog
import dev.chungjungsoo.gptmobile.data.agent.ToolInventoryPaginationEvidence
import dev.chungjungsoo.gptmobile.data.agent.tool.RepositoryGraphTool
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.github.githubOperationFamily
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Only the persisted call supplies identity and argument shape; result prose never supplies either. */
internal object ToolReportDiagnostics {
    private val actions = setOf("status", "list", "headers", "context", "comments", "reviews", "threads", "history", "commit", "branch", "changes", "checks", "workflows", "runs", "jobs", "logs", "failure", "read", "search", "create", "update", "close", "reopen", "review", "diff", "summary", "files", "tree", "get", "rerun", "rerun_failed_jobs", "cancel", "dispatch") + RepositoryOperationReportContract.toolActions
    private val errors = setOf("PRIVACY_SERVICE_UNVERIFIED", "APP_ERROR", "TOOL_FAILURE", "INVALID_ARGUMENTS", "TOOL_UNAVAILABLE", "SAVED_VIEW_UNAVAILABLE", "GITHUB_VALIDATION_FAILED", "HTTP_ERROR", "GITHUB_NOT_FOUND_OR_INACCESSIBLE", "GITHUB_REVIEW_BLOCKED", "PRECHECK_REJECTED", "TOOL_OUTCOME_UNKNOWN", "UNKNOWN_TOOL", "GITHUB_CONFLICT", "GITHUB_PR_HEAD_CHANGED", "GITHUB_PRIMARY_RATE_LIMITED", "GITHUB_SECONDARY_RATE_LIMITED", "GITHUB_FORBIDDEN_UNKNOWN", "GITHUB_ENDPOINT_PERMISSION_REQUIRED", "GITHUB_COOLDOWN_ACTIVE") + RepositoryOperationReportContract.errorCodes
    private val reasons = setOf("BUDGET_EXHAUSTED", "COMBINED_BUDGET_EXHAUSTED", "TOOL_BATCH_OVERSIZE", "DUPLICATE_INVOCATION", "REPEATED_FAILURE", "BUDGET_CHECKPOINT_UNAVAILABLE", "UNSUPPORTED_TOOL_CONTRACT", "UNSUPPORTED_OPERATION", "UNKNOWN_TOOL", "SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_CURSOR_ALTERED", "SAVED_VIEW_CURSOR_MALFORMED", "TOOL_ARGUMENT_SCHEMA_REJECTED", "TOOL_ARGUMENT_CONSTRAINT_REJECTED", "GITHUB_REPOSITORY_INPUT_REJECTED", "GITHUB_CI_RUN_SELECTOR_REQUIRED", "GITHUB_REPOSITORY_SELECTOR_INCOMPLETE", "GITHUB_SEARCH_QUERY_EMPTY", "GITHUB_SEARCH_QUERY_TOO_LONG", "GITHUB_SEARCH_FUZZY_QUERY_INVALID", "GITHUB_ARGUMENT_PRECHECK_UNCLASSIFIED", "EVIDENCE_REVISION_MISMATCH", "ISSUE_SELECTOR_REQUIRED", "PR_SELECTOR_REQUIRED", "SAVED_VIEW_CONTEXT_MISMATCH") + RepositoryOperationReportContract.toolReasonCodes

    // Closed graph-specific codes are emitted only by an exact call-bound, source-owned graph receipt.
    // Keep them separate from generic tool reasons so arbitrary tool receipts cannot impersonate graph evidence.
    private val graphReasons = setOf("GRAPH_HTTP_REJECTED", "GRAPH_TRANSPORT_UNAVAILABLE", "GRAPH_TLS_REJECTED", "GRAPH_OPERATION_FAILED", "GRAPH_REQUEST_REJECTED", "GRAPH_REVISION_MISMATCH", "GRAPH_JOB_CHANGED", "GRAPH_JOB_ACK_MISSING", "GRAPH_INTERNAL_STORAGE", "GRAPH_INTERNAL_IO", "GRAPH_INTERNAL_SERVER")
    private val phases = setOf("PREFLIGHT_REJECTED", "READ_FAILED", "OBSERVED", "OUTCOME_UNKNOWN", "PARTIAL_ACCEPTED", "COMPLETED", "FAILED", "PRECHECK_REJECTED") + RepositoryOperationReportContract.toolDispatchPhases

    private fun searchFailure(event: ToolEvent?, result: AgentToolResult): String? {
        if (event == null || event.callId != result.callId || !result.isError) return null
        val receipt = result.executionReceipt ?: return null
        if (receipt["schema"] != JsonPrimitive("gptmobile.search-operation.v1") ||
            receipt["callId"] != JsonPrimitive(result.callId) ||
            receipt["errorCode"] != JsonPrimitive(result.errorCode) ||
            receipt["state"] != JsonPrimitive(if (result.dispatched) "FAILED" else "PRECHECK_REJECTED")
        ) {
            return null
        }
        val aggregate = receipt["phase"] == JsonPrimitive("SEARCH_AGGREGATION") &&
            receipt["provider"] == JsonPrimitive("selected_search_engines") &&
            receipt["completedProviders"] == JsonPrimitive(0)
        val directResponse = event.modelToolName.substringBefore("__") == "web_search" &&
            receipt["phase"] == JsonPrimitive("PROVIDER_RESPONSE") &&
            (receipt["requestSent"] == JsonPrimitive(true) || receipt["bodyReceived"] == JsonPrimitive(true))
        return when {
            result.errorCode == "CHALLENGE" && result.dispatched && (aggregate || directResponse) -> "UPSTREAM_CHALLENGE"
            aggregate && result.errorCode in setOf("SEARCH_PROVIDERS_UNAVAILABLE", "BUDGET_EXHAUSTED", "COMBINED_BUDGET_EXHAUSTED") -> "NO_ENGINE_SUCCEEDED"
            else -> null
        }
    }

    private fun readerRefusal(event: ToolEvent?, result: AgentToolResult): String? {
        val receipt = result.executionReceipt ?: return null
        if (event?.modelToolName != "read_url" ||
            event.callId != result.callId ||
            !result.isError ||
            receipt["schema"] != JsonPrimitive("gptmobile.search-operation.v1") ||
            receipt["provider"] != JsonPrimitive("read_url") ||
            receipt["callId"] != JsonPrimitive(result.callId) ||
            receipt["errorCode"] != JsonPrimitive(result.errorCode)
        ) {
            return null
        }
        val observation = (receipt["reader_refusal"] as? JsonPrimitive)?.contentOrNull
        val before = !result.dispatched &&
            receipt["phase"] == JsonPrimitive("PRECHECK") &&
            receipt["state"] == JsonPrimitive("PRECHECK_REJECTED") &&
            receipt["requestSent"] == JsonPrimitive(false)
        val after = result.dispatched &&
            receipt["phase"] in setOf(JsonPrimitive("RETRIEVAL"), JsonPrimitive("REDIRECT_PRECHECK")) &&
            receipt["state"] == JsonPrimitive("FAILED") &&
            receipt["requestSent"] == JsonPrimitive(true)
        return when (result.errorCode) {
            "REDIRECT_FAILED" -> observation?.takeIf {
                it == "DOMAIN_SCOPE" && (before || after) || after && it in setOf("HOP_LIMIT", "LOCATION_MISSING", "LOOP", "LOCATION_INVALID")
            }
            "SOURCE_ACCESS_BLOCKED" -> when {
                after && receipt["httpStatus"] in setOf(JsonPrimitive(401), JsonPrimitive(403)) -> "HTTP_ACCESS_DENIED"
                observation == "TURN_DENIAL_CACHE" && (before || after) && receipt["httpStatus"] == null -> observation
                else -> null
            }
            else -> null
        }
    }

    private fun verificationReserveReached(event: ToolEvent?, result: AgentToolResult): Boolean =
        event != null &&
            event.callId == result.callId &&
            result.isError &&
            !result.dispatched &&
            result.errorCode == "VERIFICATION_RESERVE_REACHED" &&
            result.executionReceipt?.get("requires_reconciliation") != JsonPrimitive(true) &&
            result.executionReceipt?.get("remote_acceptance") !in setOf(JsonPrimitive("YES"), JsonPrimitive("UNKNOWN"))

    fun archivedSourceFailure(event: ToolEvent?, result: AgentToolResult): Boolean {
        if (event == null ||
            event.callId != result.callId ||
            !result.isError ||
            result.dispatched ||
            !result.sharedResult ||
            result.outputBudgetExhausted ||
            result.toolCallBudgetExhausted ||
            result.errorCode !in dev.chungjungsoo.gptmobile.data.github.GitHubTransportRecovery.codes &&
            result.errorCode != "OUTPUT_LIMIT"
        ) {
            return false
        }
        val receipt = result.executionReceipt ?: return false
        val operation = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(event.modelToolName) ?: return false
        val source = (receipt["saved_view_source_call_id"] as? JsonPrimitive)?.contentOrNull ?: return false
        if (source.isBlank() ||
            source == result.callId ||
            receipt["saved_view_owner_operation"] != JsonPrimitive(operation) ||
            receipt["saved_view_original_error_code"] != JsonPrimitive(result.errorCode) ||
            receipt["saved_view_cursor_issued"] != JsonPrimitive(true) ||
            receipt["saved_view_expired"] != JsonPrimitive(false)
        ) {
            return false
        }
        val arguments = runCatching { Json.parseToJsonElement(event.arguments) as? JsonObject }.getOrNull() ?: return false
        val cursor = (arguments["view_cursor"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return false
        return receipt["saved_view_cursor_hash"] == JsonPrimitive(dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore.digest("github.saved-view.cursor.v1\u0000$cursor"))
    }

    /** The public alias describes the retained earlier index; the unsent current result stays unchanged. */
    fun reportCode(event: ToolEvent?, result: AgentToolResult): String? {
        if (verificationReserveReached(event, result)) return "GITHUB_TASK_INCOMPLETE"
        val receipt = result.executionReceipt
        if (event?.callId == result.callId &&
            result.isError &&
            !result.dispatched &&
            result.errorCode == "DEPENDENCY_DEFERRED" &&
            receipt?.get("native_dependency_state") == JsonPrimitive("DEFERRED") &&
            receipt["dependency_admission_reasons"] == JsonArray(listOf(JsonPrimitive("TOOL_UNAVAILABLE")))
        ) {
            return "TOOL_UNAVAILABLE"
        }
        val savedIndexPrecheck = event != null &&
            event.callId == result.callId &&
            event.modelToolName.substringBefore("__") == "repository_graph" &&
            event.arguments.length <= MAX_ARGUMENT_CHARS &&
            runCatching { (Json.parseToJsonElement(event.arguments) as? JsonObject)?.get("action") }.getOrNull() == JsonPrimitive("index") &&
            result.isError &&
            !result.dispatched &&
            result.errorCode == "GRAPH_INDEX_OUTCOME_UNKNOWN" &&
            receipt?.get("schema") == JsonPrimitive(RepositoryGraphTool.RECEIPT_SCHEMA) &&
            receipt["call_id"] == JsonPrimitive(result.callId) &&
            receipt["error_code"] == JsonPrimitive(result.errorCode) &&
            receipt["phase"] == JsonPrimitive("PRECHECK_REJECTED") &&
            receipt["dispatched"] == JsonPrimitive(false) &&
            receipt["retryable"] == JsonPrimitive(false) &&
            receipt["index_outcome_unknown"] == JsonPrimitive(true) &&
            receipt["requires_reconciliation"] == JsonPrimitive(true) &&
            receipt["repair_action"] == JsonPrimitive("CHECK_SAVED_INDEX") &&
            receipt["recovery_action"] == JsonPrimitive("RECONCILE_OUTCOME")
        if (savedIndexPrecheck) return "RECONCILIATION_REQUIRED"
        if (result.errorCode == "REDIRECT_FAILED") {
            when (readerRefusal(event, result)) {
                "DOMAIN_SCOPE" -> return "SOURCE_ACCESS_BLOCKED"
                "HOP_LIMIT", "LOCATION_MISSING", "LOOP", "LOCATION_INVALID" -> return "SOURCE_UNAVAILABLE"
            }
        }
        return when (searchFailure(event, result)) {
            "UPSTREAM_CHALLENGE" -> "SOURCE_ACCESS_BLOCKED"
            "NO_ENGINE_SUCCEEDED" -> IssueReportCodes.toolResult(result.errorCode) ?: "SOURCE_UNAVAILABLE"
            else -> IssueReportCodes.toolResult(result.errorCode)
        }
    }

    /** Exists only in memory. Receipt JSON cannot impersonate a recorder-owned group key. */
    internal data class PrecheckIdentity(val digest: String)

    private fun precheckIdentity(event: ToolEvent, result: AgentToolResult, arguments: JsonObject): PrecheckIdentity? {
        val receipt = result.executionReceipt ?: return null
        if (!result.isError ||
            result.dispatched ||
            event.runId.isBlank() ||
            event.modelToolName.isBlank() ||
            receipt["requires_reconciliation"] == JsonPrimitive(true) ||
            receipt["remote_acceptance"] in setOf(JsonPrimitive("YES"), JsonPrimitive("UNKNOWN"))
        ) {
            return null
        }
        val duplicateApproval = result.errorCode == "DUPLICATE_ACTION" &&
            receipt["schema"] == JsonPrimitive("gptmobile.duplicate-approval.v1") &&
            receipt["call_id"] == JsonPrimitive(event.callId) &&
            (receipt["previous_approval_state"] as? JsonPrimitive)?.content in setOf("PENDING", "APPROVED", "EXECUTING", "COMPLETED", "OUTCOME_UNKNOWN", "INTERRUPTED")
        val eligible = duplicateApproval ||
            receipt["argument_validation_call_id"] == JsonPrimitive(event.callId) &&
            result.errorCode == "INVALID_ARGUMENTS" ||
            receipt["schema"] == JsonPrimitive("gptmobile.round-tool-catalog.v1") &&
            receipt["call_id"] == JsonPrimitive(event.callId) &&
            receipt["reason_code"] == JsonPrimitive("UNKNOWN_TOOL") &&
            receipt["catalog_revision_hash"] != null ||
            receipt["failed_stage"] == JsonPrimitive("tool_precheck") &&
            receipt["dependency_admission_reasons"] is JsonArray &&
            result.errorCode in setOf("DEPENDENCY_DEFERRED", "DEPENDENCY_FAILED", "NOT_DISPATCHED")
        if (!eligible) return null
        val scope = receipt.filterKeys { key ->
            key in setOf("schema", "previous_approval_state", "reason_code", "reasonCode", "missing_tool_hash", "discovery_available", "native_dependency_state", "dependency_admission_reasons", "dependency_call_id_hashes", "dependency_repeat_scope", "dependency_repeat_limit", "argument_repair_signature_hash", "tool_schema_hash", "recovery_action", "repair_action") ||
                listOf("grant", "policy", "authority", "revision", "checkpoint_binding").any(key::contains)
        }
        val identity = JsonArray(
            listOf(
                JsonPrimitive(event.runId),
                JsonPrimitive(event.connectionUidSnapshot),
                JsonPrimitive(event.toolName),
                JsonPrimitive(event.modelToolName),
                arguments,
                JsonPrimitive(result.errorCode),
                JsonObject(scope)
            )
        )
        return PrecheckIdentity(dev.chungjungsoo.gptmobile.data.permissions.ScopedToolGrant.canonicalHash(identity))
    }

    fun fields(event: ToolEvent?, result: AgentToolResult, precheckReason: String? = null): Map<String, Any?> = buildMap {
        if (event == null || event.callId != result.callId) return@buildMap
        put("diagnostic_area", "TOOL")
        put("diagnostic_capture_mode", "INCIDENT")
        if (verificationReserveReached(event, result)) {
            // Acquisition stopped with calls still reserved for verification. Keep
            // that reason without calling the entire task allowance exhausted.
            put("reason_code", "VERIFICATION_RESERVE_REACHED")
            put("tool_budget_phase", "BEFORE_DISPATCH")
            result.toolCallBudgetUsed?.let { put("tool_budget_calls_used", it) }
            result.toolCallBudgetLimit?.let { put("tool_budget_calls_limit", it) }
        }
        put("run_id_hash", IssueReportContext.identifierHash("run", event.runId))
        put("call_id_hash", IssueReportContext.identifierHash("call", event.callId))
        put("tool_name_hash", IssueReportContext.identifierHash("tool-name", event.modelToolName))
        putAll(dev.chungjungsoo.gptmobile.data.agent.ToolReconciliationEvidence.retained(result.errorCode, result.executionReceipt).orEmpty())
        putAll(ToolInventoryPaginationEvidence.reportFields(result, event.callId, event.modelToolName, event.arguments))
        putAll(dev.chungjungsoo.gptmobile.data.agent.GitHubSearchValidationEvidence.reportFields(result, event.callId, event.modelToolName, event.arguments))
        readerRefusal(event, result)?.let { put("url_reader_refusal", it) }
        searchFailure(event, result)?.let { observation ->
            put("search_failure_observation", observation)
            val receipt = requireNotNull(result.executionReceipt)
            if (receipt["phase"] == JsonPrimitive("SEARCH_AGGREGATION")) {
                fun count(key: String) = (receipt[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 0..1024 }
                val attempted = count("attemptCount")
                val sent = count("dispatchedAttemptCount")
                val refused = count("budgetRejectedAttemptCount")
                if (attempted != null && sent != null && refused != null && sent + refused <= attempted && (sent > 0) == result.dispatched) {
                    put("search_attempt_count", attempted)
                    put("search_dispatched_attempt_count", sent)
                    put("search_budget_rejected_attempt_count", refused)
                }
            }
        }
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
        val admission = (result.executionReceipt?.get("dependency_admission_reasons") as? JsonArray)?.singleOrNull()
        if (event.modelToolName == "read_url" && result.isError && !result.dispatched && result.errorCode == "NOT_DISPATCHED") {
            val observation = when (admission) {
                JsonPrimitive("REPEAT_LIMIT") -> "REPEAT_LIMIT"
                JsonPrimitive("REPEATED_FAILURE") -> "REPEATED_FAILURE"
                else -> null
            }
            observation?.let { put("url_reader_availability", it) }
        }
        val reader = result.executionReceipt
        if (event.modelToolName == "read_url" &&
            result.isError &&
            result.errorCode == "SOURCE_UNAVAILABLE" &&
            reader?.get("schema") == JsonPrimitive("gptmobile.search-operation.v1") &&
            reader["provider"] == JsonPrimitive("read_url") &&
            reader["callId"] == JsonPrimitive(result.callId) &&
            reader["errorCode"] == JsonPrimitive(result.errorCode)
        ) {
            val observation = if (result.dispatched &&
                reader["requestSent"] == JsonPrimitive(true) &&
                reader["httpStatus"] in setOf(JsonPrimitive(404), JsonPrimitive(405), JsonPrimitive(410))
            ) {
                JsonPrimitive("HTTP_UNAVAILABLE")
            } else if (!result.dispatched && reader["requestSent"] == JsonPrimitive(false) && reader["httpStatus"] == null) {
                reader["source_availability_observation"]?.takeIf { it in setOf(JsonPrimitive("NEGATIVE_CACHE"), JsonPrimitive("TURN_SUPPRESSION")) }
            } else {
                null
            }
            observation?.let { put("url_reader_availability", it) }
        }
        val reason = (result.executionReceipt?.get("reason_code") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: (result.executionReceipt?.get("reasonCode") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: precheckReason
        reason?.takeIf { it in reasons }?.let { put("tool_reason_code", it) }
        // Report a missing immutable GitHub path only when the original read's
        // call-bound dispatch receipt proves that no upstream operation ran.
        // Retain the closed reason, never the path or the result preview.
        val pathReceipt = result.executionReceipt
        if (result.errorCode == "TOOL_NOT_AVAILABLE" &&
            !result.dispatched &&
            pathReceipt?.get("schema") == JsonPrimitive("gptmobile.round-tool-catalog.v1") &&
            pathReceipt["call_id"] == JsonPrimitive(event.callId) &&
            pathReceipt["dispatched"] == JsonPrimitive(false)
        ) {
            put("tool_error_code", "TOOL_UNAVAILABLE")
            if (result.isError &&
                result.callId == event.callId &&
                pathReceipt["reason_code"] == JsonPrimitive("UNKNOWN_TOOL") &&
                pathReceipt["phase"] == JsonPrimitive("PREFLIGHT_REJECTED") &&
                pathReceipt["retryable"] == JsonPrimitive(false)
            ) {
                (pathReceipt["discovery_available"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull?.let { put("round_catalog_discovery_available", it) }
                (pathReceipt["catalog_revision_hash"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let {
                    put("catalog_revision_hash", it)
                    put("catalog_rejection_count", 1)
                    (pathReceipt["missing_tool_hash"] as? JsonPrimitive)?.contentOrNull?.takeIf { value -> value.matches(Regex("[a-f0-9]{64}")) }?.let { value -> put("missing_tool_hash", value) }
                }
            }
            (pathReceipt["available_tool_count"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..100_000 }?.let { put("round_catalog_tool_count", it) }
        }
        if (result.errorCode == "INVALID_ARGUMENTS" &&
            !result.dispatched &&
            pathReceipt?.get("argument_validation_call_id") == JsonPrimitive(event.callId) &&
            pathReceipt["reason_code"] == JsonPrimitive("TOOL_ARGUMENT_SCHEMA_REJECTED")
        ) {
            (pathReceipt["unknown_field_hashes"] as? JsonArray)?.let { put("unknown_field_hashes", it) }
        }
        val missingPathPrecheck = event.modelToolName.substringBefore("__") == "github_read" &&
            result.isError &&
            !result.dispatched &&
            result.errorCode == "GITHUB_PATH_NOT_FOUND" &&
            pathReceipt?.get("schema") == JsonPrimitive("github.dispatch.v1") &&
            pathReceipt["call_id"] == JsonPrimitive(result.callId) &&
            pathReceipt["error_code"] == JsonPrimitive(result.errorCode) &&
            pathReceipt["phase"] == JsonPrimitive("PREFLIGHT_REJECTED") &&
            pathReceipt["dispatched"] == JsonPrimitive(false) &&
            pathReceipt["requires_reconciliation"] == JsonPrimitive(false)
        if (missingPathPrecheck) put("tool_reason_code", "GITHUB_PATH_NOT_FOUND")
        val arguments = event.arguments.takeIf { it.length <= MAX_ARGUMENT_CHARS }
            ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        if (arguments != null) precheckIdentity(event, result, arguments)?.let { put("local_precheck_identity", it) }
        val stoppedPhoneIndex = result.isError &&
            result.errorCode == "PHONE_INDEX_INTERRUPTED" &&
            event.modelToolName.substringBefore("__") == "repository_graph" &&
            pathReceipt?.get("schema") == JsonPrimitive("gptmobile.phone-index-interruption.v1") &&
            pathReceipt["call_id"] == JsonPrimitive(result.callId) &&
            pathReceipt["tool"] == JsonPrimitive(event.modelToolName) &&
            pathReceipt["error_code"] == JsonPrimitive(result.errorCode) &&
            pathReceipt["action"] == JsonPrimitive("index") &&
            pathReceipt["map_source"] == JsonPrimitive("phone") &&
            pathReceipt["local_work_stopped"] == JsonPrimitive(true) &&
            pathReceipt["requires_reconciliation"] == JsonPrimitive(false) &&
            pathReceipt["request_replay_allowed"] == JsonPrimitive(false) &&
            listOf("mutation_requests", "accepted_mutations", "rejected_mutations", "unknown_mutations").all { pathReceipt[it] == JsonPrimitive(0) }
        val stoppedPhoneDeadline = result.isError &&
            result.errorCode == "READ_DEADLINE" &&
            event.modelToolName.substringBefore("__") == "repository_graph" &&
            pathReceipt?.get("schema") == JsonPrimitive(RepositoryGraphTool.RECEIPT_SCHEMA) &&
            pathReceipt["call_id"] == JsonPrimitive(result.callId) &&
            pathReceipt["error_code"] == JsonPrimitive(result.errorCode) &&
            (arguments?.get("action") as? JsonPrimitive)?.isString == true &&
            pathReceipt["action"] == arguments["action"] &&
            arguments["map_source"] in setOf(null, JsonPrimitive("phone")) &&
            pathReceipt["map_source"] == JsonPrimitive("phone") &&
            pathReceipt["failed_stage"] == JsonPrimitive("phone_read_deadline") &&
            pathReceipt["dispatched"] == JsonPrimitive(result.dispatched) &&
            pathReceipt["retryable"] == JsonPrimitive(false) &&
            pathReceipt["local_work_stopped"] == JsonPrimitive(true) &&
            pathReceipt["requires_reconciliation"] == JsonPrimitive(false) &&
            pathReceipt["index_outcome_unknown"] == JsonPrimitive(false) &&
            pathReceipt["current_index_outcome_unknown"] == JsonPrimitive(false) &&
            pathReceipt["replay_safe"] == JsonPrimitive(false) &&
            pathReceipt["request_replay_allowed"] == JsonPrimitive(false) &&
            pathReceipt["authorization_recheck_required"] == JsonPrimitive(true) &&
            listOf("mutation_requests", "accepted_mutations", "rejected_mutations", "unknown_mutations").all { pathReceipt[it] == JsonPrimitive(0) }
        if (stoppedPhoneDeadline && pathReceipt?.get("phase") == JsonPrimitive("CANCELLED_LOCALLY")) {
            // Use the existing public stage only for this bound local stop; retain its exact code and phase.
            put("failed_stage", "tool_execution")
        }
        (result.executionReceipt?.get("phase") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?.takeIf { it in phases && (it != "CANCELLED_LOCALLY" || stoppedPhoneIndex || stoppedPhoneDeadline) }?.let { put("tool_dispatch_phase", it) }
        put("original_reason_code_hash", IssueReportContext.identifierHash("tool-reason-code", reason))
        val action = (arguments?.get("action") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (arguments != null) {
            action?.takeIf { it in actions }?.let { put("tool_action", it) }
            put("tool_action_hash", IssueReportContext.identifierHash("tool-action", action))
            action?.takeIf { it in IssueReportIncidentCapsule.actions }?.let { put("native_tool_action", it) }
            shapeHash(arguments)?.let { put("argument_shape_hash", it) }
            putAll(typeShape(arguments, "argument"))
        }
        if (result.isError && !result.dispatched && arguments != null) {
            if (result.errorCode == "INVALID_ARGUMENTS" && action != null && arguments.keys == setOf("action")) {
                if (action == "stage") put("tool_action", action)
                val family = when (event.toolName.githubOperationFamily()) {
                    "repository_workspace" -> "REPOSITORY_WORKSPACE"
                    "repository_graph" -> "REPOSITORY_GRAPH"
                    "github" -> "GITHUB_NATIVE"
                    in dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.names -> "GITHUB_OPERATION"
                    else -> "OTHER"
                }
                put("action_only_tool_family", family)
                val receipt = result.executionReceipt
                val validatorCall = receipt?.get("argument_validation_call_id")
                val validation = when {
                    receipt.isNullOrEmpty() -> "NO_RECEIPT"
                    validatorCall == JsonPrimitive(event.callId) -> "CALL_BOUND_VALIDATOR"
                    validatorCall != null -> "VALIDATOR_CALL_MISMATCH"
                    else -> "NO_VALIDATOR_ID"
                }
                put("action_only_validation", validation)
            }
            when (event.modelToolName.githubOperationFamily()) {
                ProgressiveToolCatalog.NAME -> if (result.errorCode == "INVALID_ARGUMENTS") {
                    val names = arguments["names"]
                    val selector = when {
                        arguments.keys.any { it in setOf("inventory_cursor", "inventory_version") } &&
                            arguments.keys.any { it in setOf("query", "action", "names") } -> "MIXED_INVENTORY_SELECTORS"
                        "action" in arguments && (arguments["action"] as? JsonPrimitive)?.isString != true -> "ACTION_NOT_STRING"
                        "action" in arguments && names == null -> "ACTION_WITHOUT_NAMES"
                        names == kotlinx.serialization.json.JsonNull -> "NAMES_NULL"
                        names != null && names !is JsonArray -> "NAMES_NOT_ARRAY"
                        names is JsonArray && names.any { (it as? JsonPrimitive)?.isString != true } -> "NAMES_CONTAIN_NON_STRING"
                        "action" in arguments && names is JsonArray && names.size != 1 -> "ACTION_REQUIRES_ONE_NAME"
                        else -> "OTHER_SCHEMA_CONSTRAINT"
                    }
                    put("catalog_selector_state", selector)
                    if (names is JsonArray) put("catalog_selector_count", names.size.coerceAtMost(100_000))
                }
                "github_read" -> if (result.errorCode in setOf("EVIDENCE_UNAVAILABLE", "SAVED_VIEW_UNAVAILABLE", "SAVED_VIEW_CURSOR_ALTERED", "SAVED_VIEW_WRONG_OPERATION")) {
                    val evidence = (arguments["evidence_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val kind = when {
                        "view_cursor" in arguments -> "SAVED_CURSOR"
                        evidence == null -> "NO_HANDLE"
                        evidence.matches(Regex("ev_[a-f0-9]{24}")) -> "VERIFICATION_RECEIPT"
                        evidence.matches(Regex("[a-f0-9]{64}")) -> "DIGEST_SHAPED"
                        else -> "NONCANONICAL_HANDLE"
                    }
                    put("read_handle_kind", kind)
                }
                "github_search" -> if (result.errorCode == "INVALID_ARGUMENTS") {
                    (arguments["query"] as? JsonPrimitive)?.takeIf { it.isString }?.let { put("search_query_length", it.content.length) }
                    put("search_explicit_mode", "mode" in arguments)
                }
            }
        }
        val receipt = result.executionReceipt
        // Retain only a bounded hash of an already-captured, sanitized exception
        // type. Never infer one from generic TOOL_FAILURE text or a precheck label.
        // A source-owned, call-bound receipt must establish that no operation ran.
        if (
            result.isError &&
            result.errorCode == "TOOL_FAILURE" &&
            !result.dispatched &&
            event.modelToolName.substringBefore("__").startsWith("github_") &&
            receipt != null &&
            receipt["schema"] in setOf(JsonPrimitive("github.dispatch.v1"), JsonPrimitive("gptmobile.tool-execution.v1")) &&
            receipt["call_id"] == JsonPrimitive(result.callId) &&
            receipt["error_code"] == JsonPrimitive(result.errorCode) &&
            receipt["dispatched"] == JsonPrimitive(false) &&
            receipt["requires_reconciliation"] == JsonPrimitive(false) &&
            receipt["phase"] in setOf(JsonPrimitive("PREFLIGHT_REJECTED"), JsonPrimitive("tool_precheck"))
        ) {
            val observed = (receipt["cause_types"] as? JsonArray)
                ?.takeIf { it.size in 1..8 }
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull }
                ?.takeIf { types ->
                    types.isNotEmpty() &&
                        types.all { it.length in 1..160 && it.matches(Regex("[A-Za-z0-9_.$]+")) }
                }
            observed?.distinct()?.mapNotNull { IssueReportContext.identifierHash("tool-exception-type", it) }
                ?.takeIf { it.isNotEmpty() }
                ?.let { put("exception_type_hashes", JsonArray(it.map(::JsonPrimitive))) }
        }
        // GitHubDispatchAccounting owns the dispatch receipt. Match it to the
        // persisted tool call, and never derive a revision from response prose.
        val operation = event.modelToolName.githubOperationFamily()
        if (operation == "github_pr" &&
            action == "context" &&
            result.isError &&
            result.errorCode == "GITHUB_PR_HEAD_CHANGED" &&
            result.dispatched &&
            receipt?.get("schema") == JsonPrimitive("github.dispatch.v1") &&
            receipt["live_read_performed"] == JsonPrimitive(true)
        ) {
            put("pr_context_revision_changed", true)
            for ((source, target) in listOf(
                "read_requests" to "pr_context_read_requests",
                "read_response_observations" to "pr_context_read_responses"
            )) {
                (receipt[source] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                    ?.takeIf { it in 0..100 }?.let { put(target, it) }
            }
        }
        // A successful upstream HTTP response does not prove full pagination coverage.
        // Preserve only typed counts and flags from the call-bound traversal receipt,
        // never upstream record IDs, URLs or unbounded provider text.
        val paginationState = (receipt?.get("traversal_state") as? JsonPrimitive)?.contentOrNull
        val pagedOperation = when (operation) {
            "github_ci" -> true
            "github_context" -> action in setOf("branches", "commits", "changes", "checks")
            "github_pr" -> action in setOf("list", "checks", "context")
            "github_issue" -> action == "list"
            else -> false
        }
        if (pagedOperation &&
            result.isError &&
            result.errorCode in setOf("GITHUB_PAGINATION_INCOMPLETE", "GITHUB_PAGINATION_STALLED") &&
            (paginationState == "INCOMPLETE" || paginationState == "STALLED")
        ) {
            val pagination = receipt?.get("pagination_progress") as? JsonObject
            if (pagination?.get("schema") == JsonPrimitive("github.pagination-progress.v1")) {
                for ((source, target) in listOf(
                    "remote_page" to "remote_pagination_page",
                    "reported_total" to "remote_pagination_reported_total",
                    "unique_records_observed" to "remote_pagination_unique_records",
                    "duplicate_records_on_page" to "remote_pagination_duplicate_records"
                )) {
                    (pagination[source] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                        ?.takeIf { it in 0..16_000_000 }?.let { put(target, it) }
                }
                (pagination["remote_pages_observed"] as? JsonArray)?.takeIf { it.size <= 512 }
                    ?.let { put("remote_pagination_pages_observed", it.size) }
                for ((source, target) in listOf(
                    "contiguous_from_first_page" to "remote_pagination_contiguous",
                    "upstream_changed_during_traversal" to "remote_pagination_changed"
                )) {
                    (pagination[source] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                        ?.let { put(target, it) }
                }
                paginationState?.let { put("remote_pagination_reason", it) }
            }
        }
        if (event.modelToolName.substringBefore("__") == "repository_graph" &&
            receipt?.get("schema") == JsonPrimitive(RepositoryGraphTool.RECEIPT_SCHEMA) &&
            receipt["call_id"] == JsonPrimitive(result.callId) &&
            action != null &&
            receipt["action"] == JsonPrimitive(action)
        ) {
            // The HTTP status and uncertainty belong to the original graph operation,
            // not the report preview. A mismatched/edited receipt must not supply its cause.
            if (result.isError && receipt["error_code"] == JsonPrimitive(result.errorCode)) {
                if (action == "task_context_bundle" &&
                    receipt["map_source"] == JsonPrimitive("phone") &&
                    receipt["incident_capture_site"] == JsonPrimitive("REPOSITORY_GRAPH_CATCH") &&
                    receipt["incident_phase_provenance"] == JsonPrimitive("CAPTURE_BOUNDARY_ONLY") &&
                    result.errorCode == "REPOSITORY_GRAPH_UNAVAILABLE"
                ) {
                    (receipt["graph_local_phase"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        ?.takeIf { it in IssueReportDiagnostics.graphLocalPhases }?.let { put("graph_local_phase", it) }
                    (receipt["graph_local_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        ?.takeIf { it in IssueReportDiagnostics.graphLocalReasons }?.let { put("graph_local_reason", it) }
                }
                val reason = (receipt["reason_code"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                if (reason == "TRANSPORT_UNAVAILABLE" &&
                    result.errorCode == "REPOSITORY_GRAPH_UNAVAILABLE" &&
                    result.dispatched &&
                    receipt["dispatched"] == JsonPrimitive(true) &&
                    receipt["phase"] in setOf(JsonPrimitive("FAILED"), JsonPrimitive("UNKNOWN_OUTCOME"))
                ) {
                    put("tool_reason_code", "GRAPH_TRANSPORT_UNAVAILABLE")
                    (receipt["graph_transport_kind"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        ?.takeIf { it in IssueReportDiagnostics.graphTransportKinds }?.let { put("graph_transport_kind", it) }
                } else {
                    reason?.takeIf { it in graphReasons }?.let { put("tool_reason_code", it) }
                }
            }
            (receipt["graph_index_attempt_hash"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.let { put("graph_index_attempt_hash", it) }
        }
        for (key in listOf("github_transport_kind", "github_transport_wait_ms", "read_range_count", "read_invalid_window_count", "read_window_rejections")) {
            receipt?.get(key)?.let { put(key, it) }
        }
        if (event.modelToolName.substringBefore("__").startsWith("github_")) {
            if (result.isError &&
                result.dispatched &&
                result.errorCode == "GITHUB_VALIDATION_FAILED" &&
                receipt?.get("schema") == JsonPrimitive("github.dispatch.v1") &&
                receipt["error_code"] == JsonPrimitive(result.errorCode) &&
                receipt["phase"] == JsonPrimitive("READ_FAILED") &&
                receipt["http_status"] == JsonPrimitive(422)
            ) {
                for (key in dev.chungjungsoo.gptmobile.data.agent.tool.GitHubToolErrorDiagnostics.validationKeys) {
                    receipt[key]?.let { put(key, it) }
                }
            }
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
        if (result.combinedWorkBudgetExhausted && !result.dispatched && result.errorCode == "BUDGET_EXHAUSTED") {
            put("tool_budget_kind", "COMBINED_WORK")
            put("tool_budget_phase", "BEFORE_DISPATCH")
        } else if (result.outputBudgetExhausted || result.toolCallBudgetExhausted) {
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
            if (event.modelToolName.substringBefore("__") == "web_search" &&
                result.isError &&
                !result.dispatched &&
                result.errorCode == "BUDGET_EXHAUSTED" &&
                result.outputBudgetExhausted &&
                receipt?.get("schema") == JsonPrimitive("gptmobile.search-operation.v1") &&
                receipt["callId"] == JsonPrimitive(result.callId) &&
                receipt["phase"] == JsonPrimitive("SEARCH_AGGREGATION") &&
                receipt["state"] == JsonPrimitive("PRECHECK_REJECTED") &&
                receipt["retryable"] == JsonPrimitive(false)
            ) {
                (receipt["tool_budget_bytes_available"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                    ?.takeIf { it in 0..1023 }?.let { put("tool_budget_bytes_available", it) }
            }
        }
        when (event.modelToolName.substringBefore("__")) {
            "device_location" -> if (result.errorCode == "PERMISSION_DENIED" && !result.dispatched) put("tool_permission_scope", "ANDROID_LOCATION")
            "github_watch_checks" -> if (result.errorCode == "PERMISSION_DENIED" && !result.dispatched) put("tool_permission_scope", "GITHUB_LOCAL_GRANT")
        }
        val resolved = receipt?.get("resolved_arguments") as? JsonObject
        resolved?.let {
            if (action == null && receipt?.get("call_id") == JsonPrimitive(result.callId) && receipt["schema"] == JsonPrimitive("github.dispatch.v1")) {
                (it["action"] as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull
                    ?.takeIf { name -> name in IssueReportIncidentCapsule.actions }?.let { name -> put("native_tool_action", name) }
            }
            shapeHash(it)?.let { hash -> put("resolved_argument_shape_hash", hash) }
            putAll(typeShape(it, "resolved_argument"))
        }
        if (event.modelToolName.substringBefore("__") == "github_issue" &&
            result.isError &&
            result.callId == event.callId &&
            result.errorCode == "INVALID_ARGUMENTS" &&
            !result.dispatched &&
            receipt?.get("issue_continuation_rejected") == JsonPrimitive(true)
        ) {
            receipt?.get("issue_continuation_state")?.let { value ->
                IssueReportIncidentCapsule.validValue("issue_continuation_state", value)?.let { put("issue_continuation_state", it) }
            }
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
