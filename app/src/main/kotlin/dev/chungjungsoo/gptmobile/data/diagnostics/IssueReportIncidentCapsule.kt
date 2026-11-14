package dev.chungjungsoo.gptmobile.data.diagnostics

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Versioned, closed incident evidence. It describes observations and never authorizes a retry. */
internal object IssueReportIncidentCapsule {
    val phases = IssueReportContext.stages + setOf("acceptance_verification", "review_comparison", "approval", "model_preparation", "unknown")
    val actions = setOf(
        "context", "headers", "list", "search", "branches", "commits", "compare", "diff", "files", "file", "tree", "read", "resolve_ref",
        "comments", "history", "reviews", "threads", "checks", "status", "run", "runs", "jobs", "logs", "artifacts", "workflows",
        "create", "update", "close", "reopen", "comment", "create_pull_request", "create_issue", "update_issue", "update_pull_request",
        "merge", "review", "submit_review", "resolve_thread", "rerun", "cancel", "dispatch", "download", "definitions", "capabilities",
        "continue", "task_status", "verify", "checkpoint", "snapshot", "selection", "catalog", "releases", "tags", "labels", "milestones"
    )
    val fieldNames = setOf(
        "action", "connection_id", "connection_uid", "owner", "repo", "repository", "repository_id", "repository_full_name",
        "issue_number", "pull_number", "pr_number", "pull_request_number", "number", "title", "body", "state", "state_reason", "labels",
        "assignees", "milestone", "head", "base", "ref", "sha", "base_sha", "head_sha", "branch", "path", "paths", "content", "message",
        "draft", "maintainer_can_modify", "merge_method", "commit_title", "commit_message", "event", "comments", "line", "side",
        "start_line", "end_line", "max_lines", "start_offset", "ranges", "evidence_id", "start_side", "commit_id", "in_reply_to", "thread_id", "review_id", "comment_id", "run_id", "job_id", "workflow_id",
        "check_run_id", "artifact_id", "release_id", "tag_name", "query", "q", "sort", "order", "direction", "page", "per_page", "limit",
        "cursor", "view_cursor", "index_cursor", "search_id", "view_id", "operation", "operation_id", "model", "schema", "schema_version",
        "url", "download_url", "accept", "since", "until", "filter", "status", "conclusion", "inputs", "files", "encoding", "recursive"
    )
    val dispatchPolicyFields = setOf("dispatch_policy_sections", "dispatch_policy_stage", "dispatch_policy_resume_required")
    private val policySections = setOf("PROFILE", "BUDGET", "SPEND", "TOOLS", "MCP", "PERMISSIONS", "ASSIST", "DELEGATION", "RESEARCH", "GATEWAY", "GITHUB", "REASONING", "ENGINEERING")
    private val policyStages = setOf("provider_admission", "tool_precheck", "tool_dispatch", "tool_execution", "delivery", "verification")

