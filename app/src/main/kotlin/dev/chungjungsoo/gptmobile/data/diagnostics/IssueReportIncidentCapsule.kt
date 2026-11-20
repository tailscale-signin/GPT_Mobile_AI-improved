package dev.chungjungsoo.gptmobile.data.diagnostics

import android.database.sqlite.SQLiteConstraintException
import dev.chungjungsoo.gptmobile.data.database.ToolEventPersistenceFailure
import dev.chungjungsoo.gptmobile.data.github.GitHubAcceptancePublicationEvidence
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
        "agent_merge_status", "agent_merge_enable", "agent_merge_update", "agent_merge_disable",
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
    val failedEventFields = setOf("failed_event_code_present", "failed_event_invocation_present", "failed_event_details_present")
    private val common = failedEventFields + setOf("incident_phase", "incident_capture_site", "incident_phase_provenance", "causal_id_hash", "causal_provenance", "causal_failure_code", "incident_evidence_status", "incident_origin", "recovery_observation", "recovery_attempt_count", "retry_delay_ms", "retry_delay_source", "exception_type_hashes", "sqlite_constraint_kind", "provider_wait_state", "provider_wait_persisted", "recovery_authority") + dispatchPolicyFields + policyDiagnostics
    val toolFailureFields = setOf("url_reader_dns_state", "url_reader_availability", "tool_execution_observation", "tool_budget_kind", "tool_budget_phase", "tool_result_bytes_used", "tool_result_bytes_limit", "tool_budget_calls_used", "tool_budget_calls_limit", "tool_permission_scope")
    val gatewayDeliveryFields = setOf("gateway_terminal_status", "gateway_result_quality", "reasoning_probe_observation", "gateway_upstream_status", "gateway_dependency")
    val providerExecutionFields = setOf("claim_extraction_validation_stage", "claim_extraction_schema_hash", "tls_failure_kind", "tls_response_started", "provider_local_budget_block", "provider_tool_rejection", "tool_protocol_frame_kind", "tool_protocol_recovery_outcome", "claim_extraction_failure_kind", "claim_extraction_response_characters", "claim_extraction_max_claims", "claim_extraction_completed")
    val combinedRepairStops = setOf("CORRECTION_CAPACITY_UNAVAILABLE", "LEGACY_REVISION_REQUIRES_NEW_OWNER", "LEGACY_REPAIR_ALLOWANCE_REACHED", "NO_PROGRESS_AFTER_REQUIRED_REVISIONS", "NO_PROGRESS_AFTER_REQUIRED_REVISION", "NEWER_REVISION_OR_UNAVAILABLE_REPLACEMENT_OWNER", "REPAIR_REJECTED", "NO_REMAINING_REQUIRED_CORRECTION", "REPAIR_PREPARATION_FAILED", "REPLACEMENT_MADE_NO_TEXT_PROGRESS", "REPLACEMENT_OWNER_COMPARE_AND_SET_REJECTED")
    val combinedDeliveryFields = setOf("combined_requirement_gap", "combined_issue_count", "combined_failed_check_count", "combined_unverified_check_count", "combined_repair_rounds", "combined_repair_stop")
    private val handoffReconciliationCounts = setOf("handoff_replay_receipt_count", "handoff_replay_matched_count", "handoff_durable_receipt_count", "handoff_durable_matched_count", "handoff_retained_matched_count")
    private val handoffReconciliationHashes = setOf("handoff_tool_binding_hash", "handoff_saved_tool_binding_hash", "handoff_evidence_digest_hash", "handoff_journal_digest_hash")
    val handoffReconciliationFields = handoffReconciliationCounts + handoffReconciliationHashes + setOf("handoff_checkpoint_state", "handoff_durable_lookup")
    private val chatDeliveryCounts = setOf("chat_delivery_content_bytes", "chat_delivery_thinking_bytes", "chat_delivery_success_events", "chat_delivery_tool_events", "chat_delivery_progress_events", "chat_delivery_github_rounds")
    val chatDeliveryFields = chatDeliveryCounts + setOf("chat_delivery_terminal", "chat_delivery_answer_state", "chat_delivery_counts_capped")
    val claimFreshnessFields = setOf("acceptance_claim_freshness_predicates")
    val acceptanceBlockerFields = setOf("fixture_approval_blocked_count", "owned_fixture_blocked_count", "pending_local_fragments", "pending_remote_continuations")
    const val REPEAT_ADMISSION_FEATURE = "TOOL_REPEAT_ADMISSION_EVIDENCE_V1"
    private val repeatAdmissionCounts = setOf("dependency_repeat_prior_admissions", "dependency_repeat_batch_admissions", "dependency_repeat_used", "dependency_repeat_limit")
    val repeatAdmissionFields = repeatAdmissionCounts + "dependency_repeat_scope"
    const val REPEAT_HISTORY_FEATURE = "TOOL_REPEAT_HISTORY_EVIDENCE_V1"
    private val repeatHistoryHashFields = setOf("dependency_repeat_prior_call_id_hashes", "dependency_repeat_batch_call_id_hashes")
    val repeatHistoryListFields = repeatHistoryHashFields + "dependency_repeat_prior_outcomes"
    val repeatHistoryFields = repeatHistoryListFields + setOf("dependency_repeat_prior_coverage", "dependency_repeat_batch_coverage")
    private val repeatHistoryOutcomes = setOf("UNOBSERVED", "DISPATCHED_SUCCESS", "DISPATCHED_FAILURE", "NOT_DISPATCHED", "SHARED_RESULT", "UNKNOWN_OUTCOME")
    const val GITHUB_CI_SEMANTIC_FEATURE = "GITHUB_CI_SEMANTIC_EVIDENCE_V1"
    const val GITHUB_SEARCH_VALIDATION_FEATURE = "GITHUB_SEARCH_VALIDATION_EVIDENCE_V1"
    const val TOOL_INVENTORY_PAGINATION_FEATURE = "TOOL_INVENTORY_PAGINATION_EVIDENCE_V1"
    val inventoryPaginationFields = setOf("inventory_pagination_reason", "inventory_restart_offered")
    const val TOOL_EVENT_PERSISTENCE_FEATURE = "TOOL_EVENT_PERSISTENCE_EVIDENCE_V1"
    const val SQLITE_CONSTRAINT_FEATURE = "SQLITE_CONSTRAINT_EVIDENCE_V1"
    const val URL_READER_AVAILABILITY_FEATURE = "URL_READER_AVAILABILITY_V1"
    private val toolEventSignedSequences = setOf("tool_event_requested_sequence", "tool_event_previous_max_sequence")
    val toolEventPersistenceFields = toolEventSignedSequences + setOf("tool_event_persistence_operation", "tool_event_persistence_phase", "tool_event_persistence_failure", "tool_event_assigned_sequence", "tool_event_write_attempted")
    val reviewComparisonFields = setOf("review_comparison_state", "review_comparison_policy")
    const val GITHUB_POLICY_RESPONSE_FEATURE = "GITHUB_POLICY_RESPONSE_EVIDENCE_V1"
    private val policyResponseHeaderOrder = listOf("REPORTED_OAUTH_SCOPES", "ACCEPTED_OAUTH_SCOPES", "ACCEPTED_GITHUB_PERMISSIONS", "SSO")
    private val policyResponseHeaderFields = setOf("status_policy_protection_permission_header_names", "status_policy_rules_permission_header_names")
    val policyResponseFields = policyResponseHeaderFields + setOf("status_policy_protection_404_kind", "status_policy_rules_404_kind")

    const val GITHUB_PR_CONTEXT_RACE_FEATURE = "GITHUB_PR_CONTEXT_RACE_EVIDENCE_V1"
    val prContextRaceFields = setOf("pr_context_revision_changed", "pr_context_read_requests", "pr_context_read_responses")

    /** Sanitized fields retained when an observed remote traversal fails. */
    val remotePaginationFields = setOf(
        "remote_pagination_page",
        "remote_pagination_pages_observed",
        "remote_pagination_reported_total",
        "remote_pagination_unique_records",
        "remote_pagination_duplicate_records",
        "remote_pagination_contiguous",
        "remote_pagination_changed",
        "remote_pagination_reason"
    )
    private val toolFields = setOf(
        "native_tool_action", "tool_contract_version", "action_schema_generation_hash", "validation_field_names", "validation_path_hashes", "unknown_field_hashes", "unknown_field_count",
        "argument_field_names", "argument_field_types", "resolved_argument_field_names", "resolved_argument_field_types", "resolved_argument_shape_hash",
        "saved_view_owner_operation", "saved_view_connection_hash", "saved_view_scope_hash", "saved_view_source_hash", "saved_view_cursor_hash", "saved_view_cursor_issued", "saved_view_expired", "saved_view_restore_state", "saved_view_restore_error_hash",
        "github_validation_endpoint", "github_error_resources", "github_error_fields", "github_error_codes", "server_request_id_hash", "argument_repair_signature_hash", "argument_repair_attempt", "argument_repair_exhausted",
        "mcp_connection_hash", "mcp_failure_phase", "mcp_failure_category", "mcp_connection_state", "mcp_readiness_state", "mcp_connection_health", "mcp_health_observation", "mcp_health_age_ms",
        "native_dependency_state", "dependency_call_id_hashes", "dependency_count", "dependency_failed_count", "semantic_read_rules", "semantic_ci_rules", "search_validation_field_names", "dependency_admission_reasons", "search_cursor_state", "search_id_hash", "search_cursor_hash"
    ) + remotePaginationFields + prContextRaceFields
    private val acceptanceFields = setOf(
        "task_kind", "acceptance_evaluator_branch", "acceptance_mismatch", "eligible_receipt_count", "excluded_receipt_count", "receipt_count_scope", "receipt_exclusion_reasons",
        "expected_operation", "expected_action", "evidence_ref_hashes", "required_capability_count", "verified_capability_count", "blocked_capability_count", "untested_capability_count"
    )
    private val gatewayFields = setOf("command_receipt_observation", "command_id_hash", "gateway_source_commit_hash", "gateway_package_digest_hash")
    private val providerFields = setOf(
        "provider_error_code_state", "provider_error_origin",
        "provider_rejection_category", "provider_error_type_hash", "provider_parameter", "provider_parameter_path_hash", "payload_contract",
        "privacy_catalog_entries", "privacy_model_endpoint_count", "privacy_compatible_endpoint_count", "privacy_allowlist_excluded_count",
        "privacy_denylist_excluded_count", "privacy_fallback_excluded_count", "privacy_metadata_age_ms", "privacy_blockers"
    ) + gatewayDeliveryFields + providerExecutionFields
    private val voiceFields = setOf("voice_pronunciation_unknown_word_length", "run_id_hash", "invocation_id_hash", "voice_reply_failed", "voice_reply_terminal_status", "voice_reply_failure_evidence", "voice_chat_failure_code", "voice_chat_failure_code_hash", "voice_chat_failure_phase", "voice_focus_last_transition", "voice_focus_transition_count", "voice_pronunciation_unknown_words", "voice_pronunciation_initialisms", "voice_pronunciation_failure_kind", "voice_pronunciation_audio_queued", "voice_pronunciation_first_audio_observed")
    private val hashes = setOf("claim_extraction_schema_hash", "causal_id_hash", "action_schema_generation_hash", "resolved_argument_shape_hash", "saved_view_connection_hash", "saved_view_scope_hash", "saved_view_source_hash", "saved_view_cursor_hash", "saved_view_restore_error_hash", "server_request_id_hash", "command_id_hash", "gateway_source_commit_hash", "gateway_package_digest_hash", "argument_repair_signature_hash", "provider_error_type_hash", "provider_parameter_path_hash", "voice_chat_failure_code_hash", "mcp_connection_hash", "search_id_hash", "search_cursor_hash", "provider_wait_checkpoint_hash", "provider_wait_claim_hash", "provider_route_hash") + handoffReconciliationHashes
    private val hashLists = setOf("validation_path_hashes", "unknown_field_hashes", "evidence_ref_hashes", "exception_type_hashes", "dependency_call_id_hashes", "pending_tool_identity_hashes")
    private val flags = failedEventFields + setOf("tls_response_started", "provider_local_budget_block", "saved_view_cursor_issued", "saved_view_expired", "provider_wait_persisted", "argument_repair_exhausted", "voice_reply_failed", "dispatch_policy_resume_required", "chat_delivery_counts_capped", "tool_event_write_attempted", "inventory_restart_offered", "claim_extraction_completed", "remote_pagination_contiguous", "remote_pagination_changed", "pr_context_revision_changed", "voice_pronunciation_audio_queued", "voice_pronunciation_first_audio_observed")
    private val counts = setOf("recovery_attempt_count", "unknown_field_count", "eligible_receipt_count", "excluded_receipt_count", "required_capability_count", "verified_capability_count", "blocked_capability_count", "untested_capability_count", "privacy_catalog_entries", "privacy_model_endpoint_count", "privacy_compatible_endpoint_count", "privacy_allowlist_excluded_count", "privacy_denylist_excluded_count", "privacy_fallback_excluded_count", "claim_extraction_response_characters", "claim_extraction_max_claims", "dependency_count", "dependency_failed_count", "pending_tool_count", "remote_pagination_page", "remote_pagination_pages_observed", "remote_pagination_reported_total", "remote_pagination_unique_records", "remote_pagination_duplicate_records", "pr_context_read_requests", "pr_context_read_responses", "voice_focus_transition_count", "voice_pronunciation_unknown_words", "voice_pronunciation_initialisms") + policyCounts
    private val executionCounts = setOf("tool_result_bytes_used", "tool_result_bytes_limit", "tool_budget_calls_used", "tool_budget_calls_limit", "combined_issue_count", "combined_failed_check_count", "combined_unverified_check_count", "combined_repair_rounds") + acceptanceBlockerFields
    private val types = setOf("STRING", "NUMBER", "BOOLEAN", "OBJECT", "ARRAY", "NULL")
    private val mcpHealth = setOf("DNS_FAILED", "DNS_RECOVERY", "ROUTE_UNAVAILABLE", "CONNECTION_REFUSED", "AUTHENTICATION_FAILED", "TLS_FAILED", "CONNECT_TIMEOUT", "HTTP_ERROR", "MCP_HANDSHAKE_FAILED", "STREAM_INTERRUPTED", "STREAM_IDLE_TIMEOUT", "REQUEST_TIMEOUT", "HEALTHY", "UNKNOWN")
    private val enums = mapOf(
        // The selected provider's error object may omit both code and type.
        // Capture only the producer's closed type, never its raw error string.
        "provider_error_code_state" to setOf("ABSENT"),
        "provider_error_origin" to setOf("OPENAI_COMPAT_STREAM_ERROR_VALUE"),
        "claim_extraction_validation_stage" to setOf("CLAIM_LIST", "CLAIM_COUNT", "CLAIM_RECORD", "REQUIREMENT_MAP", "QUESTIONS"),
        "github_prerequisite_observation" to setOf("CATALOG_TOOL_NOT_EXPOSED", "CATALOG_INITIAL_REQUEST_UNAVAILABLE", "STATUS_TOOL_NOT_EXPOSED", "STATUS_ARGUMENTS_UNAVAILABLE", "STATUS_READ_FAILED", "STATUS_RECEIPT_UNUSABLE"),
        "claim_extraction_failure_kind" to setOf("INVALID_JSON", "NON_OBJECT", "SCHEMA_REJECTED"),
        "inventory_pagination_reason" to setOf("MISSING_VERSION", "VERSION_MISMATCH"),
        "remote_pagination_reason" to setOf("INCOMPLETE", "STALLED"),
        "sqlite_constraint_kind" to setOf("UNIQUE", "FOREIGN_KEY", "NOT_NULL", "CHECK", "PRIMARY_KEY", "TRIGGER", "UNKNOWN"),
        "dependency_repeat_scope" to setOf("TOOL_NAME", "TOOL_NAME_AND_ARGUMENTS"),
        "tool_event_persistence_operation" to setOf("START"),
        "tool_event_persistence_phase" to setOf("ALLOCATE_SEQUENCE", "INSERT"),
        "tool_event_persistence_failure" to setOf("RUN_SEQUENCE_CONFLICT", "EVENT_ID_CONFLICT", "RUN_FOREIGN_KEY", "NOT_NULL", "UNKNOWN_CONSTRAINT", "SEQUENCE_EXHAUSTED"),
        "review_comparison_state" to setOf("HEAD_REQUIRED", "BASELINE_REQUIRED", "BASE_REF_REQUIRED", "BASE_REF_BLOCKED", "PR_BASE_LOOKUP", "PR_BASE_UNVERIFIED", "BASE_SHA_REQUIRED", "COMPARISON_PAGES_PENDING", "READ_CHANGED_SOURCE", "COMPARE_REQUIRED"),
        "review_comparison_policy" to setOf("UNRESOLVED", "EXPLICIT_BASE_SHA", "EXPLICIT_BASE_REF", "OBSERVED_PR_BASE", "DEFAULT_BRANCH_HEAD"),
        "chat_delivery_terminal" to setOf("DONE"),
        "chat_delivery_answer_state" to setOf("EMPTY_CONTENT", "NO_ANSWER_CONTENT", "GITHUB_ANSWER_PENDING"),
        "handoff_checkpoint_state" to setOf("TOOL_HANDOFF", "TOOL_RESULTS_READY"),
        "handoff_durable_lookup" to setOf("RETURNED_EMPTY", "RETURNED_RECEIPTS"),
        "url_reader_dns_state" to setOf("RECOVERY_COOLDOWN", "LOOKUP_FAILED"),
        "url_reader_availability" to setOf("HTTP_UNAVAILABLE", "NEGATIVE_CACHE", "TURN_SUPPRESSION", "REPEAT_LIMIT", "REPEATED_FAILURE"),
        "fixture_decision_mode" to setOf("AUTO_APPROVE", "SUGGESTED", "ASK", "UNKNOWN"),
        "crash_surface" to setOf("CHAT", "DEBUG_LOGS", "GITHUB_WORKSPACE"),
        "crash_boundary" to setOf("ANDROID_VIEW_DRAW"),
        "pending_tool_receipt_state" to setOf("CHECKPOINT_STATE_INCONSISTENT", "TERMINAL_RECEIPTS_MISSING", "SOME_TERMINAL_RECEIPTS_MISSING", "OUTCOMES_UNRESOLVED"),
        "local_readiness_failure" to setOf("INSUFFICIENT_MEMORY", "PENDING_CLEANUP", "VERIFICATION_REQUIRED", "UNSUPPORTED_BACKEND", "INCOMPATIBLE_MODEL", "MISSING_RUNTIME", "INITIALIZATION_TIMEOUT", "UNKNOWN"),
        "fixture_approval_state" to setOf("READY", "CONDITIONAL", "UNKNOWN"),
        "branch_protection_observation" to policyObservation,
        "branch_rules_observation" to policyObservation,
        "dependency_repeat_prior_coverage" to setOf("COMPLETE", "PARTIAL", "UNAVAILABLE"),
        "dependency_repeat_batch_coverage" to setOf("COMPLETE", "PARTIAL", "UNAVAILABLE"),
        "status_policy_protection_404_kind" to setOf("NOT_FOUND", "OTHER_404", "EXPLICIT_BRANCH_NOT_PROTECTED"),
        "status_policy_rules_404_kind" to setOf("NOT_FOUND", "OTHER_404"),
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
        "provider_parameter" to setOf("model", "messages", "max_tokens", "max_completion_tokens", "temperature", "top_p", "stop", "tools", "tool_choice", "stream", "response_format", "reasoning", "thinking", "metadata", "system", "input", "max_output_tokens", "previous_response_id", "instructions"),
        "payload_contract" to setOf("CHAT_COMPLETIONS", "OPENAI_RESPONSES", "ANTHROPIC_MESSAGES", "GOOGLE_CONTENT", "GATEWAY_GENERATION"),
        "voice_reply_terminal_status" to setOf("COMPLETED", "FAILED", "CANCELED", "INTERRUPTED", "PARTIAL", "EVIDENCE_MISSING", "BLOCKED", "WAITING_FOR_APPROVAL"),
        "voice_reply_failure_evidence" to setOf("TYPED_INVOCATION_REFERENCE", "CLASSIFICATION_ONLY", "MISSING_FAILURE_RECORD"),
        "voice_chat_failure_code" to IssueReportCodes.allowed,
        "voice_chat_failure_phase" to phases - "unknown",
        "voice_pronunciation_failure_kind" to setOf("UNKNOWN_WORD", "UNSUPPORTED_SCRIPT", "MISSING_LETTER"),
        "voice_focus_last_transition" to setOf("RESTORE", "PAUSE", "DUCK", "STOP"),
        "github_validation_endpoint" to setOf("COMMIT_LOOKUP", "TREE_READ", "REVISION_COMPARE"),
        "native_tool_action" to actions,
        "saved_view_owner_operation" to IssueReportContext.operations,
        "saved_view_restore_state" to setOf("NOT_FOUND", "LOAD_FAILED", "IDENTITY_REJECTED", "RESTORED", "BODY_MISSING", "BODY_MISSING_INDEX_PRESENT", "BODY_UNREADABLE", "CURSOR_INDEX_MISSING", "CURSOR_INDEX_INVALID", "INVALID_VIEW_ID"),
        "mcp_failure_phase" to setOf("configuration", "catalog", "discovery", "initialization", "execution", "connection", "dns", "tls", "oauth_refresh", "local_validation"),
        "mcp_failure_category" to (mcpHealth - "HEALTHY" + setOf("MCP_TOOL_ERROR", "MCP_OUTPUT_INVALID")),
        "mcp_connection_health" to mcpHealth,
        "mcp_connection_state" to setOf("CONNECTED", "DEGRADED", "UNREACHABLE", "AUTHENTICATION_REQUIRED", "RECOVERING"),
        "mcp_readiness_state" to setOf("NOT_DISCOVERED", "DISCOVERING", "READY", "STALE"),
        "mcp_health_observation" to setOf("RETAINED_SNAPSHOT", "UNAVAILABLE"),
        "native_dependency_state" to setOf("DEFERRED", "FAILED"),
        "tool_execution_observation" to setOf("REMOTE_READ_SUCCEEDED"),
        "tool_budget_kind" to setOf("CALLS", "RESULT_BYTES", "BOTH", "COMBINED_WORK"),
        "tool_budget_phase" to setOf("BEFORE_DISPATCH", "AFTER_DISPATCH"),
        "tool_permission_scope" to setOf("ANDROID_LOCATION", "GITHUB_LOCAL_GRANT"),
        "tls_failure_kind" to setOf("CERTIFICATE", "PROTOCOL", "HANDSHAKE", "OTHER"),
        "provider_tool_rejection" to setOf("TOOL_USE_FAILED"),
        "tool_protocol_frame_kind" to setOf("MISSING_FUNCTION_NAME", "UNFINISHED_STREAM", "NON_OBJECT_ARGUMENTS", "MALFORMED_JSON"),
        "tool_protocol_recovery_outcome" to setOf("TEXT_FALLBACK_EXHAUSTED", "TOOL_RETRY_EXHAUSTED", "BACKEND_HANDLED_TOOLS", "SINGLE_PASS_NOT_RETRYABLE", "NO_SAFE_REPAIR"),
        "reasoning_probe_observation" to setOf("GATEWAY_OBSERVATION_AVAILABLE", "DEADLINE_WITHOUT_GATEWAY_OBSERVATION", "NO_GATEWAY_OBSERVATION"),
        "gateway_terminal_status" to setOf("completed", "failed", "cancelled", "canceled", "interrupted"),
        "gateway_result_quality" to setOf("complete", "partial"),
        "combined_repair_stop" to combinedRepairStops,
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
        "status_policy_protection_permission_header_names" to policyResponseHeaderOrder.toSet(),
        "status_policy_rules_permission_header_names" to policyResponseHeaderOrder.toSet(),
        "acceptance_claim_freshness_predicates" to setOf("OBSERVATION_TIME_UNAVAILABLE", "OBSERVATION_TIME_FUTURE", "MUTABLE_AGE_EXCEEDED", "MUTABLE_EPOCH_MISMATCH"),
        "dispatch_policy_sections" to policySections,
        "changed_policy_fields" to policyFieldNames,
        "semantic_read_rules" to dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments.ReadRule.entries.mapTo(linkedSetOf()) { it.name },
        "semantic_ci_rules" to setOf("BRANCH_NAME", "COMMIT_SHA", "POSITIVE_ID", "WORKFLOW_SELECTOR", "MISSING_SELECTOR", "UNUSED_FIELD", "LOG_START", "LOG_ORDER", "LOG_SPAN"),
        "search_validation_field_names" to setOf("fuzzy_threshold", "context_lines", "max_new_blobs", "batch_size"),
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
    val priorityFields = coreFields.toSet() + setOf("run_id_hash", "causal_failure_code", "incident_origin", "incident_capture_site", "incident_phase_provenance", "exception_type_hashes", "sqlite_constraint_kind") + policyDiagnostics + runEvidenceFields + handoffReconciliationFields + chatDeliveryFields + claimFreshnessFields + remotePaginationFields + prContextRaceFields + GitHubAcceptancePublicationEvidence.fields

    enum class ExceptionCaptureSite { CHAT_REPOSITORY_TERMINAL_CATCH, CHAT_REPOSITORY_PREFLIGHT, OPENAI_ADAPTER_STREAM_CATCH, PROVIDER_ADAPTER_STREAM_CATCH, CHAT_REPOSITORY_ERROR_VALUE, CHAT_FLOW_EMPTY_ANSWER, AGENT_FAILED_EVENT }

    /** An observed boundary does not establish dispatch, retryability, or a throwable. */
    fun boundaryFields(site: ExceptionCaptureSite): JsonObject = buildJsonObject {
        put("incident_capture_site", site.name)
        put("incident_phase_provenance", "CAPTURE_BOUNDARY_ONLY")
    }

    /** Preserve an observed model invocation without replacing a more specific tool cause. */
    fun invocationFields(invocationId: String?, fields: JsonObject): JsonObject {
        if (fields["causal_id"] != null || fields["causal_id_hash"] != null || fields["call_id"] != null) return fields
        val id = invocationId?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return fields
        return JsonObject(
            fields + buildJsonObject {
                put("invocation_id", id)
                put("causal_id", id)
                put("causal_provenance", "MODEL_INVOCATION")
            }
        )
    }

    /** Captured at the actual catch. A boundary identifies where observed, not where dispatched. */
    fun exceptionFields(error: Throwable, site: ExceptionCaptureSite): JsonObject = buildJsonObject {
        boundaryFields(site).forEach { (key, value) -> put(key, value) }
        generateSequence(error) { it.cause }.take(4).filterIsInstance<ToolEventPersistenceFailure>().firstOrNull()
            ?.evidence?.forEach { (key, value) ->
                if (key in toolEventPersistenceFields) validValue(key, value)?.let { put(key, it) }
            }
        put("cause_types", JsonArray(dev.chungjungsoo.gptmobile.data.network.sanitizedCauseTypes(error).map(::JsonPrimitive)))
        sqliteConstraintKind(error)?.let { put("sqlite_constraint_kind", it) }
        IssueReportContext.exceptionFingerprint(error)?.let { put("exception_fingerprint", it) }
        val (type, frames) = IssueReport.exceptionMetadata(error)
        type?.let { put("exception_report_type", it) }
        val applicationFrames = frames.filter { it.startsWith("dev.chungjungsoo.gptmobile.") || it.startsWith("dev.melo.gptmobile.") }
        if (applicationFrames.isNotEmpty()) put("exception_application_frames", JsonArray(applicationFrames.map(::JsonPrimitive)))
    }

    private fun sqliteConstraintKind(error: Throwable): String? {
        val constraint = generateSequence(error) { it.cause }.take(4).filterIsInstance<SQLiteConstraintException>().firstOrNull() ?: return null
        val raw = constraint.message ?: return "UNKNOWN"
        if (raw.length > 1024) return "UNKNOWN"
        val message = raw.trim().uppercase(Locale.ROOT)
        val prefix = Regex("^(UNIQUE|FOREIGN KEY|NOT NULL|CHECK|PRIMARY KEY|TRIGGER) CONSTRAINT FAILED(?=:|$| \\(CODE )")
            .find(message)?.groupValues?.get(1)?.replace(' ', '_')
        // Match SQLite's extended error marker, not a table name, SQL string or incidental trigger word.
        val marker = Regex("(?:^SQLITE_CONSTRAINT_(UNIQUE|FOREIGNKEY|NOTNULL|CHECK|PRIMARYKEY|TRIGGER)(?=[: \\[\\(]|$)|\\(CODE [0-9]{1,6} SQLITE_CONSTRAINT_(UNIQUE|FOREIGNKEY|NOTNULL|CHECK|PRIMARYKEY|TRIGGER)(?:\\[[0-9]{1,6}])?\\))")
        val kinds = marker.findAll(message).map { match ->
            when (val kind = match.groupValues[1].ifEmpty { match.groupValues[2] }) {
                "FOREIGNKEY" -> "FOREIGN_KEY"
                "NOTNULL" -> "NOT_NULL"
                "PRIMARYKEY" -> "PRIMARY_KEY"
                else -> kind
            }
        }.distinct().toList()
        if (kinds.size > 1) return "UNKNOWN"
        val observed = kinds.singleOrNull() ?: return prefix ?: "UNKNOWN"
        // SQLite describes a primary-key collision with a UNIQUE human prefix.
        return if (prefix == null || prefix == observed || prefix == "UNIQUE" && observed == "PRIMARY_KEY") observed else "UNKNOWN"
    }

    fun fieldsFor(area: String): Set<String> = common + when (area) {
        "TOOL" -> toolFields + toolFailureFields + repeatAdmissionFields + repeatHistoryFields + inventoryPaginationFields
        "INCIDENT" -> setOf("github_prerequisite_observation") + combinedDeliveryFields + handoffReconciliationFields + chatDeliveryFields + toolEventPersistenceFields + reviewComparisonFields + setOf("pending_tool_count", "pending_tool_identity_hashes", "pending_tool_receipt_state", "local_readiness_failure", "provider_error_code_state", "provider_error_origin")
        "ACCEPTANCE" -> setOf("github_prerequisite_observation") + acceptanceFields + acceptanceBlockerFields + claimFreshnessFields + policyResponseFields + GitHubAcceptancePublicationEvidence.fields + setOf("fixture_approval_state", "fixture_decision_mode", "branch_protection_observation", "branch_rules_observation")
        "GATEWAY" -> gatewayFields
        "PROVIDER" -> providerFields + setOf("provider_wait_checkpoint_hash", "provider_wait_claim_hash", "provider_wait_terminal_status", "provider_route_hash")
        "APP" -> setOf("crash_surface", "crash_boundary")
        "VOICE" -> voiceFields
        else -> emptySet()
    }

    fun validValue(key: String, value: JsonElement): JsonElement? {
        if (key in GitHubAcceptancePublicationEvidence.fields) return GitHubAcceptancePublicationEvidence.validValue(key, value)
        if (key in repeatHistoryListFields) {
            val items = value as? JsonArray ?: return null
            if (items.size > 8 || items.any { item -> text(item)?.let { if (key in repeatHistoryHashFields) it.matches(HASH) else it in repeatHistoryOutcomes } != true }) return null
            if (key in repeatHistoryHashFields && items.distinct().size != items.size) return null
            return JsonArray(items.toList())
        }
        if (key in hashLists || key in enumLists) {
            val items = value as? JsonArray ?: return null
            if (key == "semantic_ci_rules") {
                if (items.size !in 1..9 || items.distinct().size != items.size) return null
            } else if (key == "search_validation_field_names") {
                if (items.size !in 1..4 || items.distinct().size != items.size) return null
            } else if (key in claimFreshnessFields || key in policyResponseHeaderFields) {
                if (items.size !in 0..4 || items.distinct().size != items.size) return null
            } else if (items.size !in 1..if (key == "privacy_blockers") 4 else 8) {
                return null
            }
            if (items.any { item -> text(item)?.let { if (key in hashLists) it.matches(HASH) else it in enumLists.getValue(key) } != true }) return null
            // An empty qualified list is a measured absence. Never obtain it by
            // filtering malformed entries; retained subsets keep producer order.
            if (key in policyResponseHeaderFields && items.map { text(it) } != policyResponseHeaderOrder.filter { JsonPrimitive(it) in items }) return null
            return JsonArray(items.toList())
        }
        val primitive = value as? JsonPrimitive ?: return null
        if (key in hashes) return text(primitive)?.takeIf(HASH::matches)?.let(::JsonPrimitive)
        enums[key]?.let { choices -> return text(primitive)?.takeIf { it in choices }?.let(::JsonPrimitive) }
        if (key in flags) return primitive.takeUnless { it.isString }?.booleanOrNull?.let(::JsonPrimitive)
        val number = primitive.takeUnless { it.isString }?.longOrNull ?: return null
        val range = when (key) {
            "voice_pronunciation_unknown_word_length" -> 1L..256L
            "retry_delay_ms", "privacy_metadata_age_ms", "mcp_health_age_ms" -> 0L..86_400_000L
            "tool_contract_version" -> 1L..1000L
            "argument_repair_attempt" -> 1L..3L
            "claim_extraction_response_characters" -> 0L..32_768L
            "claim_extraction_max_claims" -> 1L..8L
            in toolEventSignedSequences -> Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
            "tool_event_assigned_sequence" -> 0L..Int.MAX_VALUE.toLong()
            in handoffReconciliationCounts -> 0L..1024L
            in counts, in executionCounts, in chatDeliveryCounts, in repeatAdmissionCounts -> 0L..16_000_000L
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
        repeatHistoryAligned(values) &&
        executionCountsAligned(values, "tool_result_bytes_used", "tool_result_bytes_limit") &&
        executionCountsAligned(values, "tool_budget_calls_used", "tool_budget_calls_limit")

    /** History is one observed group; the pre-existing admission operands remain independently valid without it. */
    fun repeatHistoryAligned(values: Map<String, JsonElement>, currentCallIdHash: JsonElement? = null): Boolean {
        if (repeatHistoryFields.none { it in values }) return true
        if (!(repeatHistoryFields + repeatAdmissionFields).all { it in values }) return false
        if (text(values["dependency_repeat_scope"]) !in setOf("TOOL_NAME", "TOOL_NAME_AND_ARGUMENTS")) return false
        fun count(key: String) = (values[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0L..16_000_000L }
        val priorCount = count("dependency_repeat_prior_admissions") ?: return false
        val batchCount = count("dependency_repeat_batch_admissions") ?: return false
        val used = count("dependency_repeat_used") ?: return false
        val limit = count("dependency_repeat_limit") ?: return false
        if (priorCount + batchCount != used || used < limit) return false
        val prior = validValue("dependency_repeat_prior_call_id_hashes", values.getValue("dependency_repeat_prior_call_id_hashes")) as? JsonArray ?: return false
        val batch = validValue("dependency_repeat_batch_call_id_hashes", values.getValue("dependency_repeat_batch_call_id_hashes")) as? JsonArray ?: return false
        val outcomes = validValue("dependency_repeat_prior_outcomes", values.getValue("dependency_repeat_prior_outcomes")) as? JsonArray ?: return false
        if (prior.size != outcomes.size || (prior + batch).distinct().size != prior.size + batch.size) return false
        if (listOfNotNull(values["call_id_hash"], currentCallIdHash).any { it in prior || it in batch }) return false
        fun covered(key: String, retained: Int, count: Long): Boolean = when (text(values[key])) {
            "COMPLETE" -> retained.toLong() == count
            "PARTIAL" -> retained > 0 && retained.toLong() < count
            "UNAVAILABLE" -> count > 0 && retained == 0
            else -> false
        }
        return covered("dependency_repeat_prior_coverage", prior.size, priorCount) && covered("dependency_repeat_batch_coverage", batch.size, batchCount)
    }

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
        // These public aliases come only from the actual comparison failure's checkpoint.
        reviewComparisonFields.forEach(::remove)
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
        if (area == "INCIDENT" && source == "CHAT_FAILURE" && text(fields["failed_stage"]) == "review_comparison") {
            for ((sourceKey, reportKey) in listOf("state" to "review_comparison_state", "comparison_policy" to "review_comparison_policy")) {
                text(fields[sourceKey])?.let { validValue(reportKey, JsonPrimitive(it)) }?.let { put(reportKey, it) }
            }
        }
        remove("dispatch_gateway_selection_change")
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
            if (names.take(32).any { text(it) == "githubProfileOverride.engineeringGatewayProfileUid" }) {
                text(fields["dispatch_gateway_selection_change"])?.takeIf { it in setOf("SELECTED", "CLEARED", "REPLACED") }
                    ?.let { put("dispatch_gateway_selection_change", it) }
            }
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
