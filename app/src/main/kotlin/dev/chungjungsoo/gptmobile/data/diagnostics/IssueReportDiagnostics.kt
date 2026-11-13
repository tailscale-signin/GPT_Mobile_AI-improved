package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.github.GatewayInputRejectionDetails
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** A bounded observation chosen before approval. No free text or raw identifiers belong in this contract. */
@Serializable
internal data class IssueReportDiagnostics(
    val schemaVersion: Int,
    val area: String,
    val capturedAtMillis: Long,
    val captureMode: String,
    val values: Map<String, JsonElement>,
    val unavailableFields: List<String>,
    val omittedFields: List<String> = emptyList()
) {
    fun isValid(): Boolean = validated() != null

    /** Constructor/copy callers and restored payloads must pass the same contract as live observations. */
    fun validated(): IssueReportDiagnostics? {
        if (schemaVersion !in setOf(1, 2, 3) || schemaVersion == 2 && area != "GATEWAY" || area == "INCIDENT" && schemaVersion != 3 || area !in areas || capturedAtMillis !in 1..9_999_999_999_999L || captureMode !in captureModes) return null
        val allowed = allowedFields(area, schemaVersion)
        if (values.keys.any { it !in allowed } || omittedFields.any { it !in allowed || it in values } || omittedFields.distinct().size != omittedFields.size) return null
        if (captureMode == "NO_CAPTURE" && (values.isNotEmpty() || omittedFields.isNotEmpty())) return null
        val copied = linkedMapOf<String, JsonElement>()
        values.forEach { (key, value) -> copied[key] = validatedValue(key, value) ?: return null }
        if (!alignedCriteria(copied) || schemaVersion == 3 && !IssueReportIncidentCapsule.aligned(copied)) return null
        val expectedUnavailable = requiredFields(area, schemaVersion).filterNot { it in copied }
        if (unavailableFields.toSet() != expectedUnavailable.toSet() || unavailableFields.distinct().size != unavailableFields.size) return null
        val snapshot = copy(values = copied.toMap(), unavailableFields = unavailableFields.toList(), omittedFields = omittedFields.toList())
        return snapshot.takeIf { IssueReport.json.encodeToString(serializer(), it).encodeToByteArray().size <= MAX_BYTES }
    }

    internal fun requiredServiceFeatures(): Set<String> = buildSet {
        if ((values.keys + omittedFields).any { it in IssueReportIncidentCapsule.runEvidenceFields } ||
            values["incident_capture_site"] == JsonPrimitive("CHAT_REPOSITORY_PREFLIGHT") ||
            values["incident_origin"] == JsonPrimitive("APPLICATION") ||
            (values["unmet_criterion_blockers"] as? JsonArray)?.contains(JsonPrimitive("FIXTURE_APPROVAL_STATE_UNKNOWN")) == true ||
            listOf("argument_field_names", "resolved_argument_field_names", "validation_field_names").any { key ->
                (values[key] as? JsonArray).orEmpty().any { it in newEvidenceFieldNames }
            }
        ) {
            add("RUN_FAILURE_EVIDENCE_V1")
        }
        if ((values.keys + omittedFields).any { it in recoveryObservationFields } ||
            (values["saved_view_restore_state"] as? JsonPrimitive)?.contentOrNull in setOf("BODY_MISSING", "CURSOR_INDEX_MISSING", "INVALID_VIEW_ID") ||
            "saved_view_restore_state" in omittedFields
        ) {
            add("RECOVERY_OBSERVATIONS_V1")
        }
        if (area == "GATEWAY" && (values.keys + omittedFields).any { it in modelPreparationFields }) add(MODEL_PREPARATION_FEATURE)
        if ((values.keys + omittedFields).any { it in failureDetailFields }) add("INCIDENT_FAILURE_DETAILS_V1")
        if ((values.keys + omittedFields).any { it in IssueReportIncidentCapsule.dispatchPolicyFields }) add("DISPATCH_POLICY_DIAGNOSTICS_V1")
        if ((values.keys + omittedFields).any { it in IssueReportIncidentCapsule.policyDiagnostics }) add("CHANGED_POLICY_FIELDS_V1")
        if ((values.keys + omittedFields).any { it in executionFields && (area != "GATEWAY" || it !in modelPreparationFields) } ||
            values["provider_rejection_category"] == JsonPrimitive("BILLING_UNAVAILABLE")
        ) {
            add("EXECUTION_OUTCOME_DIAGNOSTICS_V1")
        }
    }

    companion object {
        private val recoveryObservationFields = setOf(
            "url_reader_dns_state",
            "fixture_decision_mode",
            "voice_speech_end_to_transcript_ms",
            "voice_transcript_to_request_ms",
            "voice_request_to_response_ms"
        )

        // The capsule reads IssueReportContext during initialization. Resolve this union
        // only when checking service capabilities, after the diagnostics types initialize.
        private val executionFields by lazy {
            IssueReportIncidentCapsule.toolFailureFields +
                IssueReportIncidentCapsule.gatewayDeliveryFields +
                IssueReportIncidentCapsule.providerExecutionFields +
                IssueReportIncidentCapsule.combinedDeliveryFields +
                IssueReportIncidentCapsule.acceptanceBlockerFields
        }
        val failureDetailFields = setOf("provider_failure_stage", "provider_failure_domain", "provider_failure_code", "voice_reply_state", "voice_reply_characters", "tool_action", "tool_error_code", "tool_reason_code", "tool_dispatch_phase", "unknown_field_count", "chat_failure_code_state", "provider_wait_eligible", "provider_checkpoint_present", "provider_checkpoint_continuation_allowed", "provider_consecutive_rejections", "unmet_requirement_count", "unmet_capability_count")
        private val newEvidenceFieldNames = setOf("end_line", "max_lines", "start_offset", "ranges", "evidence_id").map(::JsonPrimitive).toSet()
        const val MAX_BYTES = 3072
        const val MAX_CRITERIA = 8
        const val MODEL_PREPARATION_FEATURE = "GATEWAY_MODEL_PREPARATION_V1"
        val areas = setOf("PROVIDER", "TOOL", "ACCEPTANCE", "VOICE", "GATEWAY", "APP", "INCIDENT")
        private val captureModes = setOf("INCIDENT", "SELECTED_CAPTURE", "NO_CAPTURE")
        private val hashPattern = Regex("[a-f0-9]{64}")

        // Closed readiness vocabulary emitted by capability/model-preparation producers.
        val modelPreparationReasonCodes = setOf(
            "BACKEND_CONNECTION_REFUSED", "BACKEND_CONNECT_TIMEOUT", "BACKEND_READ_TIMEOUT", "BACKEND_DNS_FAILED",
            "BACKEND_TLS_FAILED", "BACKEND_CONNECTION_CLOSED", "BACKEND_CONNECTION_FAILED", "BACKEND_HTTP_ERROR",
            "BACKEND_METADATA_DENIED", "BACKEND_ROUTE_UNSUPPORTED", "BACKEND_INVALID_RESPONSE", "BACKEND_RESPONSE_TOO_LARGE",
            "BACKEND_METADATA_DEADLINE", "BACKEND_CONFIGURATION_INVALID", "SELECTED_MODEL_NOT_AVAILABLE", "SELECTED_MODEL_NOT_LOADED",
            "SELECTED_MODEL_LOADING", "SELECTED_MODEL_UNVERIFIED", "BACKEND_GENERATION_UNAVAILABLE", "BACKEND_GENERATION_UNVERIFIED",
            "GATEWAY_DRAINING", "DURABLE_STORAGE_UNAVAILABLE", "DURABLE_STORAGE_WRITE_FAILED", "EXECUTION_METADATA_DEADLINE",
            "EXECUTION_METADATA_DENIED", "EXECUTION_METADATA_OVERSIZE", "EXECUTION_CAPABILITY_UNSUPPORTED", "EXECUTION_CAPABILITY_INVALID",
            "EXECUTION_BACKEND_UNAVAILABLE", "EXECUTION_MODEL_IDENTITY_INVALID", "EXECUTION_MODEL_UNAVAILABLE", "EXECUTION_MODEL_NOT_LOADED",
            "EXECUTION_MODEL_LOADING", "EXECUTION_MODEL_MISMATCH", "EXECUTION_CONTEXT_UNAVAILABLE", "REASONING_CAPABILITY_TIMEOUT",
            "REASONING_CAPABILITY_UNAVAILABLE", "REASONING_CAPABILITY_UNVERIFIED", "REASONING_ON_UNSUPPORTED", "REASONING_OFF_UNSUPPORTED",
            "REASONING_AUTO_UNSUPPORTED", "GATEWAY_REMOTE_SURFACE_NOT_ADMITTED"
        )
        private val commonHashes = setOf("run_id_hash", "invocation_id_hash", "call_id_hash", "job_id_hash", "profile_hash", "model_hash")
        private val hashFields = commonHashes + setOf(
            "voice_session_id_hash", "original_error_code_hash", "original_reason_code_hash", "server_request_id_hash", "generation_id_hash",
            "privacy_policy_hash", "backend_terminal_code_hash", "tool_name_hash", "tool_action_hash", "tool_schema_hash", "argument_shape_hash", "checkpoint_hash", "gateway_manifest_hash", "gateway_operation_id_hash", "operation_id_hash"
        )
        private val hashLists = setOf("unmet_criterion_id_hashes", "unmet_criterion_reason_hashes")
        private val flags = setOf(
            "provider_wait_eligible", "provider_checkpoint_present", "provider_checkpoint_continuation_allowed",
            "transport_complete", "generation_completed", "request_replay_allowed", "logical_continuation_allowed", "privacy_zdr_required", "privacy_fallbacks_allowed",
            "voice_engine_warm", "voice_permission_granted", "voice_foreground", "voice_muted", "voice_input_silenced", "gateway_admission_ready", "model_preparation_load_accepted"
        )
        private val attempts = setOf("backend_attempts", "backend_completed_attempts", "backend_failed_attempt", "repair_attempts", "repair_attempt_limit")
        private val durations = setOf(
            "voice_speech_end_to_transcript_ms",
            "voice_transcript_to_request_ms",
            "voice_request_to_response_ms",
            "voice_response_to_queue_ms",
            "voice_queue_to_synthesis_ms",
            "voice_synthesis_to_first_audio_ms",
            "voice_response_to_first_audio_ms",
            "voice_warmup_ms"
        )
        private val counts = setOf(
            "voice_reply_characters",
            "provider_consecutive_rejections", "unmet_requirement_count", "unmet_capability_count",
            "unknown_field_count",
            "configured_output_limit", "effective_output_limit", "backend_output_limit", "input_tokens", "output_tokens", "reasoning_tokens", "visible_output_bytes", "reasoning_output_bytes",
            "privacy_provider_allowlist_count", "privacy_provider_denylist_count", "privacy_provider_order_count", "missing_field_count", "invalid_field_count", "validation_error_count",
            "effective_call_limit", "used_calls", "remaining_calls", "verification_reserve", "pending_continuations", "unresolved_outcomes", "unmet_criterion_count", "unmet_criterion_omitted_count",
            "voice_capture_attempts", "voice_read_calls", "voice_captured_samples", "voice_nonzero_samples", "voice_recognized_characters"
        )
        private val finishReasons = setOf("STOP", "TOOL_CALLS", "LENGTH", "CONTENT_FILTER", "OTHER", "UNKNOWN")
        private val enums = mapOf(
            "provider_failure_stage" to setOf("ADMISSION", "ENGINEERING_GUARD", "GENERATION", "CONTINUATION", "REVIEW_COMPATIBILITY", "ANSWER_REVIEW", "CLAIM_VERIFICATION", "DELIVERY"),
            "provider_failure_domain" to setOf("PROVIDER", "APPROVAL", "APPLICATION_VERIFICATION", "TOOL_EXECUTION", "UNKNOWN"),
            "provider_failure_code" to setOf("HTTP_ERROR", "TRANSPORT_ERROR", "UNCLASSIFIED_PROVIDER_FAILURE", "OUTPUT_LIMIT", "CONTEXT_CAPACITY_EXCEEDED", "LOCAL_SERVICE_UNREACHABLE", "GATEWAY_OUTCOME_UNKNOWN", "TIMEOUT_OUTCOME_UNKNOWN", "RUN_DEADLINE_REACHED", "PROVIDER_RATE_LIMITED", "PRIVACY_CONFIGURATION"),
            "voice_reply_state" to setOf("FAILED_WITHOUT_DETAILS", "EMPTY_REPLY"),
            "tool_action" to setOf("status", "list", "headers", "context", "comments", "reviews", "threads", "history", "commit", "branch", "changes", "checks", "workflows", "runs", "jobs", "logs", "failure", "read", "search", "create", "update", "close", "reopen", "review", "diff", "summary", "files", "tree", "get", "rerun", "rerun_failed_jobs", "cancel", "dispatch"),
            "tool_error_code" to setOf("APP_ERROR", "TOOL_FAILURE", "INVALID_ARGUMENTS", "TOOL_UNAVAILABLE", "SAVED_VIEW_UNAVAILABLE", "GITHUB_VALIDATION_FAILED", "HTTP_ERROR", "GITHUB_NOT_FOUND_OR_INACCESSIBLE", "GITHUB_REVIEW_BLOCKED", "PRECHECK_REJECTED", "TOOL_OUTCOME_UNKNOWN", "UNKNOWN_TOOL", "GITHUB_CONFLICT", "GITHUB_PRIMARY_RATE_LIMITED", "GITHUB_SECONDARY_RATE_LIMITED", "GITHUB_FORBIDDEN_UNKNOWN", "GITHUB_ENDPOINT_PERMISSION_REQUIRED"),
            "tool_reason_code" to setOf("BUDGET_EXHAUSTED", "TOOL_BATCH_OVERSIZE", "DUPLICATE_INVOCATION", "REPEATED_FAILURE", "BUDGET_CHECKPOINT_UNAVAILABLE", "UNSUPPORTED_TOOL_CONTRACT", "UNSUPPORTED_OPERATION", "UNKNOWN_TOOL", "SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_CURSOR_ALTERED", "TOOL_ARGUMENT_SCHEMA_REJECTED", "TOOL_ARGUMENT_CONSTRAINT_REJECTED", "ISSUE_SELECTOR_REQUIRED", "PR_SELECTOR_REQUIRED"),
            "tool_dispatch_phase" to setOf("PREFLIGHT_REJECTED", "READ_FAILED", "OBSERVED", "OUTCOME_UNKNOWN", "PARTIAL_ACCEPTED", "COMPLETED", "FAILED", "PRECHECK_REJECTED"),
            "chat_failure_code_state" to setOf("ABSENT", "KNOWN", "UNRECOGNIZED"),

            "response_format" to setOf("TEXT", "GITHUB_REVIEW_V1", "CLAIM_JSON"),
            "finish_reason" to finishReasons,
            "backend_finish_reason" to finishReasons,
            "backend_status" to setOf("COMPLETED", "FAILED", "UNKNOWN"),
            "output_byte_scope" to setOf("CLIENT_STREAM", "LAST_BACKEND_ATTEMPT"),
            "privacy_observation" to setOf("CACHED_REJECTION", "NO_COMPATIBLE_ENDPOINT"),
            "usage_source" to setOf(
                "provider_reported", "runtime_reported", "gateway_reported", "mixed_reported", "gateway_reported_partial", "estimated", "unavailable",
                "provider_reported_initial", "provider_reported_interim", "runtime_reported_initial", "runtime_reported_interim", "gateway_reported_initial", "gateway_reported_interim", "mixed_reported_initial", "mixed_reported_interim"
            ),
            "acceptance_evidence_scope" to setOf("CURRENT_VERDICT", "RETAINED_VERDICT"),
            "voice_engine" to setOf("SYSTEM", "LOCAL", "KOKORO"),
            "voice_phase" to setOf("PAUSED", "STARTING", "LISTENING", "FINALIZING", "REVIEWING", "THINKING", "SPEAKING", "ERROR", "CLOSED"),
            "voice_focus_result" to setOf("GRANTED", "DENIED", "ERROR", "NOT_REQUESTED"),
            "voice_first_audio_basis" to setOf("UTTERANCE_START", "PLAYBACK_HEAD"),
            "gateway_reason_code" to GatewayInputRejectionDetails.reasonCodes + modelPreparationReasonCodes,
            "gateway_failure_phase" to setOf("model_preparation", "connection_preflight", "provider_admission", "request_validation", "engineering_guard", "binding_admission", "message_admission", "conversation_admission", "execution_admission", "gateway_recovery", "transport"),
            "gateway_state" to setOf("QUEUED", "RUNNING", "WAITING_FOR_CLIENT_TOOL", "WAITING_FOR_APPROVAL", "PAUSED", "CANCELLING", "CANCELLED", "COMPLETED", "FAILED"),
            "model_preparation_state" to setOf("PREPARING", "PRECHECK_REJECTED", "LOAD_REJECTED", "LOAD_ACCEPTED", "LOAD_OUTCOME_UNKNOWN", "WAITING_FOR_MODEL", "METADATA_QUALIFIED", "QUALIFICATION_BLOCKED"),
            "model_preparation_action" to setOf("NONE", "LOAD", "WAKE"),
            "model_preparation_dispatch" to setOf("NOT_DISPATCHED", "DISPATCHED"),
            "gateway_model_state" to setOf("UNKNOWN", "UNAVAILABLE", "UNLOADED", "LOADING", "LOADED", "SLEEPING"),
            "gateway_failure_category" to setOf("connection_refused", "connect_timeout", "read_timeout", "dns", "tls", "connection_closed", "connection", "http", "denied", "unsupported", "invalid_response", "oversize", "deadline", "configuration", "model_unavailable", "model_not_loaded", "model_loading", "generation_unavailable", "unverified", "storage", "draining"),
            "gateway_dependency" to setOf("model_backend", "backend_health", "backend_models", "backend_properties", "backend_template", "selected_model", "durable_storage", "process"),
            "gateway_upstream_message_code" to setOf("MODEL_NOT_LOADED", "MODEL_LOADING", "MODEL_NOT_FOUND", "MODEL_LOAD_FAILED", "MODEL_SELECTOR_REQUIRED", "INVALID_API_KEY", "ENDPOINT_DISABLED"),
            "app_exit_kind" to setOf("JVM_CRASH", "NATIVE_CRASH", "APP_NOT_RESPONDING", "STARTUP_FAILED")
        )
        private val enumLists = mapOf(
            "invalid_field_classes" to setOf("MISSING_REQUIRED", "WRONG_TYPE", "EMPTY_STRING", "OUT_OF_RANGE", "INVALID_ENUM", "PATTERN_MISMATCH", "UNKNOWN_FIELD", "COMPOSITE_CONSTRAINT"),
            "unmet_criterion_categories" to setOf("BUILD_REVISION_BINDING", "AUTHORIZED_CATALOG", "FIXTURE_LIFECYCLE", "TOOL_ACTION_COVERAGE", "EVIDENCE_SEPARATION", "DEVICE_EXECUTION", "REPOSITORY_GRAPH", "CI_VERIFICATION", "CI_OBSERVATION", "API_OPERATION", "REPOSITORY_LIFECYCLE", "REQUIREMENT"),
            "unmet_criterion_states" to setOf("PASS", "FAIL", "BLOCKED", "NOT_TESTED"),
            "unmet_criterion_blockers" to setOf(
                "OUT_OF_SCOPE", "REPOSITORY_SCOPE_EXCLUDED", "OPERATION_IDENTITY_REQUIRED", "AUTHORIZATION_REQUIRED", "HISTORICAL_CONTRACT_CHANGED", "APPROVAL_REQUIRED", "FIXTURE_APPROVAL_MODE_REQUIRED", "FIXTURE_APPROVAL_STATE_UNKNOWN",
                "LIVE_EVIDENCE_REQUIRED", "CONTINUATION_REQUIRED", "PRECHECK_REJECTED", "OPERATION_FAILED", "USER_DECISION_REQUIRED", "CHECK_SUBSCRIPTION_REQUIRED", "OWNED_FIXTURE_REQUIRED",
                "ISSUE_ID_REQUIRED", "PULL_REQUEST_ID_REQUIRED", "WORKFLOW_RUN_OR_JOB_REQUIRED", "RELEASE_OR_TAG_REQUIRED", "REPOSITORY_INPUT_REQUIRED", "REPOSITORY_LIFECYCLE_REQUIRED",
                "EXECUTION_NOT_EXERCISED", "BLOCKER_DETAIL_NOT_RECORDED", "NONE", "VERIFICATION_FAILED", "REQUIRED_OPERATION_BLOCKED", "DEVICE_EXECUTION_NOT_OBSERVED", "CATALOG_NOT_EXHAUSTED",
                "FIXTURE_PROOF_INCOMPLETE", "GRAPH_QUALIFICATION_INCOMPLETE", "REPOSITORY_OPERATION_UNVERIFIED", "MATCHING_EVIDENCE_MISSING"
            )
        )
        private val criterionFields = hashLists + setOf("unmet_criterion_categories", "unmet_criterion_states", "unmet_criterion_blockers")
        private val gatewayAdmissionFields = setOf("gateway_operation_id_hash", "gateway_manifest_hash", "gateway_version", "gateway_protocol_version", "gateway_admission_ready", "gateway_failure_phase", "gateway_http_status", "gateway_reason_code")
        private val gatewayAdmissionCore = listOf("gateway_manifest_hash", "gateway_protocol_version", "gateway_admission_ready")
        private val modelPreparationFields = setOf(
            "original_reason_code_hash", "operation_id_hash", "model_preparation_state", "model_preparation_action", "model_preparation_dispatch", "model_preparation_load_accepted", "model_preparation_load_requests",
            "gateway_model_state", "gateway_failure_category", "gateway_dependency", "gateway_upstream_status", "gateway_upstream_message_code", "gateway_reconciliation_status"
        )
        private val fieldsByArea = mapOf(
            "PROVIDER" to commonHashes + setOf(
                "provider_failure_stage", "provider_failure_domain", "provider_failure_code",
                "provider_wait_eligible", "provider_checkpoint_present", "provider_checkpoint_continuation_allowed", "provider_consecutive_rejections",
                "original_error_code_hash", "server_request_id_hash", "generation_id_hash", "response_format", "finish_reason", "configured_output_limit", "effective_output_limit", "backend_output_limit",
                "input_tokens", "output_tokens", "reasoning_tokens", "visible_output_bytes", "reasoning_output_bytes", "backend_attempts", "backend_completed_attempts", "backend_failed_attempt",
                "repair_attempts", "repair_attempt_limit", "transport_complete", "generation_completed", "request_replay_allowed", "logical_continuation_allowed", "usage_source", "output_byte_scope",
                "privacy_policy_hash", "privacy_zdr_required", "privacy_provider_allowlist_count", "privacy_provider_denylist_count", "privacy_provider_order_count", "privacy_fallbacks_allowed", "privacy_observation",
                "backend_terminal_code_hash", "backend_terminal_status", "backend_finish_reason", "backend_status"
            ),
            "TOOL" to commonHashes + setOf("tool_action", "tool_error_code", "tool_reason_code", "tool_dispatch_phase", "unknown_field_count", "tool_name_hash", "tool_action_hash", "tool_schema_hash", "original_error_code_hash", "original_reason_code_hash", "argument_shape_hash", "missing_field_count", "invalid_field_count", "validation_error_count", "invalid_field_classes"),
            "ACCEPTANCE" to commonHashes + criterionFields + setOf("unmet_requirement_count", "unmet_capability_count", "effective_call_limit", "used_calls", "remaining_calls", "verification_reserve", "pending_continuations", "unresolved_outcomes", "unmet_criterion_count", "unmet_criterion_omitted_count", "checkpoint_hash", "acceptance_evidence_scope"),
            "VOICE" to setOf("voice_reply_state", "voice_reply_characters", "voice_session_id_hash", "profile_hash", "model_hash", "voice_generation", "voice_engine", "voice_phase", "voice_capture_attempts", "voice_read_calls", "voice_captured_samples", "voice_nonzero_samples", "voice_recognized_characters", "voice_speech_end_to_transcript_ms", "voice_transcript_to_request_ms", "voice_request_to_response_ms", "voice_response_to_queue_ms", "voice_queue_to_synthesis_ms", "voice_synthesis_to_first_audio_ms", "voice_response_to_first_audio_ms", "voice_warmup_ms", "voice_engine_warm", "voice_permission_granted", "voice_foreground", "voice_muted", "voice_focus_result", "voice_input_silenced", "voice_first_audio_basis"),
            "GATEWAY" to commonHashes + modelPreparationFields + setOf("checkpoint_hash", "original_error_code_hash", "gateway_state", "transport_complete", "request_replay_allowed", "logical_continuation_allowed"),
            "APP" to commonHashes + setOf("original_error_code_hash", "app_exit_kind", "chat_failure_code_state"),
            "INCIDENT" to commonHashes + setOf("original_error_code_hash", "chat_failure_code_state")
        )
        private val coreFields = mapOf(
            "PROVIDER" to listOf("profile_hash", "model_hash", "configured_output_limit", "output_tokens", "finish_reason", "backend_attempts", "transport_complete"),
            "TOOL" to listOf("tool_name_hash", "tool_action_hash", "tool_schema_hash", "original_error_code_hash", "missing_field_count", "invalid_field_count"),
            "ACCEPTANCE" to listOf("unmet_criterion_id_hashes", "unmet_criterion_categories", "unmet_criterion_states", "effective_call_limit", "used_calls", "remaining_calls", "checkpoint_hash"),
            "VOICE" to listOf("voice_engine", "voice_phase", "voice_capture_attempts", "voice_recognized_characters", "voice_response_to_queue_ms", "voice_synthesis_to_first_audio_ms", "voice_response_to_first_audio_ms"),
            "GATEWAY" to listOf("job_id_hash", "gateway_state", "checkpoint_hash", "transport_complete"),
            "APP" to listOf("original_error_code_hash", "app_exit_kind"),
            "INCIDENT" to emptyList()
        )

        private fun allowedFields(area: String, version: Int) = fieldsByArea.getValue(area) +
            (if (version >= 2 && area == "GATEWAY") gatewayAdmissionFields else emptySet()) +
            (if (version == 3) IssueReportIncidentCapsule.fieldsFor(area) else emptySet())

        private fun requiredFields(area: String, version: Int) = coreFields.getValue(area) +
            (if (version >= 2 && area == "GATEWAY") gatewayAdmissionCore else emptyList()) +
            (if (version == 3) IssueReportIncidentCapsule.coreFields else emptyList())

        /** Unknown/private input is not copied. Explicit area selection is required to opt into context v5. */
        fun fromFields(source: String, fields: Map<String, Any?>, occurredAtMillis: Long): IssueReportDiagnostics? {
            if (source !in IssueReportContext.sources || occurredAtMillis !in 1..9_999_999_999_999L) return null
            val area = string(fields["diagnostic_area"])?.takeIf { it in areas } ?: return null
            val mode = if ("diagnostic_capture_mode" in fields) string(fields["diagnostic_capture_mode"]) ?: return null else "INCIDENT"
            if (mode !in captureModes) return null
            val version = when {
                area == "INCIDENT" || IssueReportIncidentCapsule.fieldsFor(area).any { it in fields } -> 3
                area == "GATEWAY" && gatewayAdmissionFields.any { it in fields } -> 2
                else -> 1
            }
            val allowed = allowedFields(area, version)
            val core = requiredFields(area, version)
            val values = linkedMapOf<String, JsonElement>()
            if (mode != "NO_CAPTURE") {
                allowed.forEach { key ->
                    element(fields[key])?.let { validatedValue(key, it) }?.let { values[key] = it }
                }
            }
            val omitted = mutableListOf<String>()
            if (!alignedCriteria(values)) {
                criterionFields.filter { it in values }.forEach {
                    values.remove(it)
                    omitted.add(it)
                }
            }
            if (version == 3 && !IssueReportIncidentCapsule.aligned(values)) {
                listOf("argument_field_names", "argument_field_types", "resolved_argument_field_names", "resolved_argument_field_types").filter { it in values }.forEach {
                    values.remove(it)
                    omitted.add(it)
                }
            }
            fun snapshot() = IssueReportDiagnostics(version, area, occurredAtMillis, mode, values.toMap(), core.filterNot { it in values }, omitted.toList())
            var result = snapshot()
            // Bound before preview/approval, and say exactly which otherwise valid fields did not fit.
            while (IssueReport.json.encodeToString(serializer(), result).encodeToByteArray().size > MAX_BYTES && values.isNotEmpty()) {
                val optional = if (version == 3) values.filterKeys { it !in IssueReportIncidentCapsule.priorityFields } else values
                val key = (optional.ifEmpty { values }).maxBy { it.key.length + it.value.toString().length }.key
                val criterionSize = (values[key] as? JsonArray)?.size ?: 0
                if (version == 3 && key in criterionFields && criterionSize > 1) {
                    // Keep a useful aligned sample when the capsule consumes space;
                    // never discard every criterion merely to add causal metadata.
                    criterionFields.forEach { field -> (values[field] as? JsonArray)?.let { values[field] = JsonArray(it.dropLast(1)) } }
                    val omittedCount = (values["unmet_criterion_omitted_count"] as? JsonPrimitive)?.intOrNull
                    if (omittedCount != null) values["unmet_criterion_omitted_count"] = JsonPrimitive(omittedCount + 1)
                    result = snapshot()
                    continue
                }
                val shapePair = listOf("argument", "resolved_argument").firstOrNull { key in setOf("${it}_field_names", "${it}_field_types") }
                val remove = when {
                    key in criterionFields -> criterionFields.filter { it in values }
                    shapePair != null -> listOf("${shapePair}_field_names", "${shapePair}_field_types").filter { it in values }
                    else -> listOf(key)
                }
                remove.forEach {
                    values.remove(it)
                    omitted.add(it)
                }
                result = snapshot()
            }
            return result.validated()
        }

        /** Parse without ignoreUnknownKeys so private extension fields cannot bypass an approval or route check. */
        fun fromJson(value: JsonElement?): IssueReportDiagnostics? {
            val objectValue = value as? JsonObject ?: return null
            if (objectValue.toString().encodeToByteArray().size > MAX_BYTES) return null
            val required = setOf("schemaVersion", "area", "capturedAtMillis", "captureMode", "values", "unavailableFields")
            if (!objectValue.keys.containsAll(required) || (objectValue.keys - required - "omittedFields").isNotEmpty()) return null
            fun strings(key: String): List<String>? = (objectValue[key] as? JsonArray)?.map { string(it) ?: return null }
            return IssueReportDiagnostics(
                schemaVersion = (objectValue["schemaVersion"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return null,
                area = string(objectValue["area"]) ?: return null,
                capturedAtMillis = (objectValue["capturedAtMillis"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return null,
                captureMode = string(objectValue["captureMode"]) ?: return null,
                values = objectValue["values"] as? JsonObject ?: return null,
                unavailableFields = strings("unavailableFields") ?: return null,
                omittedFields = if ("omittedFields" in objectValue) strings("omittedFields") ?: return null else emptyList()
            ).validated()
        }

        private fun alignedCriteria(values: Map<String, JsonElement>): Boolean =
            criterionFields.mapNotNull { (values[it] as? JsonArray)?.size }.distinct().size <= 1

        private fun validatedValue(key: String, value: JsonElement): JsonElement? {
            IssueReportIncidentCapsule.validValue(key, value)?.let { return it }
            if (key in hashLists || key in enumLists) {
                val items = value as? JsonArray ?: return null
                if (items.size !in 1..MAX_CRITERIA) return null
                val copied = items.map { item ->
                    val text = string(item) ?: return null
                    if (key in hashLists && !hashPattern.matches(text) || key in enumLists && text !in enumLists.getValue(key)) return null
                    JsonPrimitive(text)
                }
                return JsonArray(copied)
            }
            val primitive = value as? JsonPrimitive ?: return null
            if (key == "gateway_version") return string(primitive)?.takeIf { it.matches(Regex("[0-9]{1,4}(?:\\.[0-9]{1,4}){1,3}")) }?.let(::JsonPrimitive)
            if (key in hashFields) return string(primitive)?.takeIf(hashPattern::matches)?.let(::JsonPrimitive)
            enums[key]?.let { allowed -> return string(primitive)?.takeIf { it in allowed }?.let(::JsonPrimitive) }
            if (key in flags) return primitive.takeUnless { it.isString }?.booleanOrNull?.let(::JsonPrimitive)
            val number = primitive.takeUnless { it.isString }?.longOrNull ?: return null
            val range = when (key) {
                "voice_generation" -> 0..Long.MAX_VALUE
                "backend_terminal_status", "gateway_http_status", "gateway_upstream_status", "gateway_reconciliation_status" -> 100L..599L
                "gateway_protocol_version" -> 1L..1000L
                "model_preparation_load_requests" -> 0L..1L
                in attempts -> 0L..10_000L
                in durations -> 0L..86_400_000L
                in counts -> 0L..16_000_000L
                else -> return null
            }
            return number.takeIf { it in range }?.let(::JsonPrimitive)
        }

        private fun string(value: Any?): String? = when (value) {
            is String -> value.takeIf { it.length <= 128 }
            is JsonPrimitive -> value.takeIf { it.isString }?.contentOrNull?.takeIf { it.length <= 128 }
            else -> null
        }

        private fun element(value: Any?): JsonElement? = when (value) {
            is JsonElement -> value
            is String -> value.takeIf { it.length <= 128 }?.let(::JsonPrimitive)
            is Boolean -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is List<*> -> value.takeIf { it.size <= MAX_CRITERIA }?.let { JsonArray(it.map { item -> string(item)?.let(::JsonPrimitive) ?: return null }) }
            else -> null
        }
    }
}