    /** Serialized production policy/profile keys only; never scope IDs or settings values. */
    val policyFieldNames = setOf(
        "githubProfileOverride.webSearch", "githubProfileOverride.githubTools", "githubProfileOverride.engineeringMode", "githubProfileOverride.maximumContext", "githubProfileOverride.engineeringMemory", "githubProfileOverride.allowCloudEngineeringMemory", "githubProfileOverride.workflowPreset", "githubProfileOverride.quickWorkflows", "githubProfileOverride.engineeringGatewayProfileUid", "githubProfileOverride.executionBatchMode", "githubProfileOverride.sampling",
        "assist", "assistPolicy", "budget", "conversationDelegation", "conversationGateway",
        "conversationResearch", "delegation", "engineering", "githubProfileOverride", "githubProfileSelection",
        "llamaReasoning", "location", "plugins", "profile", "profile.accelerator",
        "profile.apiUrl", "profile.batchApiUrl", "profile.batchMode", "profile.compatibleType", "profile.dangerousContentSafetyThreshold",
        "profile.disableAllTools", "profile.disableLocalTools", "profile.disableRemoteTools", "profile.enabled", "profile.harassmentSafetyThreshold",
        "profile.hateSpeechSafetyThreshold", "profile.id", "profile.isFavorite", "profile.labels", "profile.maxTokens",
        "profile.maxToolCalls", "profile.model", "profile.name", "profile.ollamaOptions", "profile.openRouterRouting",
        "profile.providerConnectionUid", "profile.reasoning", "profile.removed", "profile.secretRef", "profile.sexuallyExplicitSafetyThreshold",
        "profile.stream", "profile.systemPrompt", "profile.temperature", "profile.timeout", "profile.token",
        "profile.topK", "profile.topP", "profile.uid", "providerPermissions", "reasoning", "remoteMcp",
        "researchEnabled", "researchReviewer", "spend", "tool_inventory", "tools"
    )
    private const val MAX_POLICY_ENTRIES = 256
    private const val MAX_POLICY_NAMES = 8
    private val policyCounts = setOf("changed_policy_field_count", "changed_policy_unknown_count", "changed_policy_malformed_count", "changed_policy_omitted_count")
    val policyDiagnostics = policyCounts + setOf("changed_policy_fields", "changed_policy_observation")
    val runEvidenceFields = setOf(
        "pending_tool_count", "pending_tool_identity_hashes", "pending_tool_receipt_state", "local_readiness_failure",
        "semantic_read_rules", "dependency_admission_reasons", "fixture_approval_state", "branch_protection_observation", "branch_rules_observation",
        "provider_wait_checkpoint_hash", "provider_wait_claim_hash", "provider_wait_terminal_status", "provider_route_hash", "crash_surface", "crash_boundary"
    )
    private val policyObservation = setOf("OBSERVED", "UNOBSERVED", "INVALID_RESPONSE", "ACCESS_DENIED", "NOT_FOUND_OR_INACCESSIBLE", "RATE_LIMITED", "UNAVAILABLE", "HTTP_FAILURE")
    private val common = setOf("incident_phase", "incident_capture_site", "incident_phase_provenance", "causal_id_hash", "causal_provenance", "causal_failure_code", "incident_evidence_status", "incident_origin", "recovery_observation", "recovery_attempt_count", "retry_delay_ms", "retry_delay_source", "exception_type_hashes", "provider_wait_state", "provider_wait_persisted", "recovery_authority") + dispatchPolicyFields + policyDiagnostics
    val toolFailureFields = setOf("url_reader_dns_state", "tool_execution_observation", "tool_budget_kind", "tool_budget_phase", "tool_result_bytes_used", "tool_result_bytes_limit", "tool_budget_calls_used", "tool_budget_calls_limit", "tool_permission_scope")
    val gatewayDeliveryFields = setOf("gateway_terminal_status", "gateway_result_quality", "reasoning_probe_observation", "gateway_upstream_status", "gateway_dependency")
    val providerExecutionFields = setOf("tls_failure_kind", "tls_response_started", "provider_local_budget_block", "provider_tool_rejection")
    val combinedDeliveryFields = setOf("combined_requirement_gap", "combined_issue_count")
    val acceptanceBlockerFields = setOf("fixture_approval_blocked_count", "owned_fixture_blocked_count", "pending_local_fragments", "pending_remote_continuations")
    private val toolFields = setOf(
        "native_tool_action", "tool_contract_version", "action_schema_generation_hash", "validation_field_names", "validation_path_hashes", "unknown_field_hashes", "unknown_field_count",
        "argument_field_names", "argument_field_types", "resolved_argument_field_names", "resolved_argument_field_types", "resolved_argument_shape_hash",
        "saved_view_owner_operation", "saved_view_connection_hash", "saved_view_scope_hash", "saved_view_source_hash", "saved_view_cursor_hash", "saved_view_cursor_issued", "saved_view_expired", "saved_view_restore_state", "saved_view_restore_error_hash",
        "github_error_resources", "github_error_fields", "github_error_codes", "server_request_id_hash", "argument_repair_signature_hash", "argument_repair_attempt", "argument_repair_exhausted",
        "mcp_connection_hash", "mcp_failure_phase", "mcp_failure_category", "mcp_connection_state", "mcp_readiness_state", "mcp_connection_health", "mcp_health_observation", "mcp_health_age_ms",
        "native_dependency_state", "dependency_call_id_hashes", "dependency_count", "dependency_failed_count", "semantic_read_rules", "dependency_admission_reasons", "search_cursor_state", "search_id_hash", "search_cursor_hash"
    )
    private val acceptanceFields = setOf(
        "task_kind", "acceptance_evaluator_branch", "acceptance_mismatch", "eligible_receipt_count", "excluded_receipt_count", "receipt_count_scope", "receipt_exclusion_reasons",
        "expected_operation", "expected_action", "evidence_ref_hashes", "required_capability_count", "verified_capability_count", "blocked_capability_count", "untested_capability_count"
    )
    private val gatewayFields = setOf("command_receipt_observation", "command_id_hash", "gateway_source_commit_hash", "gateway_package_digest_hash")
    private val providerFields = setOf(
        "provider_rejection_category", "provider_error_type_hash", "provider_parameter", "provider_parameter_path_hash", "payload_contract",
        "privacy_catalog_entries", "privacy_model_endpoint_count", "privacy_compatible_endpoint_count", "privacy_allowlist_excluded_count",
        "privacy_denylist_excluded_count", "privacy_fallback_excluded_count", "privacy_metadata_age_ms", "privacy_blockers"
    ) + gatewayDeliveryFields + providerExecutionFields
    private val voiceFields = setOf("run_id_hash", "invocation_id_hash", "voice_reply_failed", "voice_reply_terminal_status", "voice_reply_failure_evidence", "voice_chat_failure_code", "voice_chat_failure_code_hash", "voice_chat_failure_phase")
    private val hashes = setOf("causal_id_hash", "action_schema_generation_hash", "resolved_argument_shape_hash", "saved_view_connection_hash", "saved_view_scope_hash", "saved_view_source_hash", "saved_view_cursor_hash", "saved_view_restore_error_hash", "server_request_id_hash", "command_id_hash", "gateway_source_commit_hash", "gateway_package_digest_hash", "argument_repair_signature_hash", "provider_error_type_hash", "provider_parameter_path_hash", "voice_chat_failure_code_hash", "mcp_connection_hash", "search_id_hash", "search_cursor_hash", "provider_wait_checkpoint_hash", "provider_wait_claim_hash", "provider_route_hash")
    private val hashLists = setOf("validation_path_hashes", "unknown_field_hashes", "evidence_ref_hashes", "exception_type_hashes", "dependency_call_id_hashes", "pending_tool_identity_hashes")
    private val flags = setOf("tls_response_started", "provider_local_budget_block", "saved_view_cursor_issued", "saved_view_expired", "provider_wait_persisted", "argument_repair_exhausted", "voice_reply_failed", "dispatch_policy_resume_required")
    private val counts = setOf("recovery_attempt_count", "unknown_field_count", "eligible_receipt_count", "excluded_receipt_count", "required_capability_count", "verified_capability_count", "blocked_capability_count", "untested_capability_count", "privacy_catalog_entries", "privacy_model_endpoint_count", "privacy_compatible_endpoint_count", "privacy_allowlist_excluded_count", "privacy_denylist_excluded_count", "privacy_fallback_excluded_count", "dependency_count", "dependency_failed_count", "pending_tool_count") + policyCounts
    private val executionCounts = setOf("tool_result_bytes_used", "tool_result_bytes_limit", "tool_budget_calls_used", "tool_budget_calls_limit", "combined_issue_count") + acceptanceBlockerFields
    private val types = setOf("STRING", "NUMBER", "BOOLEAN", "OBJECT", "ARRAY", "NULL")
    private val mcpHealth = setOf("DNS_FAILED", "DNS_RECOVERY", "ROUTE_UNAVAILABLE", "CONNECTION_REFUSED", "AUTHENTICATION_FAILED", "TLS_FAILED", "CONNECT_TIMEOUT", "HTTP_ERROR", "MCP_HANDSHAKE_FAILED", "STREAM_INTERRUPTED", "STREAM_IDLE_TIMEOUT", "REQUEST_TIMEOUT", "HEALTHY", "UNKNOWN")
    private val enums = mapOf(
        "url_reader_dns_state" to setOf("RECOVERY_COOLDOWN", "LOOKUP_FAILED"),
        "fixture_decision_mode" to setOf("AUTO_APPROVE", "SUGGESTED", "ASK", "UNKNOWN"),
        "crash_surface" to setOf("CHAT", "DEBUG_LOGS", "GITHUB_WORKSPACE"),
        "crash_boundary" to setOf("ANDROID_VIEW_DRAW"),
        "pending_tool_receipt_state" to setOf("CHECKPOINT_STATE_INCONSISTENT", "TERMINAL_RECEIPTS_MISSING", "SOME_TERMINAL_RECEIPTS_MISSING", "OUTCOMES_UNRESOLVED"),
        "local_readiness_failure" to setOf("INSUFFICIENT_MEMORY", "PENDING_CLEANUP", "VERIFICATION_REQUIRED", "UNSUPPORTED_BACKEND", "INCOMPATIBLE_MODEL", "MISSING_RUNTIME", "INITIALIZATION_TIMEOUT", "UNKNOWN"),
        "fixture_approval_state" to setOf("READY", "CONDITIONAL", "UNKNOWN"),
        "branch_protection_observation" to policyObservation,
        "branch_rules_observation" to policyObservation,
        "provider_wait_terminal_status" to setOf("COMPLETED", "PARTIAL", "FAILED", "BLOCKED", "INTERRUPTED", "CANCELED", "EVIDENCE_MISSING", "WAITING_FOR_APPROVAL"),
        "changed_policy_observation" to setOf("OBSERVED", "PARTIAL", "MALFORMED"),
        "incident_phase" to phases,
        "dispatch_policy_stage" to policyStages,
        "incident_capture_site" to ExceptionCaptureSite.entries.mapTo(mutableSetOf()) { it.name },
        "incident_phase_provenance" to setOf("CAPTURE_BOUNDARY_ONLY"),
        "causal_provenance" to setOf("TOOL_CALL", "MODEL_INVOCATION", "GATEWAY_COMMAND", "EXPLICIT_CAUSE", "UNAVAILABLE"),
        "causal_failure_code" to IssueReportCodes.allowed,
        "incident_evidence_status" to setOf("TYPED_EVIDENCE", "CAUSAL_EVIDENCE_UNAVAILABLE"),
        "incident_origin" to setOf("PROVIDER", "TOOL", "GATEWAY", "ACCEPTANCE", "LOCAL_RECOVERY", "APPLICATION", "UNKNOWN"),
        "recovery_observation" to setOf("WAITING_RETRY", "RETRY_SCHEDULED", "RETRY_ATTEMPTED", "RECOVERED", "RECONCILIATION_REQUIRED", "TERMINAL", "UNOBSERVED"),
        "provider_wait_state" to setOf("WAITING_PROVIDER_LIMIT", "CHECKPOINT_UNAVAILABLE", "AUTHORITY_CHANGED", "PERSIST_FAILED", "NOT_SCHEDULED", "RESUMED"),
        "retry_delay_source" to setOf("PROVIDER_HEADER", "PROVIDER_BODY", "LOCAL_BACKOFF", "UNKNOWN"),
        "recovery_authority" to setOf("VALID", "CHANGED", "UNVERIFIED"),
        "receipt_count_scope" to setOf("LINKED_CRITERION_EVIDENCE"),
        "provider_rejection_category" to setOf("BILLING_UNAVAILABLE", "INVALID_REQUEST", "AUTHENTICATION", "PERMISSION", "RATE_LIMIT", "NOT_FOUND", "GONE", "UNAVAILABLE", "PAYLOAD_SIZE", "MODEL_UNAVAILABLE", "UNKNOWN"),
        "provider_parameter" to setOf("model", "messages", "max_tokens", "max_completion_tokens", "temperature", "top_p", "stop", "tools", "tool_choice", "stream", "response_format", "reasoning", "thinking", "metadata", "system"),
        "payload_contract" to setOf("CHAT_COMPLETIONS", "ANTHROPIC_MESSAGES", "GOOGLE_CONTENT", "GATEWAY_GENERATION"),
        "voice_reply_terminal_status" to setOf("COMPLETED", "FAILED", "CANCELED", "INTERRUPTED", "PARTIAL", "EVIDENCE_MISSING", "BLOCKED", "WAITING_FOR_APPROVAL"),
        "voice_reply_failure_evidence" to setOf("TYPED_INVOCATION_REFERENCE", "CLASSIFICATION_ONLY", "MISSING_FAILURE_RECORD"),
        "voice_chat_failure_code" to IssueReportCodes.allowed,
        "voice_chat_failure_phase" to phases - "unknown",
        "native_tool_action" to actions,
        "saved_view_owner_operation" to IssueReportContext.operations,
        "saved_view_restore_state" to setOf("NOT_FOUND", "LOAD_FAILED", "IDENTITY_REJECTED", "RESTORED", "BODY_MISSING", "CURSOR_INDEX_MISSING", "INVALID_VIEW_ID"),
        "mcp_failure_phase" to setOf("configuration", "catalog", "discovery", "initialization", "execution", "connection", "dns", "tls", "oauth_refresh", "local_validation"),
        "mcp_failure_category" to mcpHealth - "HEALTHY",
        "mcp_connection_health" to mcpHealth,
        "mcp_connection_state" to setOf("CONNECTED", "DEGRADED", "UNREACHABLE", "AUTHENTICATION_REQUIRED", "RECOVERING"),
        "mcp_readiness_state" to setOf("NOT_DISCOVERED", "DISCOVERING", "READY", "STALE"),
        "mcp_health_observation" to setOf("RETAINED_SNAPSHOT", "UNAVAILABLE"),
        "native_dependency_state" to setOf("DEFERRED", "FAILED"),
        "tool_execution_observation" to setOf("REMOTE_READ_SUCCEEDED"),
        "tool_budget_kind" to setOf("CALLS", "RESULT_BYTES", "BOTH"),
        "tool_budget_phase" to setOf("BEFORE_DISPATCH", "AFTER_DISPATCH"),
        "tool_permission_scope" to setOf("ANDROID_LOCATION", "GITHUB_LOCAL_GRANT"),
        "tls_failure_kind" to setOf("CERTIFICATE", "PROTOCOL", "HANDSHAKE", "OTHER"),
        "provider_tool_rejection" to setOf("TOOL_USE_FAILED"),
        "reasoning_probe_observation" to setOf("GATEWAY_OBSERVATION_AVAILABLE", "DEADLINE_WITHOUT_GATEWAY_OBSERVATION", "NO_GATEWAY_OBSERVATION"),
        "gateway_terminal_status" to setOf("completed", "failed", "cancelled", "canceled", "interrupted"),
        "gateway_result_quality" to setOf("complete", "partial"),
        "combined_requirement_gap" to setOf("CURRENT_ARTICLE", "LENGTH", "TOPIC", "OTHER"),
        "search_cursor_state" to setOf("RETAINED_SEARCH_MISSING", "PAGE_BOUNDARY_UNOBSERVED"),
        "expected_operation" to IssueReportContext.operations,
        "expected_action" to actions,
        "task_kind" to setOf("REVIEW", "IMPLEMENTATION", "INVESTIGATION", "INTEGRATION_TEST", "CI_OBSERVATION", "STATUS_HEALTH", "STATUS_SNAPSHOT", "READ_ONLY", "GENERAL", "UNCLASSIFIED"),
        "acceptance_evaluator_branch" to setOf("REQUIREMENT_MATCH", "CAPABILITY_COVERAGE", "CI_REVISION_BINDING", "REVIEW_COMPARISON", "FIXTURE_LIFECYCLE", "EXECUTION_LEDGER", "STATUS_OBSERVATION", "STATUS_UNCERTAINTY_REPORT", "INFORMATIONAL_CLAIMS", "OPERATION_ACCEPTANCE", "REVISION_TEST_ACCEPTANCE", "ACCEPTANCE_MAPPING_REQUIRED", "UNCLASSIFIED"),
        "acceptance_mismatch" to setOf("NO_ELIGIBLE_RECEIPT", "OPERATION_MISMATCH", "ACTION_MISMATCH", "REVISION_MISMATCH", "SCOPE_MISMATCH", "STATUS_NOT_PASS", "UNRESOLVED_OUTCOME", "EVIDENCE_UNAVAILABLE", "MATCHING_OBSERVATION_INCOMPLETE", "UNSUPPORTED_CRITERION", "NO_MATCHING_CLAIMS", "LINKED_RECEIPT_INELIGIBLE", "COMPARISON_REQUIRED", "ACCEPTANCE_EVIDENCE_MISSING", "UNSUPPORTED_CLAIMS", "NO_EXECUTION_PROOF", "UNRESOLVED_USER_DECISION", "ACCEPTANCE_MAPPING_REQUIRED"),
        "command_receipt_observation" to setOf("PRESENT", "UNAVAILABLE_404", "CONTRACT_UNSUPPORTED", "READ_INTERRUPTED", "UNOBSERVED")
    )
    private val enumLists = mapOf(
        "dispatch_policy_sections" to policySections,
        "changed_policy_fields" to policyFieldNames,
        "semantic_read_rules" to dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments.ReadRule.entries.mapTo(linkedSetOf()) { it.name },
        "dependency_admission_reasons" to dev.chungjungsoo.gptmobile.data.agent.ToolDependencyDiagnostics.Admission.entries.mapTo(linkedSetOf()) { it.name },
        "validation_field_names" to fieldNames,
        "argument_field_names" to fieldNames,
        "resolved_argument_field_names" to fieldNames,
        "argument_field_types" to types,
        "resolved_argument_field_types" to types,
        "github_error_resources" to setOf("PullRequest", "Issue", "Review", "Repository", "Commit", "Reference", "Search", "Label", "Milestone", "Release"),
        "github_error_fields" to fieldNames,
        "github_error_codes" to setOf("missing", "missing_field", "invalid", "already_exists", "unprocessable", "custom"),
        "privacy_blockers" to setOf("MODEL_NOT_IN_ZDR_CATALOG", "PROVIDER_ALLOWLIST", "PROVIDER_DENYLIST", "FALLBACK_ORDER"),
        "receipt_exclusion_reasons" to setOf("WRONG_OPERATION", "WRONG_ACTION", "WRONG_REVISION", "WRONG_SCOPE", "PRECHECK_ONLY", "NOT_SUCCESSFUL", "UNKNOWN_OUTCOME", "STALE", "MISSING_IDENTITY", "UNVERIFIED")
    )
    val coreFields = listOf("incident_phase", "causal_id_hash", "causal_provenance", "incident_evidence_status")
    val priorityFields = coreFields.toSet() + setOf("run_id_hash", "causal_failure_code", "incident_origin", "incident_capture_site", "incident_phase_provenance", "exception_type_hashes") + policyDiagnostics + runEvidenceFields

    enum class ExceptionCaptureSite { CHAT_REPOSITORY_TERMINAL_CATCH, CHAT_REPOSITORY_PREFLIGHT, OPENAI_ADAPTER_STREAM_CATCH }

    /** Captured at the actual catch. A boundary identifies where observed, not where dispatched. */
    fun exceptionFields(error: Throwable, site: ExceptionCaptureSite): JsonObject = buildJsonObject {
        put("incident_capture_site", site.name)
        put("incident_phase_provenance", "CAPTURE_BOUNDARY_ONLY")
        put("cause_types", JsonArray(dev.chungjungsoo.gptmobile.data.network.sanitizedCauseTypes(error).map(::JsonPrimitive)))
        IssueReportContext.exceptionFingerprint(error)?.let { put("exception_fingerprint", it) }
        val (type, frames) = IssueReport.exceptionMetadata(error)
        type?.let { put("exception_report_type", it) }
        val applicationFrames = frames.filter { it.startsWith("dev.chungjungsoo.gptmobile.") || it.startsWith("dev.melo.gptmobile.") }
        if (applicationFrames.isNotEmpty()) put("exception_application_frames", JsonArray(applicationFrames.map(::JsonPrimitive)))
    }

    fun fieldsFor(area: String): Set<String> = common + when (area) {
        "TOOL" -> toolFields + toolFailureFields
        "INCIDENT" -> combinedDeliveryFields + setOf("pending_tool_count", "pending_tool_identity_hashes", "pending_tool_receipt_state", "local_readiness_failure")
        "ACCEPTANCE" -> acceptanceFields + acceptanceBlockerFields + setOf("fixture_approval_state", "fixture_decision_mode", "branch_protection_observation", "branch_rules_observation")
        "GATEWAY" -> gatewayFields
        "PROVIDER" -> providerFields + setOf("provider_wait_checkpoint_hash", "provider_wait_claim_hash", "provider_wait_terminal_status", "provider_route_hash")
        "APP" -> setOf("crash_surface", "crash_boundary")
        "VOICE" -> voiceFields
        else -> emptySet()
    }

    fun validValue(key: String, value: JsonElement): JsonElement? {
        if (key in hashLists || key in enumLists) {
            val items = value as? JsonArray ?: return null
            if (items.size !in 1..if (key == "privacy_blockers") 4 else 8) return null
            if (items.any { item -> text(item)?.let { if (key in hashLists) it.matches(HASH) else it in enumLists.getValue(key) } != true }) return null
            return JsonArray(items.toList())
        }
        val primitive = value as? JsonPrimitive ?: return null
        if (key in hashes) return text(primitive)?.takeIf(HASH::matches)?.let(::JsonPrimitive)
        enums[key]?.let { choices -> return text(primitive)?.takeIf { it in choices }?.let(::JsonPrimitive) }
        if (key in flags) return primitive.takeUnless { it.isString }?.booleanOrNull?.let(::JsonPrimitive)
        val number = primitive.takeUnless { it.isString }?.longOrNull ?: return null
        val range = when (key) {
            "retry_delay_ms", "privacy_metadata_age_ms", "mcp_health_age_ms" -> 0L..86_400_000L
            "tool_contract_version" -> 1L..1000L
            "argument_repair_attempt" -> 1L..3L
            in counts, in executionCounts -> 0L..16_000_000L
            else -> return null
        }
        return number.takeIf { it in range }?.let(::JsonPrimitive)
    }

    fun aligned(values: Map<String, JsonElement>): Boolean = listOf("argument", "resolved_argument").all { prefix ->
        val names = values["${prefix}_field_names"] as? JsonArray
        val types = values["${prefix}_field_types"] as? JsonArray
        names == null && types == null || names != null && types != null && names.size == types.size
    } &&
        policyAligned(values) &&
        executionCountsAligned(values, "tool_result_bytes_used", "tool_result_bytes_limit") &&
        executionCountsAligned(values, "tool_budget_calls_used", "tool_budget_calls_limit")

    private fun executionCountsAligned(values: Map<String, JsonElement>, used: String, limit: String): Boolean {
        val observed = (values[used] as? JsonPrimitive)?.longOrNull ?: return true
        val maximum = (values[limit] as? JsonPrimitive)?.longOrNull ?: return true
        return observed <= maximum
    }

    private fun policyAligned(values: Map<String, JsonElement>): Boolean {
        if (policyDiagnostics.none { it in values }) return true
        fun count(key: String) = (values[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        val status = text(values["changed_policy_observation"])
        val total = count("changed_policy_field_count")
        if (status == "MALFORMED") {
            return (total == null || total > MAX_POLICY_ENTRIES) &&
                (policyDiagnostics - setOf("changed_policy_observation", "changed_policy_field_count")).none { it in values }
        }
        if (status !in setOf("OBSERVED", "PARTIAL") || total == null || total !in 0L..MAX_POLICY_ENTRIES.toLong()) return false
        val unknown = count("changed_policy_unknown_count") ?: return false
        val malformed = count("changed_policy_malformed_count") ?: return false
        val omitted = count("changed_policy_omitted_count") ?: return false
        val names = (values["changed_policy_fields"] as? JsonArray).orEmpty()
        if (names.distinct().size != names.size || total < names.size + unknown + malformed + omitted) return false
        if (names.isEmpty() && total > unknown + malformed || omitted > 0 && names.size != MAX_POLICY_NAMES || names.size + omitted > policyFieldNames.size) return false
        return if (status == "OBSERVED") unknown == 0L && malformed == 0L && omitted == 0L else unknown + malformed + omitted > 0L
    }

    /** Counts describe supplied entries; omitted counts only distinct known names beyond the retained sample. */
    private fun policyFields(raw: Any?): Map<String, Any?> = buildMap {
        val entries: List<*> = when (raw) {
            is JsonArray -> raw
            is List<*> -> raw
            else -> {
                put("changed_policy_observation", "MALFORMED")
                return@buildMap
            }
        }
        if (entries.size > MAX_POLICY_ENTRIES) {
            put("changed_policy_observation", "MALFORMED")
            entries.size.takeIf { it <= 16_000_000 }?.let { put("changed_policy_field_count", it) }
            return@buildMap
        }
        val known = mutableSetOf<String>()
        var unknown = 0
        var malformed = 0
        for (entry in entries) {
            val name = when (entry) {
                is String -> entry
                is JsonPrimitive -> entry.takeIf { it.isString }?.contentOrNull
                else -> null
            }
            when {
                name == null -> malformed++
                name.length <= 128 && name in policyFieldNames -> known.add(name)
                else -> unknown++
            }
        }
        val omitted = (known.size - MAX_POLICY_NAMES).coerceAtLeast(0)
        if (known.isNotEmpty()) put("changed_policy_fields", known.sorted().take(MAX_POLICY_NAMES))
        put("changed_policy_field_count", entries.size)
        put("changed_policy_unknown_count", unknown)
        put("changed_policy_malformed_count", malformed)
        put("changed_policy_omitted_count", omitted)
        put("changed_policy_observation", if (unknown + malformed + omitted == 0) "OBSERVED" else "PARTIAL")
    }

    /** No latest-run lookup: this projection uses only the failure's own typed fields. */
    fun fields(feature: ReportFeature, source: String, code: String, fields: Map<String, Any?>): Map<String, Any?> = buildMap {
        putAll(fields)
        // Only the original structured list supplies policy-change evidence. Never publish arbitrary names.
        policyDiagnostics.forEach(::remove)
        if (fields.containsKey("changed_policy_fields")) putAll(policyFields(fields["changed_policy_fields"]))
        val area = text(fields["diagnostic_area"])?.takeIf { it in IssueReportDiagnostics.areas } ?: when (feature) {
            ReportFeature.TOOLS -> "TOOL"
            ReportFeature.GATEWAY -> "GATEWAY"
            ReportFeature.APP -> "APP"
            else -> "INCIDENT"
        }
        put("diagnostic_area", area)
        val phase = listOf("failed_stage", "failedStage", "failurePhase", "phase", "stage").firstNotNullOfOrNull { text(fields[it]) }
            ?.lowercase(Locale.ROOT)?.takeIf { it in phases } ?: "unknown"
        put("incident_phase", phase)
        val policyCode = text(fields["authority_invalidation_code"]) ?: code
        if (policyCode in setOf("DISPATCH_POLICY_CHANGED", "POLICY_STALE")) {
            val changes = fields["changed_policy_fields"]
            val names = when (changes) {
                is JsonArray -> changes.asSequence()
                is List<*> -> changes.asSequence()
                else -> emptySequence()
            }
            val sections = names.take(32).mapNotNull { policySection(text(it)) }.distinct().take(8).toList()
            if (sections.isNotEmpty()) put("dispatch_policy_sections", sections)
            (text(fields["authority_failed_stage"]) ?: text(fields["blocked_stage"]) ?: phase)
                .takeIf { it in policyStages }?.let { put("dispatch_policy_stage", it) }
            when (val resume = fields["resume_required"]) {
                is Boolean -> put("dispatch_policy_resume_required", resume)
                is JsonPrimitive -> resume.takeUnless { it.isString }?.booleanOrNull?.let { put("dispatch_policy_resume_required", it) }
            }
        }
        val explicit = text(fields["causal_id"])
        val toolCall = if (source == "TOOL_RESULT") text(fields["call_id"]) ?: text(fields["callId"]) else null
        val invocation = if (phase in setOf("model_invocation", "provider_generation", "response_stream")) text(fields["invocation_id"]) ?: text(fields["invocationId"]) else null
        val provenance = when {
            explicit != null -> text(fields["causal_provenance"])?.takeIf { it in enums.getValue("causal_provenance") && it != "UNAVAILABLE" } ?: "EXPLICIT_CAUSE"
            toolCall != null -> "TOOL_CALL"
            invocation != null -> "MODEL_INVOCATION"
            else -> "UNAVAILABLE"
        }
        val causalHash = IssueReportContext.identifierHash("incident-cause:$provenance", explicit ?: toolCall ?: invocation)
        if (causalHash != null) put("causal_id_hash", causalHash) else remove("causal_id_hash")
        put("causal_provenance", if (causalHash == null) "UNAVAILABLE" else provenance)
        put("causal_failure_code", IssueReportCodes.known(text(fields["causal_failure_code"])) ?: code)
        put("incident_evidence_status", if (phase != "unknown" && causalHash != null) "TYPED_EVIDENCE" else "CAUSAL_EVIDENCE_UNAVAILABLE")
        val origin = text(fields["failure_origin"])?.uppercase(Locale.ROOT)?.takeIf { it in enums.getValue("incident_origin") }
            ?: when (source) {
                "TOOL_RESULT" -> "TOOL"
                else -> when (area) {
                    "PROVIDER", "ACCEPTANCE", "GATEWAY" -> area
                    else -> "UNKNOWN"
                }
            }
        put("incident_origin", origin)
        val recovery = text(fields["recovery_observation"])?.takeIf { it in enums.getValue("recovery_observation") } ?: "UNOBSERVED"
        put("recovery_observation", recovery)
        val causes = when (val value = fields["cause_types"]) {
            is JsonArray -> value.mapNotNull(::text)
            is List<*> -> value.mapNotNull(::text)
            else -> emptyList()
        }.take(4).mapNotNull { IssueReportContext.identifierHash("exception-type", it) }
        if (causes.isNotEmpty()) put("exception_type_hashes", causes)
    }

    /** A causal identity is meaningful only in its run. Missing evidence never joins two layers. */
    fun identity(context: IssueReportContext, code: String): CausalIdentity? {
        val values = context.diagnostics?.takeIf { it.schemaVersion == 3 }?.values ?: return null
        val run = context.runIdHash ?: return null
        val cause = text(values["causal_id_hash"]) ?: return null
        val provenance = text(values["causal_provenance"])?.takeUnless { it == "UNAVAILABLE" } ?: return null
        return CausalIdentity(run, cause, provenance, IssueReportCodes.known(text(values["causal_failure_code"])) ?: code)
    }

    data class CausalIdentity(val runHash: String, val causeHash: String, val provenance: String, val code: String)

    private fun policySection(field: String?): String? = when (field?.substringBefore('.')) {
        "profile" -> "PROFILE"
        "budget" -> "BUDGET"
        "spend" -> "SPEND"
        "tools", "plugins", "tool_inventory" -> "TOOLS"
        "remoteMcp" -> "MCP"
        "location", "providerPermissions" -> "PERMISSIONS"
        "assist", "assistPolicy" -> "ASSIST"
        "delegation", "conversationDelegation" -> "DELEGATION"
        "conversationResearch", "researchEnabled", "researchReviewer" -> "RESEARCH"
        "conversationGateway" -> "GATEWAY"
        "githubProfileSelection", "githubProfileOverride" -> "GITHUB"
        "reasoning", "llamaReasoning" -> "REASONING"
        "engineering" -> "ENGINEERING"
        else -> null
    }

    private fun text(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.length <= 256 }
        is JsonPrimitive -> value.takeIf { it.isString }?.contentOrNull?.takeIf { it.length <= 256 }
        else -> null
    }

    private val HASH = Regex("[a-f0-9]{64}")
}
