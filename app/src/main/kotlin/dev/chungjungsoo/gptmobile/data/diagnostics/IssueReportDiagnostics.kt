package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.github.GatewayInputRejectionDetails
import dev.chungjungsoo.gptmobile.data.github.GitHubAcceptanceExplanation
import dev.chungjungsoo.gptmobile.data.github.GitHubAcceptancePublicationEvidence
import dev.chungjungsoo.gptmobile.data.github.GitHubAcceptanceRecoveryEvidence
import dev.chungjungsoo.gptmobile.data.github.GitHubCriterionBlocker
import dev.chungjungsoo.gptmobile.data.github.GitHubExecutionLimits
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCapabilityFailureEvidence
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
        if (values.keys.any { it in composerSlotFields } && omittedFields.any { it in composerSlotFields }) return null
        if (area != "INCIDENT" && values["incident_capture_site"] == JsonPrimitive("CHAT_FLOW_EMPTY_ANSWER")) return null
        if (captureMode == "NO_CAPTURE" && (values.isNotEmpty() || omittedFields.isNotEmpty())) return null
        val copied = linkedMapOf<String, JsonElement>()
        values.forEach { (key, value) -> copied[key] = validatedValue(key, value) ?: return null }
        if (!alignedCriteria(copied) || !alignedComposer(copied) || !GatewayCapabilityFailureEvidence.valid(copied) || !PrivacyFinalRequestFailureEvidence.valid(copied) || schemaVersion == 3 && !IssueReportIncidentCapsule.aligned(copied)) return null
        val expectedUnavailable = requiredFields(area, schemaVersion).filterNot { it in copied }
        val optionalUnavailable = (reasoningProbeFields + IssueReportIncidentCapsule.policyResponseFields + IssueReportIncidentCapsule.repeatHistoryFields).intersect(allowed) - copied.keys
        if (!unavailableFields.containsAll(expectedUnavailable) || unavailableFields.any { it !in expectedUnavailable && it !in optionalUnavailable } || unavailableFields.distinct().size != unavailableFields.size) return null
        val snapshot = copy(values = copied.toMap(), unavailableFields = unavailableFields.toList(), omittedFields = omittedFields.toList())
        return snapshot.takeIf { IssueReport.json.encodeToString(serializer(), it).encodeToByteArray().size <= MAX_BYTES }
    }

    /** Unreleased protocol 2 extends bounded observations without expanding readiness features. */
    internal fun requiredReporterVersion(): Int =
        if ((values.keys + omittedFields + unavailableFields).any { it in protocolTwoFields } ||
            values["voice_playback_failure"] in setOf(JsonPrimitive("PRONUNCIATION_UNAVAILABLE"), JsonPrimitive("LEXICON_UNAVAILABLE"))
        ) {
            2
        } else {
            0
        }

    internal fun requiredServiceFeatures(): Set<String> = buildSet {
        if ((values.keys + omittedFields + unavailableFields).any { it in ExecutionCompletionDiagnostics.providerBudgetFields } ||
            (values["provider_wait_no_wait_reason"] as? JsonPrimitive)?.contentOrNull in ExecutionCompletionDiagnostics.providerLimitRefusals
        ) {
            add(ExecutionCompletionDiagnostics.PROVIDER_LIMIT_CAUSE_FEATURE)
        }
        if ((values["tool_error_code"] as? JsonPrimitive)?.contentOrNull in RepositoryOperationReportContract.errorCodes ||
            (values["tool_action"] as? JsonPrimitive)?.contentOrNull in RepositoryOperationReportContract.toolActions ||
            (values["tool_reason_code"] as? JsonPrimitive)?.contentOrNull in RepositoryOperationReportContract.toolReasonCodes ||
            (values["tool_dispatch_phase"] as? JsonPrimitive)?.contentOrNull in RepositoryOperationReportContract.toolDispatchPhases ||
            (values["provider_wait_no_wait_reason"] as? JsonPrimitive)?.contentOrNull in RepositoryOperationReportContract.providerWaitNoWaitReasons
        ) {
            add(RepositoryOperationReportContract.FEATURE)
        }
        if ((values.keys + omittedFields + unavailableFields).any { it in ExecutionCompletionDiagnostics.newFields || it in ExecutionCompletionDiagnostics.deadlineFields } ||
            (values["provider_wait_decision_state"] as? JsonPrimitive)?.contentOrNull in ExecutionCompletionDiagnostics.newWaitStates ||
            area == "INCIDENT" &&
            "provider_wait_decision_state" in values.keys + omittedFields ||
            values["tool_reason_code"] == JsonPrimitive("GITHUB_SEARCH_FUZZY_QUERY_INVALID")
        ) {
            add(ExecutionCompletionDiagnostics.FEATURE)
        }
        if ((values["no_output_stop_reason"] as? JsonPrimitive)?.contentOrNull in ExecutionCompletionDiagnostics.visibleOutputStopReasons) {
            add(ExecutionCompletionDiagnostics.VISIBLE_OUTPUT_RECOVERY_FEATURE)
        }
        if ((values.keys + omittedFields + unavailableFields).any { it in IssueReportIncidentCapsule.conversationRecoveryFields } ||
            values["incident_capture_site"] in setOf(JsonPrimitive("GITHUB_VERIFICATION_CATCH"), JsonPrimitive("OUTPUT_CONTINUATION_OPEN_CATCH")) ||
            values["catalog_selector_state"] == JsonPrimitive("NAMES_NULL") ||
            (values["provider_failure_code"] as? JsonPrimitive)?.contentOrNull in dev.chungjungsoo.gptmobile.data.agent.ProviderRecoveryFailure.CODES + setOf("GITHUB_VERIFICATION_INTERNAL_ERROR", "OUTPUT_CONTINUATION_UNAVAILABLE") ||
            listOf("argument_field_names", "resolved_argument_field_names", "validation_field_names").any { key ->
                (values[key] as? JsonArray).orEmpty().any { it in setOf(JsonPrimitive("after"), JsonPrimitive("expected_updated_at"), JsonPrimitive("names"), JsonPrimitive("inventory_cursor"), JsonPrimitive("inventory_version"), JsonPrimitive("catalog_only")) }
            }
        ) {
            add(IssueReportIncidentCapsule.CONVERSATION_RECOVERY_FEATURE)
        }
        if ((values.keys + omittedFields).any { it in dev.chungjungsoo.gptmobile.data.network.RequestByteBudget.fields } ||
            values["provider_failure_code"] == JsonPrimitive(dev.chungjungsoo.gptmobile.data.network.RequestByteBudget.CODE)
        ) {
            add(dev.chungjungsoo.gptmobile.data.network.RequestByteBudget.FEATURE)
        }
        if ((values.keys + omittedFields).any { it in incidentDetailFields }) add(INCIDENT_DETAIL_FEATURE)
        if ((values.keys + omittedFields + unavailableFields).any { it in IssueReportIncidentCapsule.tlsTransportPhaseFields }) add(IssueReportIncidentCapsule.TLS_TRANSPORT_PHASE_FEATURE)
        if (values["tool_reason_code"] == JsonPrimitive("EVIDENCE_REVISION_MISMATCH")) add(GITHUB_READ_REVISION_FEATURE)
        if ("claim_extraction_schema_hash" in values.keys + omittedFields + unavailableFields) add("CLAIM_EXTRACTION_SCHEMA_HASH_V1")
        if ((values.keys + omittedFields + unavailableFields).any { it in GatewayCapabilityFailureEvidence.fields } ||
            (values["provider_failure_code"] as? JsonPrimitive)?.contentOrNull in GatewayCapabilityFailureEvidence.codes
        ) {
            add(GatewayCapabilityFailureEvidence.FEATURE)
        }
        if ((values.keys + omittedFields + unavailableFields).any { it in PrivacyFailureEvidence.fields } ||
            listOf("provider_failure_code", "causal_failure_code", "voice_chat_failure_code").any { values[it] == JsonPrimitive(PrivacyFailureEvidence.CODE) }
        ) {
            add(PrivacyFailureEvidence.FEATURE)
        }
        if ((values.keys + omittedFields + unavailableFields).any { it in PrivacyFinalRequestFailureEvidence.fields } ||
            listOf("provider_failure_code", "causal_failure_code", "voice_chat_failure_code").any { values[it] == JsonPrimitive(PrivacyFinalRequestFailureEvidence.CODE) }
        ) {
            add(PrivacyFinalRequestFailureEvidence.FEATURE)
        }
        if ((values.keys + omittedFields + unavailableFields).any { it in IssueReportIncidentCapsule.policyResponseFields }) add(IssueReportIncidentCapsule.GITHUB_POLICY_RESPONSE_FEATURE)
        if ((values.keys + omittedFields + unavailableFields).any { it in IssueReportIncidentCapsule.repeatHistoryFields }) add(IssueReportIncidentCapsule.REPEAT_HISTORY_FEATURE)
        if ("semantic_ci_rules" in values || "semantic_ci_rules" in omittedFields) add(IssueReportIncidentCapsule.GITHUB_CI_SEMANTIC_FEATURE)
        if ("search_validation_field_names" in values || "search_validation_field_names" in omittedFields) add(IssueReportIncidentCapsule.GITHUB_SEARCH_VALIDATION_FEATURE)
        if ((values.keys + omittedFields + unavailableFields).any { it in IssueReportIncidentCapsule.searchArgumentFields }) add(IssueReportIncidentCapsule.GITHUB_SEARCH_ARGUMENT_FEATURE)
        if ((values.keys + omittedFields).any { it in IssueReportIncidentCapsule.inventoryPaginationFields }) add(IssueReportIncidentCapsule.TOOL_INVENTORY_PAGINATION_FEATURE)
        if ((values.keys + omittedFields).any { it in IssueReportIncidentCapsule.remotePaginationFields || it == "provider_limit_source" || it == "provider_cooldown_scope_hash" }) {
            add(PAGINATION_AND_PROVIDER_LIMIT_FEATURE)
        }
        if ((values.keys + omittedFields).any { it in IssueReportIncidentCapsule.prContextRaceFields }) add(IssueReportIncidentCapsule.GITHUB_PR_CONTEXT_RACE_FEATURE)
        if ((values.keys + omittedFields).any { it in IssueReportIncidentCapsule.repeatAdmissionFields }) add(IssueReportIncidentCapsule.REPEAT_ADMISSION_FEATURE)
        if ((values.keys + omittedFields).any { it in IssueReportIncidentCapsule.toolEventPersistenceFields }) add(IssueReportIncidentCapsule.TOOL_EVENT_PERSISTENCE_FEATURE)
        if ("sqlite_constraint_kind" in values || "sqlite_constraint_kind" in omittedFields) add(IssueReportIncidentCapsule.SQLITE_CONSTRAINT_FEATURE)
        if ((values.keys + omittedFields).any { it in GitHubAcceptancePublicationEvidence.observationFields || it in IssueReportIncidentCapsule.reviewComparisonFields }) add(GitHubAcceptancePublicationEvidence.OBSERVATION_FEATURE)
        if ((values.keys + omittedFields).any { it in IssueReportIncidentCapsule.handoffReconciliationFields }) add("HANDOFF_RECONCILIATION_EVIDENCE_V1")
        if ((values.keys + omittedFields).any { it in IssueReportIncidentCapsule.chatDeliveryFields } || values["incident_capture_site"] == JsonPrimitive("CHAT_FLOW_EMPTY_ANSWER")) add("CHAT_DELIVERY_EVIDENCE_V1")
        if ((values.keys + omittedFields).any { it in IssueReportIncidentCapsule.claimFreshnessFields }) add("CLAIM_FRESHNESS_EVIDENCE_V1")
        if ((values["incident_capture_site"] as? JsonPrimitive)?.contentOrNull in setOf("PROVIDER_ADAPTER_STREAM_CATCH", "CHAT_REPOSITORY_ERROR_VALUE")) {
            add("CAPTURE_BOUNDARY_EVIDENCE_V1")
        }
        if ((values.keys + omittedFields).any { it in githubSchemaBudgetFields } ||
            listOf("provider_failure_code", "causal_failure_code", "voice_chat_failure_code").any { values[it] == JsonPrimitive("GITHUB_TOOL_CONTEXT_BUDGET") }
        ) {
            add("GITHUB_SCHEMA_BUDGET_EVIDENCE_V1")
        }
        if ((values.keys + omittedFields).any { it in profileSelectionFields } ||
            listOf("provider_failure_code", "causal_failure_code", "voice_chat_failure_code").any { values[it] == JsonPrimitive("GITHUB_PROFILE_LOCKED") }
        ) {
            add("PROFILE_SELECTION_EVIDENCE_V1")
        }
        if ((values.keys + omittedFields).any { it in githubCooldownFields } ||
            listOf("provider_failure_code", "causal_failure_code", "voice_chat_failure_code", "tool_error_code").any { values[it] == JsonPrimitive("GITHUB_COOLDOWN_ACTIVE") } ||
            values["provider_failure_code"] in setOf(JsonPrimitive("GITHUB_PRIMARY_RATE_LIMITED"), JsonPrimitive("GITHUB_SECONDARY_RATE_LIMITED"))
        ) {
            add("GH_COOLDOWN_V1")
        }
        if ((values.keys + omittedFields).any { it in capacityEvidenceFields }) add("CAPACITY_ADMISSION_EVIDENCE_V1")
        if ((values.keys + omittedFields).any { it in conflictEvidenceFields } || values["conflict_pending_tool_count"] == JsonPrimitive(0)) add("CONVERSATION_CONFLICT_EVIDENCE_V1")
        if ((values.keys + omittedFields).any { it in acceptanceScopeFields }) add("ACCEPTANCE_SCOPE_EVIDENCE_V1")
        if ((values.keys + omittedFields).any { it in githubPrerequisiteFields }) add("GITHUB_PREREQUISITE_EVIDENCE_V1")
        if ("status_missing_observations" in values || "status_missing_observations" in omittedFields) add("STATUS_COVERAGE_EVIDENCE_V1")
        if ((values.keys + omittedFields).any { it in GitHubAcceptanceRecoveryEvidence.fields }) add("ACCEPTANCE_RECOVERY_EVIDENCE_V1")
        if ((values.keys + omittedFields).any { it in GitHubAcceptancePublicationEvidence.fields }) add(GitHubAcceptancePublicationEvidence.FEATURE)
        if ((values.keys + omittedFields).any { it in GitHubAcceptanceExplanation.fields }) add(GitHubAcceptanceExplanation.FEATURE)
        if ((values.keys + omittedFields).any { it in graphPolicyFields } ||
            values["status_policy_recovery"] == JsonPrimitive("CHECK_RULES_READ_PERMISSION") ||
            (values["unmet_criterion_blockers"] as? JsonArray).orEmpty().any { (it as? JsonPrimitive)?.contentOrNull in graphPolicyBlockers }
        ) {
            add("GITHUB_GRAPH_POLICY_EVIDENCE_V1")
        }
        if ((values.keys + omittedFields).any { it in voiceInstallEvidenceFields } || values["voice_install_failure"] == JsonPrimitive("WORK_NOT_FOUND")) add("VOICE_INSTALL_EVIDENCE_V1")
        if ((values.keys + omittedFields).any { it in VoiceExecutionDiagnostics.fields }) add(VoiceExecutionDiagnostics.FEATURE)
        if (VoiceExecutionDiagnostics.requiresStreamingBackendSupport(values)) add(VoiceExecutionDiagnostics.STREAMING_BACKEND_FEATURE)
        if ((values.keys + omittedFields + unavailableFields).any { it == "claim_extraction_validation_stage" || it == "github_prerequisite_observation" }) add("GITHUB_VALIDATION_CONTEXT_V1")
        if ("voice_playback_audio_mode" in values || "voice_playback_audio_mode" in omittedFields) add("AUDIO_FOCUS_MODE_V1")
        if ((values.keys + omittedFields).any { it in voiceCapturePlaybackFields || it in voicePackStateFields }) add("VOICE_CAPTURE_PLAYBACK_EVIDENCE_V1")
        if ((values.keys + omittedFields).any { it in composerEvidenceFields }) add("COMPOSER_IME_EVIDENCE_V1")
        if ((values.keys + omittedFields).any { it in completionEvidenceFields } || values["provider_failure_code"] == JsonPrimitive("TOOL_PROTOCOL_INVALID_FRAME")) add("COMPLETION_EVIDENCE_V1")
        if ((values.keys + omittedFields).any { it in setOf("tool_protocol_frame_kind", "tool_protocol_recovery_outcome") }) add("TOOL_PROTOCOL_FRAME_DIAGNOSTICS_V1")
        if (values["voice_phase"] == JsonPrimitive("PREPARING")) add("VOICE_PLAYBACK_PHASE_V1")
        if ((values.keys + omittedFields).any { it in admissionFields }) add("ADMISSION_DIAGNOSTICS_V1")
        if ((values.keys + omittedFields).any { it in reconciliationFields } || (values["provider_failure_code"] as? JsonPrimitive)?.contentOrNull in reconciliationCodes) {
            add("SESSION_RECOVERY_DIAGNOSTICS_V1")
        }
        if ((values["changed_policy_fields"] as? JsonArray).orEmpty().any { (it as? JsonPrimitive)?.contentOrNull?.startsWith("githubProfileOverride.") == true }) add("ISSUE_RECOVERY_DETAILS_V1")
        if ((values.keys + omittedFields).any { it in issueRecoveryFields } || values["tool_reason_code"] == JsonPrimitive("SAVED_VIEW_CONTEXT_MISMATCH")) add("ISSUE_RECOVERY_DETAILS_V1")
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
        if ("url_reader_availability" in values || "url_reader_availability" in omittedFields) {
            add(IssueReportIncidentCapsule.URL_READER_AVAILABILITY_FEATURE)
        }
        if ((values.keys + omittedFields).any { it in recoveryObservationFields } ||
            (values["saved_view_restore_state"] as? JsonPrimitive)?.contentOrNull in setOf("BODY_MISSING", "BODY_MISSING_INDEX_PRESENT", "BODY_UNREADABLE", "CURSOR_INDEX_MISSING", "CURSOR_INDEX_INVALID", "INVALID_VIEW_ID") ||
            "saved_view_restore_state" in omittedFields ||
            values["tool_error_code"] == JsonPrimitive("DNS_RESOLUTION_FAILED")
        ) {
            add("RECOVERY_OBSERVATIONS_V1")
        }
        if (area == "GATEWAY" && (values.keys + omittedFields).any { it in modelPreparationFields }) add(MODEL_PREPARATION_FEATURE)
        if (area == "PROVIDER") {
            val selectedModelFailure = (values["provider_failure_code"] as? JsonPrimitive)?.contentOrNull in selectedModelFailureCodes
            if (selectedModelFailure || (values.keys + omittedFields).any { it in selectedModelObservationFields }) add(SELECTED_MODEL_ADMISSION_FEATURE)
        }
        if ((values["github_prerequisite_observation"] as? JsonPrimitive)?.contentOrNull in IssueReportIncidentCapsule.reviewCatalogObservations) add(REVIEW_CATALOG_FEATURE)
        if ("app_search_readiness" in values.keys + omittedFields) add(SEARCH_READINESS_FEATURE)
        if ((values.keys + omittedFields).any { it in silencedInputFields }) add(SILENCED_INPUT_FEATURE)
        if ((values.keys + omittedFields).any { it in replayBudgetFields }) add("REPLAY_BUDGET_EVIDENCE_V1")
        if ("pending_saved_page_count" in values.keys + omittedFields || values["replay_budget_state"] == JsonPrimitive("REQUIRED_SAVED_PAGE_NOT_PRESENT")) add(SAVED_PAGE_REPLAY_FEATURE)
        if ("graph_transport_kind" in values || "graph_transport_kind" in omittedFields) add("GRAPH_TRANSPORT_EVIDENCE_V1")
        if ((values.keys + omittedFields).any { it in graphLocalFields } || values["incident_capture_site"] == JsonPrimitive("REPOSITORY_GRAPH_CATCH")) add("GRAPH_LOCAL_CONTEXT_FAILURE_V1")
        if (values["combined_repair_stop"] == JsonPrimitive("SINGLE_PASS_LOCAL_CHECKS_ONLY")) add(COMBINED_SINGLE_PASS_FEATURE)
        if ((values.keys + omittedFields).any { it in readerRunFields } || values["incident_capture_site"] == JsonPrimitive("AGENT_ROUND_CATCH")) add("READER_AND_RUN_FAILURE_DETAILS_V1")
        if ((values.keys + omittedFields).any { it in searchFailureFields || it in taskTimingFields }) add("TASK_AND_TOOL_FAILURE_DETAILS_V1")
        if (area == "TOOL" && ((values.keys + omittedFields).any { it in actionOnlyFields } || values["tool_action"] == JsonPrimitive("stage"))) add(ACTION_ONLY_FEATURE)
        if (IssueReportIncidentCapsule.LAST_UNSENT_CODE in values.keys + omittedFields + unavailableFields) add(REPEAT_PRIOR_CAUSE_FEATURE)
        if ((values["changed_policy_fields"] as? JsonArray).orEmpty().any { (it as? JsonPrimitive)?.contentOrNull?.startsWith("profileBehavior.") == true }) add(PROFILE_BEHAVIOR_FEATURE)
        if ((values.keys + omittedFields).any { it in failureDetailFields }) add("INCIDENT_FAILURE_DETAILS_V1")
        if ((values.keys + omittedFields).any { it in IssueReportIncidentCapsule.dispatchPolicyFields }) add("DISPATCH_POLICY_DIAGNOSTICS_V1")
        if ((values.keys + omittedFields).any { it in IssueReportIncidentCapsule.policyDiagnostics }) add("CHANGED_POLICY_FIELDS_V1")
        if ((values.keys + omittedFields).any { it == "tool_budget_bytes_available" || it in executionFields && (area != "GATEWAY" || it !in modelPreparationFields) } ||
            values["provider_rejection_category"] == JsonPrimitive("BILLING_UNAVAILABLE")
        ) {
            add("EXECUTION_OUTCOME_DIAGNOSTICS_V1")
        }
    }

    companion object {
        const val INCIDENT_DETAIL_FEATURE = "RUNTIME_INCIDENT_DETAILS_V1"
        internal val researchCriteria = setOf("WRITER_INCOMPLETE", "NO_ACQUISITION_PACKET", "NO_READABLE_SOURCES", "DELIVERY_VERIFICATION", "EMPTY_REPORT", "UNKNOWN_CITATION", "PARTIAL_COVERAGE_NOT_DISCLOSED", "REVIEW_STATUS_NOT_DISCLOSED", "MISSING_SECTION", "OMITTED_LEADING_STUDY", "OMITTED_UNREAD_LEAD", "UNSOURCED_SCIENTIFIC_PARAGRAPH", "METADATA_PRESENTED_AS_READ", "NUMERICAL_CLAIM_NOT_IN_CITED_PASSAGE", "UNREGISTERED_REFERENCE_URL", "DIRECT_REFERENCE_MISSING", "QUESTION_EVIDENCE_NOT_CITED", "QUESTION_EVIDENCE_UNCOVERED", "QUESTION_EVIDENCE_METADATA_REQUIRED", "EVIDENCE_CLAIM_NOT_COVERED", "COUNTEREVIDENCE_NOT_DISCLOSED")
        private val researchEvidenceFields = setOf("research_delivery_criterion", "research_unread_sources", "research_read_sources", "research_blocked_sources", "research_blocker_receipt_hash")
        internal val requiredSchemaDetailFields = setOf("required_schema_count", "required_catalog_schema_tokens", "required_recovery_schema_tokens", "required_github_operation_tokens")
        private val incidentDetailsByArea = mapOf(
            "PROVIDER" to requiredSchemaDetailFields + setOf("dispatch_gateway_selection_change", "reasoning_probe_enable_thinking", "reasoning_probe_thinking", "provider_wait_decision_state"),
            "APP" to setOf("native_trace_reason", "native_trace_format", "native_trace_frames", "native_trace_truncated", "native_signal", "native_top_frame_hash", "anr_trace_state", "anr_frame_count"),
            "INCIDENT" to researchEvidenceFields + setOf("dispatch_gateway_selection_change", "failure_boundary"),
            "TOOL" to setOf("dispatch_gateway_selection_change", "read_handle_kind", "search_query_length", "search_explicit_mode", "catalog_revision_hash", "catalog_rejection_count", "catalog_selector_count"),
            "ACCEPTANCE" to setOf("acceptance_verified_claim_count", "acceptance_unmeasured_claim_count", "acceptance_missing_pointer_hash", "acceptance_pointer_receipt_hash")
        )
        private val incidentDetailFields = incidentDetailsByArea.values.flatten().toSet()
        private val capacityCeilingFields = setOf(
            "admission_route_context_tokens",
            "admission_configured_context_tokens",
            "admission_catalog_context_tokens",
            "admission_input_ceiling_tokens",
            "admission_output_ceiling_tokens"
        )
        private val capacityEvidenceCounts = capacityCeilingFields + setOf(
            "admission_system_tokens",
            "admission_history_tokens",
            "admission_current_request_tokens",
            "admission_tool_result_tokens",
            "admission_response_schema_tokens",
            "admission_envelope_tokens",
            "admission_capacity_age_ms"
        )
        internal val capacityEvidenceFields = capacityEvidenceCounts + "admission_capacity_provenance"
        internal val githubSchemaBudgetFields = setOf("required_schema_tokens", "available_schema_tokens")
        private val profileSelectionFlags = setOf("github_selection_profile_match", "github_selection_registered_profile", "github_selection_model_match")
        private val profileSelectionHashes = setOf("github_selection_profile_hash", "github_selection_registered_model_hash")
        internal val profileSelectionFields = profileSelectionFlags + profileSelectionHashes
        private val githubCooldownLongs = setOf("github_cooldown_delay_ms", "github_cooldown_deadline_ms", "github_cooldown_observed_at_ms")
        internal val githubCooldownFields = githubCooldownLongs + setOf("github_cooldown_resource", "github_cooldown_basis", "github_cooldown_remaining_requests")
        private val acceptanceScopeFields = setOf("acceptance_capability_scope", "acceptance_pagination_mode")
        private val githubPrerequisiteFields = setOf("repository_graph_observation", "graph_input_blocked_count", "workflow_dependency_observation")
        private val graphPolicyFields = setOf("branch_protection_http_status", "branch_rules_http_status")
        private val graphPolicyBlockers = setOf("GRAPH_INDEX_REQUIRED", "GRAPH_INDEX_UNCONFIRMED", "GRAPH_INPUT_REQUIRED")
        private val conflictEvidenceFields = setOf(
            "conflict_active_job_provenance",
            "conflict_pending_tool_state",
            "conflict_pending_tool_ids_hash",
            "conflict_handoff_sequence",
            "conflict_stream_job_relation"
        )
        private val conflictFields = setOf("conflict_active_job_kind", "conflict_conversation_revision", "conflict_pending_tool_count", "conflict_conversation_hash", "github_cooldown_stop")
        private val transportFields = setOf("github_transport_read_attempts", "github_transport_unanswered_reads", "github_transport_http_responses")
        private val capacityDetailFields = setOf("admission_tool_schema_tokens", "admission_tool_result_reserve_tokens", "admission_attachment_reserve_tokens")
        private val reconciliationFields = conflictFields + transportFields + capacityDetailFields + "acceptance_pending_remote_continuations"
        const val COMBINED_SINGLE_PASS_FEATURE = "COMBINED_SINGLE_PASS_EVIDENCE_V1"
        const val SEARCH_READINESS_FEATURE = "APP_SEARCH_READINESS_V1"
        const val SILENCED_INPUT_FEATURE = "VOICE_SILENCED_INPUT_V1"
        private val silencedInputFields = setOf("voice_silenced_system_mic_muted", "voice_silenced_audio_mode")
        const val ACTION_ONLY_FEATURE = "ACTION_ONLY_TOOL_PRECHECK_V1"
        const val REPEAT_PRIOR_CAUSE_FEATURE = "TOOL_REPEAT_PRIOR_CAUSE_V1"
        const val PROFILE_BEHAVIOR_FEATURE = "PROFILE_BEHAVIOR_CHANGE_V1"
        const val SAVED_PAGE_REPLAY_FEATURE = "SAVED_PAGE_REPLAY_EVIDENCE_V1"
        private val replayBudgetCounts = setOf("replay_allowance", "replay_tokens", "replay_exchange_count", "pending_saved_page_count")
        internal val replayBudgetFields = replayBudgetCounts + "replay_budget_state"
        private val reconciliationEvidenceFields = setOf("reconciliation_source", "reconciliation_tracked_signature_count")
        internal val graphTransportKinds = setOf("DNS", "DISCONNECTED", "ABORTED", "TIMEOUT", "TLS", "UNKNOWN")
        internal val graphLocalPhases = setOf("TREE_SELECTION", "PACKET_CACHE_READ", "SYMBOL_CATALOG", "SOURCE_LOAD", "MANIFEST_RANKING", "SOURCE_GRAPH", "SOURCE_RANGES", "SEMANTIC_RANKING", "PACKET_ASSEMBLY", "PACKET_CACHE_WRITE")
        internal val graphLocalReasons = setOf("LOCAL_INVARIANT_REJECTED", "LOCAL_STATE_INVALID", "LOCAL_RUNTIME_FAILURE")
        private val graphLocalFields = setOf("graph_local_phase", "graph_local_reason")
        private val readerCatalogFields = setOf("url_reader_refusal", "round_catalog_discovery_available")
        private val readerRunFields = reconciliationEvidenceFields + readerCatalogFields
        private val searchAttemptFields = setOf("search_attempt_count", "search_dispatched_attempt_count", "search_budget_rejected_attempt_count")
        private val searchFailureFields = searchAttemptFields + setOf("search_failure_observation", "catalog_selector_state")
        private val taskTimingFields = setOf("task_deadline_check", "task_deadline_observation", "task_deadline_window_seconds", "task_deadline_overdue_seconds", "acceptance_acquisition_allowed", "acceptance_cleanup_reserve", "acceptance_fixture_start_minimum", "acceptance_prerequisite_blockers")
        private val actionOnlyFields = setOf("action_only_tool_family", "action_only_validation")
        const val SELECTED_MODEL_ADMISSION_FEATURE = "SELECTED_MODEL_ADMISSION_V1"
        const val REVIEW_CATALOG_FEATURE = "REVIEW_CATALOG_OBSERVATION_V1"
        internal val selectedModelObservationFields = setOf("gateway_model_state", "gateway_model_advertised", "gateway_model_loaded")
        internal val selectedModelFailureCodes = setOf("SELECTED_MODEL_LOADING", "SELECTED_MODEL_NOT_LOADED", "SELECTED_MODEL_NOT_AVAILABLE")
        private val reconciliationCodes = setOf("CONVERSATION_CONFLICT", "CLIENT_TOOL_RESULTS_REQUIRED", "GITHUB_CIRCUIT_OPEN", "REASONING_OFF_UNSUPPORTED", "REASONING_ON_UNSUPPORTED")
        internal val reasoningProbeFields = setOf("reasoning_probe_outcome", "reasoning_probe_attempts", "reasoning_probe_age_ms", "reasoning_metadata_age_ms")
        private val admissionFields = reasoningProbeFields + setOf("reasoning_capability_age_ms", "admission_prompt_tokens", "admission_safety_tokens", "admission_output_tokens", "admission_capacity_tokens", "admission_dominant_tokens", "queue_active_leases", "queue_pending_deliveries", "queue_unsettled_submissions", "queue_legacy_runs", "queue_cached_active_jobs", "queue_concurrency_limit", "gateway_preflight_blocker_count", "gateway_preflight_candidate_count", "acceptance_approval_blocked_total", "acceptance_input_blocked_total", "acceptance_live_blocked_total", "acceptance_continuation_blocked_total", "admission_measurement", "admission_dominant_component", "reasoning_recovery_action", "reasoning_model_state", "queue_blocker", "gateway_validation_policy_state", "gateway_preflight_reason", "gateway_preflight_blocker_codes", "gateway_preflight_recovery_actions", "gateway_preflight_discovery_state", "gateway_preflight_candidate_count", "status_policy_recovery")
        private val issueRecoveryFields = setOf("voice_install_state", "voice_install_scheduler", "voice_install_failure", "voice_install_stop_reason", "voice_install_progress_percent", "github_transport_kind", "github_transport_wait_ms", "read_range_count", "read_invalid_window_count", "read_window_rejections", "argument_page_size", "reasoning_requested_mode", "reasoning_admission_basis", "reasoning_route_kind", "reasoning_control_state", "reasoning_template_hash", "repository_input_blocked_count", "workflow_input_blocked_count", "live_evidence_blocked_count", "acceptance_failed_operation_count", "acceptance_unresolved_claim_count", "acceptance_claim_failure_reasons")
        private val composerEventFields = (1..8).map { "composer_event_$it" }.toSet()
        private val composerAgeFields = (1..8).map { "composer_event_${it}_age_ms" }.toSet()
        internal val composerSlotFields = composerEventFields + composerAgeFields
        internal val composerTimelineFields = composerSlotFields + setOf("composer_generation", "composer_events_dropped", "composer_enabled", "composer_window_focused", "composer_lifecycle", "composer_input_mode")
        private val composerEvidenceFields = composerTimelineFields + setOf("composer_focus_transitions", "composer_ime_transitions", "composer_focused", "composer_ime_visible")
        private val protocolTwoFields = reasoningProbeFields + composerTimelineFields + VoiceExecutionDiagnostics.fields + setOf("tool_budget_bytes_available", "graph_index_attempt_hash")
        private val composerEvents = setOf("ATTACHED", "DETACHED", "EDITOR_FOCUSED", "EDITOR_UNFOCUSED", "IME_VISIBLE", "IME_HIDDEN", "WINDOW_FOCUSED", "WINDOW_UNFOCUSED", "CHAT_ENABLED", "CHAT_DISABLED", "INPUT_TOUCH", "INPUT_KEYBOARD", "LIFECYCLE_CREATE", "LIFECYCLE_START", "LIFECYCLE_RESUME", "LIFECYCLE_PAUSE", "LIFECYCLE_STOP", "LIFECYCLE_DESTROY", "RELEASE_SEND", "RELEASE_SHORTCUT", "RELEASE_BACKGROUND_TAP", "RELEASE_DISABLED", "RELEASE_LIFECYCLE_STOP", "RELEASE_DISPOSE")
        private val completionEvidenceFields = setOf("empty_completion_observed_attempts", "empty_completion_reasoning_only_attempts")
        private val voicePackStateFields = setOf("voice_install_recognition_status", "voice_install_playback_status")
        private val voiceCapturePlaybackFields = silencedInputFields + setOf("voice_playback_audio_mode", "voice_playback_failure", "voice_playback_focus_attempts", "voice_capture_silenced_stage", "voice_caption_alignment", "voice_focus_last_transition", "voice_focus_transition_count", "voice_pronunciation_unknown_words", "voice_pronunciation_unknown_word_length", "voice_pronunciation_initialisms", "voice_pronunciation_failure_kind", "voice_pronunciation_audio_queued", "voice_pronunciation_first_audio_observed")
        private val voiceInstallEvidenceFields = setOf(
            "voice_install_pending_reason",
            "voice_install_elapsed_ms",
            "voice_install_pack",
            "voice_install_transfer_stage",
            "voice_install_failure_kind",
            "voice_install_http_status",
            "voice_install_downloaded_bytes",
            "voice_install_total_bytes"
        )

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
        val failureDetailFields = setOf("provider_failure_stage", "provider_failure_domain", "provider_failure_code", "voice_reply_state", "voice_reply_characters", "tool_action", "tool_error_code", "tool_reason_code", "tool_dispatch_phase", "unknown_field_count", "chat_failure_code_state", "provider_error_code_state", "provider_error_origin", "sqlite_constraint_kind", "provider_wait_eligible", "provider_checkpoint_present", "provider_checkpoint_continuation_allowed", "provider_consecutive_rejections", "tool_protocol_frame_kind", "provider_limit_source", "provider_cooldown_scope_hash", "unmet_requirement_count", "unmet_capability_count")
        private val newEvidenceFieldNames = setOf("end_line", "max_lines", "start_offset", "ranges", "evidence_id").map(::JsonPrimitive).toSet()
        const val MAX_BYTES = 3072
        const val MAX_CRITERIA = 8
        const val PAGINATION_AND_PROVIDER_LIMIT_FEATURE = "PAGINATION_AND_PROVIDER_LIMIT_EVIDENCE_V1"
        const val GITHUB_READ_REVISION_FEATURE = "GITHUB_READ_REVISION_EVIDENCE_V1"
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
        private val hashFields = commonHashes + profileSelectionHashes + setOf(
            "native_top_frame_hash", "acceptance_missing_pointer_hash", "acceptance_pointer_receipt_hash", "research_blocker_receipt_hash", "catalog_revision_hash",
            "graph_index_attempt_hash",
            "reasoning_template_hash", "conflict_conversation_hash", "conflict_pending_tool_ids_hash",
            "voice_session_id_hash", "original_error_code_hash", "original_reason_code_hash", "server_request_id_hash", "generation_id_hash", "provider_cooldown_scope_hash",
            "privacy_policy_hash", "backend_terminal_code_hash", "tool_name_hash", "tool_action_hash", "tool_schema_hash", "argument_shape_hash", "checkpoint_hash", "gateway_manifest_hash", "gateway_operation_id_hash", "operation_id_hash"
        )
        private val hashLists = setOf("unmet_criterion_id_hashes", "unmet_criterion_reason_hashes")
        private val flags = profileSelectionFlags + setOf("payload_replan_allowed", "task_deadline_renewed") + setOf(
            "gateway_model_advertised", "gateway_model_loaded",
            "native_trace_truncated", "search_explicit_mode", "round_catalog_discovery_available",
            "acceptance_fixture_creation_admissible", "acceptance_acquisition_allowed", "status_branch_protected", "status_policy_complete",
            "provider_wait_eligible", "provider_checkpoint_present", "provider_checkpoint_continuation_allowed",
            "transport_complete", "generation_completed", "request_replay_allowed", "logical_continuation_allowed", "privacy_zdr_required", "privacy_fallbacks_allowed",
            "composer_enabled", "composer_window_focused", "composer_focused", "composer_ime_visible", "voice_engine_warm", "voice_permission_granted", "voice_foreground", "voice_muted", "voice_input_silenced", "voice_silenced_system_mic_muted", "voice_pronunciation_audio_queued", "voice_pronunciation_first_audio_observed", "gateway_admission_ready", "model_preparation_load_accepted"
        )
        private val attempts = completionEvidenceFields + setOf("backend_attempts", "backend_completed_attempts", "backend_failed_attempt", "repair_attempts", "repair_attempt_limit")
        private val durations = setOf(
            "reasoning_probe_age_ms", "reasoning_metadata_age_ms",
            "github_transport_wait_ms",
            "voice_speech_end_to_transcript_ms",
            "voice_transcript_to_request_ms",
            "voice_request_to_response_ms",
            "voice_response_to_queue_ms",
            "voice_queue_to_synthesis_ms",
            "voice_synthesis_to_first_audio_ms",
            "voice_response_to_first_audio_ms",
            "voice_warmup_ms"
        )
        private val counts = replayBudgetCounts + capacityEvidenceCounts + githubSchemaBudgetFields + requiredSchemaDetailFields + setOf(
            "research_unread_sources", "research_read_sources", "research_blocked_sources", "catalog_rejection_count", "catalog_selector_count",
            "search_query_length", "acceptance_verified_claim_count", "acceptance_unmeasured_claim_count",
            "tool_budget_bytes_available",
            "github_cooldown_remaining_requests",
            "reasoning_capability_age_ms", "admission_prompt_tokens", "admission_safety_tokens", "admission_output_tokens", "admission_capacity_tokens", "admission_dominant_tokens", "queue_active_leases", "queue_pending_deliveries", "queue_unsettled_submissions", "queue_legacy_runs", "queue_cached_active_jobs", "queue_concurrency_limit", "gateway_preflight_blocker_count", "gateway_preflight_candidate_count", "acceptance_approval_blocked_total", "acceptance_input_blocked_total", "acceptance_live_blocked_total", "acceptance_continuation_blocked_total",
            "acceptance_unresolved_claim_count", "acceptance_pending_remote_continuations", "conflict_conversation_revision",
            "github_transport_read_attempts", "github_transport_unanswered_reads", "github_transport_http_responses",
            "admission_tool_schema_tokens", "admission_tool_result_reserve_tokens", "admission_attachment_reserve_tokens",
            "voice_install_stop_reason", "voice_install_progress_percent", "voice_install_elapsed_ms", "read_range_count", "read_invalid_window_count", "argument_page_size", "repository_input_blocked_count", "workflow_input_blocked_count", "live_evidence_blocked_count", "acceptance_failed_operation_count",
            "voice_reply_characters",
            "provider_consecutive_rejections", "unmet_requirement_count", "unmet_capability_count", "graph_input_blocked_count",
            "unknown_field_count",
            "configured_output_limit", "effective_output_limit", "backend_output_limit", "input_tokens", "output_tokens", "reasoning_tokens", "visible_output_bytes", "reasoning_output_bytes",
            "privacy_provider_allowlist_count", "privacy_provider_denylist_count", "privacy_provider_order_count", "missing_field_count", "invalid_field_count", "validation_error_count",
            "effective_call_limit", "used_calls", "remaining_calls", "verification_reserve", "pending_continuations", "unresolved_outcomes", "unmet_criterion_count", "unmet_criterion_omitted_count",
            "voice_capture_attempts", "voice_read_calls", "voice_captured_samples", "voice_nonzero_samples", "voice_recognized_characters", "voice_focus_transition_count", "voice_pronunciation_unknown_words", "voice_pronunciation_initialisms"
        )
        private val finishReasons = setOf("STOP", "TOOL_CALLS", "LENGTH", "CONTENT_FILTER", "OTHER", "UNKNOWN")
        private val enums = mapOf(
            "provider_wait_decision_state" to dev.chungjungsoo.gptmobile.data.agent.ProviderWaitSchedulingResult.decisionStates + "NOT_RECORDED",
            "dispatch_gateway_selection_change" to setOf("SELECTED", "CLEARED", "REPLACED"),
            "read_handle_kind" to setOf("SAVED_CURSOR", "NO_HANDLE", "DIGEST_SHAPED", "VERIFICATION_RECEIPT", "NONCANONICAL_HANDLE"),
            "native_trace_format" to setOf("text", "protobuf", "binary", "unknown"),
            "anr_trace_state" to setOf("UNAVAILABLE", "NO_MATCHING_FRAMES", "MAIN_THREAD_CAPTURED"),
            "failure_boundary" to setOf("AFTER_VISIBLE_OUTPUT", "BEFORE_VISIBLE_OUTPUT"),
            "research_delivery_criterion" to researchCriteria,
            "native_trace_reason" to setOf("no_trace_stream", "empty_trace", "trace_read_failed", "trace_access_failed", "unrecognized_binary_tombstone", "no_recognized_tombstone_fields", "text_prefix_limit", "signal_and_library_frames", "malformed_protobuf", "trace_process_mismatch", "trace_limit_before_evidence", "no_recognized_protobuf_fields", "protobuf_prefix_limit", "protobuf_crashed_thread"),
            "native_signal" to setOf("SIGABRT", "SIGSEGV", "SIGBUS", "SIGFPE", "SIGILL", "SIGTRAP", "SIGSYS", "SIGKILL", "SIGQUIT", "SIGTERM", "SIGPIPE", "SIGSTKFLT", "SIGXCPU", "SIGXFSZ"),
            "reasoning_probe_enable_thinking" to dev.chungjungsoo.gptmobile.data.context.reasoningProbeNegatives,
            "reasoning_probe_thinking" to dev.chungjungsoo.gptmobile.data.context.reasoningProbeNegatives,
            "composer_lifecycle" to setOf("INITIALIZED", "CREATED", "STARTED", "RESUMED", "DESTROYED"),
            "composer_input_mode" to setOf("TOUCH", "KEYBOARD"),
            "reasoning_probe_outcome" to setOf("VERIFIED", "IDENTICAL_PROMPTS", "MISSING_THINK_MARKER", "PREFIX_MISMATCH", "SUFFIX_MISMATCH", "INVALID_PROMPT", "METADATA_CHANGED", "PROBE_UNAVAILABLE", "PROBE_DEADLINE", "MIXED_NEGATIVE_RESULTS"),
            "admission_capacity_provenance" to setOf("VERIFIED_ROUTE", "DECLARED", "CONSERVATIVE_POLICY", "UNKNOWN"),
            "conflict_active_job_provenance" to setOf("CONVERSATION_ACTIVE_JOB", "UNREPORTED"),
            "conflict_pending_tool_state" to setOf("IDENTIFIED", "MISSING", "EMPTY", "INVALID", "UNAVAILABLE", "NOT_REPORTED"),
            "conflict_stream_job_relation" to setOf("MATCHES_VERIFIED_STREAM_JOB", "DIFFERS_VERIFIED_STREAM_JOB", "NO_VERIFIED_STREAM_JOB", "ACTIVE_JOB_NOT_REPORTED"),
            "acceptance_capability_scope" to setOf("FULL_CATALOG", "OBSERVED_CAPABILITIES"),
            "reconciliation_source" to setOf("SAVED_AND_TRACKED", "SAVED_UNSETTLED_CALL", "TRACKED_CALL_SIGNATURE"),
            "replay_budget_state" to setOf("NO_INITIAL_ALLOWANCE", "NO_CONTINUATION_ALLOWANCE", "COMPACTED_IDENTITIES_EXCEED_ALLOWANCE", "REQUIRED_SAVED_PAGE_NOT_PRESENT", "SAVED_PAGE_BODY_UNAVAILABLE", "SAVED_PAGE_DELIVERY_MISMATCH"),
            "graph_transport_kind" to graphTransportKinds,
            "graph_local_phase" to graphLocalPhases,
            "graph_local_reason" to graphLocalReasons,
            "url_reader_refusal" to setOf("DOMAIN_SCOPE", "HOP_LIMIT", "LOCATION_MISSING", "LOOP", "LOCATION_INVALID", "HTTP_ACCESS_DENIED", "TURN_DENIAL_CACHE"),
            "search_failure_observation" to setOf("UPSTREAM_CHALLENGE", "NO_ENGINE_SUCCEEDED"),
            "catalog_selector_state" to setOf("MIXED_INVENTORY_SELECTORS", "ACTION_NOT_STRING", "ACTION_WITHOUT_NAMES", "NAMES_NOT_ARRAY", "NAMES_NULL", "NAMES_CONTAIN_NON_STRING", "ACTION_REQUIRES_ONE_NAME", "OTHER_SCHEMA_CONSTRAINT"),
            "task_deadline_check" to GitHubExecutionLimits.DeadlineCheck.entries.map { it.name }.toSet(),
            "task_deadline_observation" to GitHubAcceptanceRecoveryEvidence.deadlineObservations,
            "task_deadline_clock" to setOf("WALL_CLOCK"),
            "acceptance_preparation_state" to GitHubAcceptanceRecoveryEvidence.preparationStates,
            "status_policy_recovery_hint" to GitHubAcceptanceRecoveryEvidence.policyRecoveryHints,
            "acceptance_pagination_mode" to setOf("COMPLETE_INVENTORY_REQUESTED", "BOUNDED_SMOKE_SAMPLE"),
            "repository_graph_observation" to setOf("NOT_INDEXED", "OBSERVED", "UNOBSERVED", "REVISION_MISMATCH", "UNAVAILABLE"),
            "workflow_dependency_observation" to setOf("UNOBSERVED", "READ_FAILED", "OBSERVED_EXECUTED_JOB", "RUN_READ_PENDING", "MISSING_EXECUTED_JOB", "RUN_PAGE_AVAILABLE"),
            "gateway_validation_policy_state" to setOf("NOT_CONFIGURED", "MISSING_OR_OVERSIZED", "REPOSITORY_UNCONFIGURED", "INVALID_DOCUMENT", "INVALID_ENTRY", "UNREADABLE", "VALIDATED"),
            "provider_request_limit_source" to setOf("UNREPORTED", "RESPONSE_HEADER"),
            "admission_measurement" to setOf("SERVING_TOKENIZER", "UTF8_ESTIMATE"),
            "admission_dominant_component" to setOf("tool_schemas", "system_instructions", "retained_history", "tool_results", "current_request", "response_schema", "serialization_and_template_estimate", "attachments"),
            "status_policy_recovery" to setOf("CHECK_PROTECTION_READ_PERMISSION", "CHECK_RULES_READ_PERMISSION"),
            "reasoning_model_state" to setOf("UNKNOWN", "UNAVAILABLE", "UNLOADED", "LOADING", "LOADED", "SLEEPING"),
            "reasoning_recovery_action" to setOf("CHOOSE_AUTO_OR_QUALIFIED_MODEL"),
            "queue_blocker" to setOf("ACTIVE_LEASE", "CHAT_BUSY", "DELIVERY_PENDING", "EXTERNAL_WORK", "GATEWAY_NOT_READY", "MODEL_PREPARATION", "CONFIGURATION", "NONE"),
            "gateway_preflight_reason" to setOf("VALIDATION_POLICY_REQUIRED", "VALIDATION_CHECKS_REQUIRED", "VALIDATION_CHECK_UNSUPPORTED", "VALIDATION_RUNTIME_UNAVAILABLE", "VALIDATION_REVISION_UNAVAILABLE", "SOURCE_REVISION_REQUIRED", "ACCEPTANCE_CONTRACT_REQUIRED", "ACCEPTANCE_PRODUCER_REQUIRED"),
            "gateway_preflight_discovery_state" to setOf("REVIEW_REQUIRED", "DISCOVERY_UNAVAILABLE"),

            "reasoning_route_kind" to setOf("GATEWAY", "DIRECT"),
            "reasoning_control_state" to setOf("VERIFIED", "UNSUPPORTED", "UNVERIFIED"),
            "voice_install_state" to setOf("IDLE", "QUEUED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED", "WAITING_NETWORK"),
            "voice_install_scheduler" to setOf("JOB_SCHEDULER", "WORK_MANAGER"),
            "voice_install_failure" to setOf("SCHEDULE_REJECTED", "SCHEDULE_EXCEPTION", "ASSET_FAILURE", "WORKER_EXCEPTION", "WORK_NOT_FOUND"),
            "voice_install_pending_reason" to setOf("UNDEFINED", "APP_STATE", "APP_STANDBY", "BACKGROUND_RESTRICTION", "CONNECTIVITY", "DEVICE_STATE", "SCHEDULER_OPTIMIZATION", "QUOTA", "USER", "OTHER_CONSTRAINT", "EXECUTING", "MISSING", "UNAVAILABLE"),
            "voice_install_pack" to setOf("MOONSHINE", "KOKORO"),
            "voice_install_transfer_stage" to setOf("CHECKING", "CONNECTING", "TRANSFERRING", "VERIFYING", "EXTRACTING", "READY", "FAILED"),
            "voice_install_failure_kind" to setOf("DNS", "TIMEOUT", "TLS", "HTTP", "NETWORK_IO", "INTEGRITY", "STORAGE", "MODEL_IN_USE", "UNKNOWN"),
            "conflict_active_job_kind" to setOf("generation", "engineering"),
            "github_cooldown_stop" to setOf("WAIT_ALREADY_USED", "WAIT_LIMIT", "TASK_DEADLINE"),
            "github_transport_kind" to setOf("DNS", "DISCONNECTED", "ABORTED", "TIMEOUT", "TLS", "CANCELLED", "UNKNOWN", "CIRCUIT_OPEN", "HTTP"),
            "reasoning_requested_mode" to setOf("AUTO", "ON", "OFF"),
            "reasoning_admission_basis" to setOf("NO_VERIFIED_CONTROL"),

            "provider_failure_stage" to setOf("ADMISSION", "ENGINEERING_GUARD", "GENERATION", "CONTINUATION", "REVIEW_COMPATIBILITY", "ANSWER_REVIEW", "CLAIM_VERIFICATION", "DELIVERY"),
            "provider_failure_domain" to setOf("PROVIDER", "APPROVAL", "APPLICATION_VERIFICATION", "TOOL_EXECUTION", "UNKNOWN"),
            "provider_failure_code" to setOf("GITHUB_TASK_IDENTITY_CHANGED", "PRIVACY_SERVICE_UNVERIFIED") + ExecutionCompletionDiagnostics.codes + GatewayCapabilityFailureEvidence.codes + reconciliationCodes + selectedModelFailureCodes + dev.chungjungsoo.gptmobile.data.agent.ProviderRecoveryFailure.CODES + setOf("GITHUB_VERIFICATION_INTERNAL_ERROR", "OUTPUT_CONTINUATION_UNAVAILABLE", "OUTPUT_CONTINUATION_NO_PROGRESS", "PROVIDER_INVOCATION_FAILED", "BUDGET_RESTRICTED", "GITHUB_COOLDOWN_ACTIVE", "GITHUB_PRIMARY_RATE_LIMITED", "GITHUB_SECONDARY_RATE_LIMITED", "GITHUB_PROFILE_LOCKED", "GITHUB_TOOL_CONTEXT_BUDGET", "TOOL_PROTOCOL_INVALID_FRAME", "HTTP_ERROR", "TRANSPORT_ERROR", "UNCLASSIFIED_PROVIDER_FAILURE", "OUTPUT_LIMIT", "REQUEST_BYTES_EXCEEDED", "CAPACITY_UNVERIFIED", "INPUT_CAPACITY_EXCEEDED", "OUTPUT_CAPACITY_EXCEEDED", "CONTEXT_CAPACITY_EXCEEDED", "LOCAL_SERVICE_UNREACHABLE", "GATEWAY_OUTCOME_UNKNOWN", "TIMEOUT_OUTCOME_UNKNOWN", "RUN_DEADLINE_REACHED", "PROVIDER_RATE_LIMITED", "PRIVACY_CONFIGURATION", PrivacyFailureEvidence.CODE, PrivacyFinalRequestFailureEvidence.CODE),
            GatewayCapabilityFailureEvidence.KIND to GatewayCapabilityFailureEvidence.kinds,
            GatewayCapabilityFailureEvidence.DECODE_REASON to GatewayCapabilityFailureEvidence.decodeReasons,
            "privacy_reason_code" to PrivacyFailureEvidence.reasonCodes,
            "privacy_status" to PrivacyFailureEvidence.statuses,
            PrivacyFinalRequestFailureEvidence.FIELD to PrivacyFinalRequestFailureEvidence.outcomes,
            "github_cooldown_resource" to setOf("CORE", "SEARCH", "GRAPHQL", "OTHER"),
            "github_cooldown_basis" to setOf("SECONDARY_DEADLINE", "RESOURCE_RESET", "RESOURCE_RETRY_AFTER", "RESOURCE_RESET_AND_RETRY_AFTER"),
            "voice_reply_state" to setOf("FAILED_WITHOUT_DETAILS", "EMPTY_REPLY"),
            "action_only_tool_family" to setOf("REPOSITORY_WORKSPACE", "REPOSITORY_GRAPH", "GITHUB_NATIVE", "GITHUB_OPERATION", "OTHER"),
            "action_only_validation" to setOf("NO_RECEIPT", "CALL_BOUND_VALIDATOR", "VALIDATOR_CALL_MISMATCH", "NO_VALIDATOR_ID"),
            "tool_action" to setOf("stage", "agent_merge_status", "agent_merge_enable", "agent_merge_update", "agent_merge_disable", "status", "list", "headers", "context", "comments", "reviews", "threads", "history", "commit", "branch", "changes", "checks", "workflows", "runs", "jobs", "logs", "failure", "read", "search", "create", "update", "close", "reopen", "review", "diff", "summary", "files", "tree", "get", "rerun", "rerun_failed_jobs", "cancel", "dispatch") + RepositoryOperationReportContract.toolActions,
            "tool_error_code" to setOf("PRIVACY_SERVICE_UNVERIFIED", "APP_ERROR", "DNS_RESOLUTION_FAILED", "TOOL_FAILURE", "TOOL_PRECHECK_FAILED", "INVALID_ARGUMENTS", "TOOL_UNAVAILABLE", "EVIDENCE_UNAVAILABLE", "SAVED_VIEW_CURSOR_ALTERED", "SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_UNAVAILABLE", "GITHUB_VALIDATION_FAILED", "GITHUB_PR_HEAD_CHANGED", "HTTP_ERROR", "GITHUB_NOT_FOUND_OR_INACCESSIBLE", "GITHUB_REVIEW_BLOCKED", "PRECHECK_REJECTED", "TOOL_OUTCOME_UNKNOWN", "UNKNOWN_TOOL", "GITHUB_CONFLICT", "GITHUB_COOLDOWN_ACTIVE", "GITHUB_PRIMARY_RATE_LIMITED", "GITHUB_SECONDARY_RATE_LIMITED", "GITHUB_FORBIDDEN_UNKNOWN", "GITHUB_ENDPOINT_PERMISSION_REQUIRED") + RepositoryOperationReportContract.errorCodes,
            "tool_reason_code" to setOf("EVIDENCE_REVISION_MISMATCH", "GITHUB_PATH_NOT_FOUND", "BUDGET_EXHAUSTED", "COMBINED_BUDGET_EXHAUSTED", "TOOL_BATCH_OVERSIZE", "DUPLICATE_INVOCATION", "REPEATED_FAILURE", "BUDGET_CHECKPOINT_UNAVAILABLE", "TOOL_PRECHECK_FAILED", "UNSUPPORTED_TOOL_CONTRACT", "UNSUPPORTED_OPERATION", "UNKNOWN_TOOL", "SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_CURSOR_ALTERED", "SAVED_VIEW_CURSOR_MALFORMED", "TOOL_ARGUMENT_SCHEMA_REJECTED", "TOOL_ARGUMENT_CONSTRAINT_REJECTED", "GITHUB_REPOSITORY_INPUT_REJECTED", "GITHUB_CI_RUN_SELECTOR_REQUIRED", "GITHUB_REPOSITORY_SELECTOR_INCOMPLETE", "GITHUB_SEARCH_QUERY_EMPTY", "GITHUB_SEARCH_QUERY_TOO_LONG", "GITHUB_SEARCH_FUZZY_QUERY_INVALID", "GITHUB_ARGUMENT_PRECHECK_UNCLASSIFIED", "ISSUE_SELECTOR_REQUIRED", "PR_SELECTOR_REQUIRED", "SAVED_VIEW_CONTEXT_MISMATCH", "GRAPH_HTTP_REJECTED", "GRAPH_TRANSPORT_UNAVAILABLE", "GRAPH_TLS_REJECTED", "GRAPH_INDEX_OUTCOME_UNKNOWN", "GRAPH_OPERATION_FAILED", "GRAPH_REQUEST_REJECTED", "GRAPH_REVISION_MISMATCH", "GRAPH_JOB_CHANGED", "GRAPH_JOB_ACK_MISSING", "GRAPH_INTERNAL_STORAGE", "GRAPH_INTERNAL_IO", "GRAPH_INTERNAL_SERVER") + RepositoryOperationReportContract.toolReasonCodes,
            "tool_dispatch_phase" to setOf("PREFLIGHT_REJECTED", "READ_FAILED", "OBSERVED", "OUTCOME_UNKNOWN", "PARTIAL_ACCEPTED", "COMPLETED", "FAILED", "PRECHECK_REJECTED") + RepositoryOperationReportContract.toolDispatchPhases,
            "chat_failure_code_state" to setOf("ABSENT", "KNOWN", "UNRECOGNIZED"),
            // These states come from the actual OpenAI-compatible stream error
            // value; never expose its untrusted message or arbitrary code.
            "provider_error_code_state" to setOf("ABSENT"),
            "provider_error_origin" to setOf("OPENAI_COMPAT_STREAM_ERROR_VALUE"),
            "tool_protocol_frame_kind" to setOf("MISSING_FUNCTION_NAME", "UNFINISHED_STREAM", "NON_OBJECT_ARGUMENTS", "MALFORMED_JSON"),
            "tool_protocol_recovery_outcome" to setOf("TEXT_FALLBACK_EXHAUSTED", "TOOL_RETRY_EXHAUSTED", "BACKEND_HANDLED_TOOLS", "SINGLE_PASS_NOT_RETRYABLE", "TEXT_ALREADY_EMITTED", "NO_SAFE_REPAIR"),
            "provider_limit_source" to setOf("upstream_provider_shared_pool", "openrouter_free_tier_per_minute", "openrouter_free_tier_daily"),

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
            "voice_engine" to setOf("SYSTEM", "LOCAL", "KOKORO", "MEDIAPIPE"),
            "voice_phase" to setOf("PAUSED", "STARTING", "LISTENING", "FINALIZING", "REVIEWING", "THINKING", "PREPARING", "SPEAKING", "ERROR", "CLOSED"),
            "voice_playback_failure" to VoiceExecutionDiagnostics.playbackFailures,
            "voice_install_recognition_status" to setOf("CHECKING", "NOT_INSTALLED", "DOWNLOADING", "READY", "FAILED"),
            "voice_install_playback_status" to setOf("CHECKING", "NOT_INSTALLED", "DOWNLOADING", "READY", "FAILED"),
            "voice_pronunciation_failure_kind" to setOf("UNKNOWN_WORD", "UNSUPPORTED_SCRIPT", "MISSING_LETTER"),
            "voice_silenced_audio_mode" to setOf("NORMAL", "RINGTONE", "IN_CALL", "IN_COMMUNICATION", "OTHER"),
            "voice_capture_silenced_stage" to setOf("CAPTURE_START", "CAPTURE_READ"),
            "voice_caption_alignment" to setOf("NO_TIMING", "EXACT_RANGE", "TEXT_UNMAPPED"),
            "voice_playback_audio_mode" to setOf("NORMAL", "RINGTONE", "IN_CALL", "IN_COMMUNICATION", "OTHER"),
            "voice_focus_result" to setOf("GRANTED", "DENIED", "ERROR", "NOT_REQUESTED"),
            "voice_first_audio_basis" to setOf("UTTERANCE_START", "PLAYBACK_HEAD"),
            "voice_focus_last_transition" to setOf("RESTORE", "PAUSE", "DUCK", "STOP"),
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
            "status_missing_observations" to GitHubAcceptanceRecoveryEvidence.statusObservationCodes,
            "acceptance_prerequisite_blockers" to GitHubCriterionBlocker.entries.map { it.name }.toSet(),
            "acceptance_blocked_operation_families" to GitHubAcceptanceRecoveryEvidence.operationFamilies,
            "acceptance_claim_failure_reasons" to setOf("RECEIPT_NOT_FOUND", "UNTRUSTED_RECEIPT", "AUTHORIZATION_REVALIDATION_REQUIRED", "OPERATION_FAILED", "CLAIM_VALUE_MISSING", "POINTER_NOT_IN_EVIDENCE", "FIELD_SEMANTICS_MISMATCH", "OBSERVATION_TIME_UNKNOWN", "HISTORICAL_OBSERVATION", "MUTABLE_EVIDENCE_EXPIRED_OR_REVISION_CHANGED", "VALUE_MISMATCH", "EXACT_RECEIPT_MATCH"),
            "read_window_rejections" to setOf("BAD_START", "CONFLICTING_LIMITS", "BAD_MAX_LINES", "BAD_END"),
            "gateway_preflight_blocker_codes" to setOf("VALIDATION_POLICY_REQUIRED", "VALIDATION_CHECKS_REQUIRED", "VALIDATION_CHECK_UNSUPPORTED", "VALIDATION_RUNTIME_UNAVAILABLE", "VALIDATION_REVISION_UNAVAILABLE", "SOURCE_REVISION_REQUIRED", "ACCEPTANCE_CONTRACT_REQUIRED", "ACCEPTANCE_PRODUCER_REQUIRED"),
            "gateway_preflight_recovery_actions" to setOf("CONFIGURE_VALIDATION_POLICY", "DEFINE_ACCEPTANCE_REQUIREMENTS", "CONFIGURE_CI_CHECK_MAPPING", "CONFIGURE_ACCEPTANCE_MAPPING", "REFRESH_SOURCE_REVISION", "PROVISION_PINNED_MIRROR", "PROVISION_VALIDATION_RUNTIME"),
            "invalid_field_classes" to setOf("MISSING_REQUIRED", "WRONG_TYPE", "EMPTY_STRING", "OUT_OF_RANGE", "INVALID_ENUM", "PATTERN_MISMATCH", "UNKNOWN_FIELD", "COMPOSITE_CONSTRAINT"),
            "unmet_criterion_categories" to setOf("BUILD_REVISION_BINDING", "AUTHORIZED_CATALOG", "FIXTURE_LIFECYCLE", "TOOL_ACTION_COVERAGE", "EVIDENCE_SEPARATION", "DEVICE_EXECUTION", "REPOSITORY_GRAPH", "CI_VERIFICATION", "CI_OBSERVATION", "API_OPERATION", "REPOSITORY_LIFECYCLE", "REQUIREMENT"),
            "unmet_criterion_states" to setOf("PASS", "FAIL", "BLOCKED", "NOT_TESTED"),
            "unmet_criterion_blockers" to setOf(
                "OUT_OF_SCOPE", "REPOSITORY_SCOPE_EXCLUDED", "OPERATION_IDENTITY_REQUIRED", "AUTHORIZATION_REQUIRED", "HISTORICAL_CONTRACT_CHANGED", "APPROVAL_REQUIRED", "FIXTURE_APPROVAL_MODE_REQUIRED", "FIXTURE_APPROVAL_STATE_UNKNOWN",
                "LIVE_EVIDENCE_REQUIRED", "CONTINUATION_REQUIRED", "PRECHECK_REJECTED", "OPERATION_FAILED", "USER_DECISION_REQUIRED", "CHECK_SUBSCRIPTION_REQUIRED", "OWNED_FIXTURE_REQUIRED",
                "ISSUE_ID_REQUIRED", "PULL_REQUEST_ID_REQUIRED", "WORKFLOW_RUN_OR_JOB_REQUIRED", "RELEASE_OR_TAG_REQUIRED", "REPOSITORY_INPUT_REQUIRED", "REPOSITORY_LIFECYCLE_REQUIRED",
                "GRAPH_INDEX_REQUIRED", "GRAPH_INDEX_UNCONFIRMED", "GRAPH_INPUT_REQUIRED",
                "EXECUTION_NOT_EXERCISED", "BLOCKER_DETAIL_NOT_RECORDED", "NONE", "VERIFICATION_FAILED", "REQUIRED_OPERATION_BLOCKED", "DEVICE_EXECUTION_NOT_OBSERVED", "CATALOG_NOT_EXHAUSTED",
                "CATALOG_NOT_OBSERVED", "CATALOG_EVIDENCE_INVALID", "REPRESENTATIVE_READ_NOT_OBSERVED", "VERIFICATION_RESERVE_NOT_OBSERVED", "VERIFICATION_RESERVE_EXHAUSTED",
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
            "PROVIDER" to dev.chungjungsoo.gptmobile.data.network.RequestByteBudget.fields + selectedModelObservationFields + GatewayCapabilityFailureEvidence.fields + reasoningProbeFields + githubCooldownFields + profileSelectionFields + completionEvidenceFields + capacityEvidenceFields + githubSchemaBudgetFields + conflictEvidenceFields + conflictFields + capacityDetailFields + setOf("reasoning_capability_age_ms", "admission_prompt_tokens", "admission_safety_tokens", "admission_output_tokens", "admission_capacity_tokens", "admission_dominant_tokens", "admission_measurement", "admission_dominant_component", "reasoning_recovery_action", "reasoning_model_state") + commonHashes + setOf(
                "reasoning_requested_mode", "reasoning_admission_basis", "reasoning_route_kind", "reasoning_control_state", "reasoning_template_hash",
                "provider_failure_stage", "provider_failure_domain", "provider_failure_code",
                "provider_error_code_state", "provider_error_origin", "chat_failure_code_state",
                "provider_wait_eligible", "provider_checkpoint_present", "provider_checkpoint_continuation_allowed", "provider_consecutive_rejections",
                "provider_limit_source", "provider_cooldown_scope_hash",
                "original_error_code_hash", "server_request_id_hash", "generation_id_hash", "response_format", "finish_reason", "configured_output_limit", "effective_output_limit", "backend_output_limit",
                "input_tokens", "output_tokens", "reasoning_tokens", "visible_output_bytes", "reasoning_output_bytes", "backend_attempts", "backend_completed_attempts", "backend_failed_attempt",
                "repair_attempts", "repair_attempt_limit", "transport_complete", "generation_completed", "request_replay_allowed", "logical_continuation_allowed", "usage_source", "output_byte_scope",
                "privacy_policy_hash", "privacy_zdr_required", "privacy_provider_allowlist_count", "privacy_provider_denylist_count", "privacy_provider_order_count", "privacy_fallbacks_allowed", "privacy_observation", "privacy_reason_code", "privacy_status", PrivacyFinalRequestFailureEvidence.FIELD,
                "backend_terminal_code_hash", "backend_terminal_status", "backend_finish_reason", "backend_status"
            ),
            "TOOL" to actionOnlyFields + searchFailureFields + readerCatalogFields + graphLocalFields + setOf("tool_budget_bytes_available", "graph_index_attempt_hash", "graph_transport_kind") + githubCooldownFields + commonHashes + transportFields + setOf("github_transport_kind", "github_transport_wait_ms", "read_range_count", "read_invalid_window_count", "read_window_rejections", "argument_page_size", "tool_action", "tool_error_code", "tool_reason_code", "tool_dispatch_phase", "unknown_field_count", "tool_name_hash", "tool_action_hash", "tool_schema_hash", "original_error_code_hash", "original_reason_code_hash", "argument_shape_hash", "missing_field_count", "invalid_field_count", "validation_error_count", "invalid_field_classes"),
            "ACCEPTANCE" to GitHubAcceptanceRecoveryEvidence.fields + GitHubAcceptancePublicationEvidence.fields + acceptanceScopeFields + githubPrerequisiteFields + setOf("acceptance_pending_remote_continuations", "acceptance_approval_blocked_total", "acceptance_input_blocked_total", "acceptance_live_blocked_total", "acceptance_continuation_blocked_total", "status_policy_recovery", "branch_protection_http_status", "branch_rules_http_status") + commonHashes + criterionFields + setOf("acceptance_unresolved_claim_count", "acceptance_claim_failure_reasons", "repository_input_blocked_count", "workflow_input_blocked_count", "live_evidence_blocked_count", "acceptance_failed_operation_count", "unmet_requirement_count", "unmet_capability_count", "effective_call_limit", "used_calls", "remaining_calls", "verification_reserve", "pending_continuations", "unresolved_outcomes", "unmet_criterion_count", "unmet_criterion_omitted_count", "checkpoint_hash", "acceptance_evidence_scope"),
            "VOICE" to voiceInstallEvidenceFields + voicePackStateFields + voiceCapturePlaybackFields + setOf("voice_install_state", "voice_install_scheduler", "voice_install_failure", "voice_install_stop_reason", "voice_install_progress_percent", "voice_reply_state", "voice_reply_characters", "voice_session_id_hash", "profile_hash", "model_hash", "voice_generation", "voice_engine", "voice_phase", "voice_capture_attempts", "voice_read_calls", "voice_captured_samples", "voice_nonzero_samples", "voice_recognized_characters", "voice_speech_end_to_transcript_ms", "voice_transcript_to_request_ms", "voice_request_to_response_ms", "voice_response_to_queue_ms", "voice_queue_to_synthesis_ms", "voice_synthesis_to_first_audio_ms", "voice_response_to_first_audio_ms", "voice_warmup_ms", "voice_engine_warm", "voice_permission_granted", "voice_foreground", "voice_muted", "voice_focus_result", "voice_input_silenced", "voice_first_audio_basis"),
            "GATEWAY" to setOf("queue_active_leases", "queue_pending_deliveries", "queue_unsettled_submissions", "queue_legacy_runs", "queue_cached_active_jobs", "queue_concurrency_limit", "gateway_preflight_blocker_count", "gateway_preflight_candidate_count", "gateway_preflight_blocker_codes", "gateway_preflight_recovery_actions", "gateway_preflight_discovery_state", "queue_blocker", "gateway_validation_policy_state", "gateway_preflight_reason", "gateway_preflight_blocker_codes", "gateway_preflight_recovery_actions", "gateway_preflight_discovery_state", "gateway_preflight_candidate_count", "status_policy_recovery") + commonHashes + modelPreparationFields + setOf("checkpoint_hash", "original_error_code_hash", "gateway_state", "transport_complete", "request_replay_allowed", "logical_continuation_allowed"),
            "APP" to composerEvidenceFields + commonHashes + setOf("original_error_code_hash", "app_exit_kind", "chat_failure_code_state"),
            "INCIDENT" to commonHashes + replayBudgetFields + reconciliationEvidenceFields + setOf(
                "original_error_code_hash",
                "chat_failure_code_state",
                "provider_error_code_state",
                "provider_error_origin"
            )
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

        private fun allowedFields(area: String, version: Int) = fieldsByArea.getValue(area) + incidentDetailsByArea[area].orEmpty() +
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
                    element(fields[key], if (key == "semantic_ci_rules") 9 else MAX_CRITERIA)?.let { validatedValue(key, it) }?.let { values[key] = it }
                }
            }
            val omitted = mutableListOf<String>()
            // A malformed local member invalidates the entire captured sequence,
            // including cases where ordinary scalar filtering removed the tail.
            if (area == "APP" &&
                mode != "NO_CAPTURE" &&
                (composerSlotFields.any { it in fields && it !in values } || !alignedComposer(values))
            ) {
                (1..8).filter { "composer_event_$it" in fields || "composer_event_${it}_age_ms" in fields }.forEach { slot ->
                    for (field in listOf("composer_event_$slot", "composer_event_${slot}_age_ms")) {
                        values.remove(field)
                        omitted.add(field)
                    }
                }
            }
            if (!alignedCriteria(values)) {
                criterionFields.filter { it in values }.forEach {
                    values.remove(it)
                    omitted.add(it)
                }
            }
            // Filtering an invalid member must not turn a partial history into
            // observed empty/complete history or invalidate unrelated operands.
            if (!IssueReportIncidentCapsule.repeatHistoryAligned(values)) {
                IssueReportIncidentCapsule.repeatHistoryFields.forEach(values::remove)
                values.remove(IssueReportIncidentCapsule.LAST_UNSENT_CODE)
            }
            if (GatewayCapabilityFailureEvidence.fields.any { it in fields && it !in values } || !GatewayCapabilityFailureEvidence.valid(values)) {
                GatewayCapabilityFailureEvidence.fields.forEach(values::remove)
            }
            if (!PrivacyFinalRequestFailureEvidence.valid(values)) {
                PrivacyFinalRequestFailureEvidence.fields.forEach(values::remove)
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
                val retainedCriteria = criterionFields.firstNotNullOfOrNull { (values[it] as? JsonArray)?.size } ?: 0
                val criterionFloor = if (version == 3 && retainedCriteria > 0) {
                    setOf("unmet_criterion_count", "unmet_criterion_omitted_count") + if (retainedCriteria == 1) criterionFields else emptySet()
                } else {
                    emptySet()
                }
                // Keep the last valid row and observed counts even when all ordinary
                // optional fields are gone. An impossible envelope still fails validation.
                val candidates = values.filterKeys { it !in criterionFloor }
                if (candidates.isEmpty()) break
                val optional = if (version == 3) candidates.filterKeys { it !in IssueReportIncidentCapsule.priorityFields && it !in incidentDetailFields && it !in replayBudgetFields && it !in searchFailureFields && it !in readerRunFields && it !in acceptanceScopeFields && it !in githubPrerequisiteFields && it !in GitHubAcceptanceRecoveryEvidence.fields && it !in voiceCapturePlaybackFields && it !in githubSchemaBudgetFields && it !in profileSelectionFields && it !in githubCooldownFields && it !in PrivacyFailureEvidence.fields && it !in PrivacyFinalRequestFailureEvidence.fields } else candidates
                val key = (optional.ifEmpty { candidates }).maxBy { it.key.length + it.value.toString().length }.key
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
                    key in GatewayCapabilityFailureEvidence.fields || key == "provider_failure_code" -> (listOf(key) + GatewayCapabilityFailureEvidence.fields.filter { it in values } + PrivacyFinalRequestFailureEvidence.fields.filter { key == "provider_failure_code" && it in values }).distinct()
                    key in PrivacyFinalRequestFailureEvidence.identityFields -> listOf(key) + PrivacyFinalRequestFailureEvidence.fields.filter { it in values }
                    key in composerSlotFields -> composerSlotFields.filter { it in values }
                    key in setOf("composer_generation", "composer_events_dropped") -> listOf(key) + composerSlotFields.filter { it in values }
                    key in criterionFields -> criterionFields.filter { it in values }
                    shapePair != null -> listOf("${shapePair}_field_names", "${shapePair}_field_types").filter { it in values }
                    else -> listOf(key)
                }
                val removedFields = if (IssueReportIncidentCapsule.repeatHistoryFields.any { it in values } &&
                    remove.any { it in IssueReportIncidentCapsule.repeatHistoryFields || it in IssueReportIncidentCapsule.repeatAdmissionFields }
                ) {
                    (remove + IssueReportIncidentCapsule.repeatHistoryFields + listOfNotNull(IssueReportIncidentCapsule.LAST_UNSENT_CODE.takeIf { it in values })).distinct()
                } else {
                    remove
                }
                removedFields.forEach {
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

        /** Numbered slots retain capture order; equal-age and repeated observations remain distinct. */
        private fun alignedComposer(values: Map<String, JsonElement>): Boolean {
            val slots = (1..8).filter { "composer_event_$it" in values || "composer_event_${it}_age_ms" in values }
            if (slots.isEmpty()) return true
            if ("composer_generation" !in values || "composer_events_dropped" !in values || slots != (1..slots.size).toList()) return false
            val ages = slots.map { slot ->
                if ("composer_event_$slot" !in values) return false
                (values["composer_event_${slot}_age_ms"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return false
            }
            return ages.last() == 0L && ages.zipWithNext().all { (older, newer) -> older >= newer }
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
            if (key in composerEventFields) return string(primitive)?.takeIf { it in composerEvents }?.let(::JsonPrimitive)
            enums[key]?.let { allowed -> return string(primitive)?.takeIf { it in allowed }?.let(::JsonPrimitive) }
            if (key in flags) return primitive.takeUnless { it.isString }?.booleanOrNull?.let(::JsonPrimitive)
            val number = primitive.takeUnless { it.isString }?.longOrNull ?: return null
            val range = when (key) {
                "reconciliation_tracked_signature_count", in searchAttemptFields -> 0L..1024L
                "native_trace_frames" -> 0L..16L
                "anr_frame_count" -> 0L..32L
                "reasoning_probe_attempts" -> 0L..4L
                "composer_generation" -> 1L..16_000_000L
                "composer_events_dropped" -> 0L..16_000_000L
                in composerAgeFields -> 0L..600_000L
                "composer_focus_transitions", "composer_ime_transitions" -> 0L..255L
                "voice_playback_focus_attempts" -> 1L..2L
                "voice_generation" -> 0..Long.MAX_VALUE
                "voice_install_progress_percent" -> 0L..100L
                "voice_install_downloaded_bytes", "voice_install_total_bytes" -> 0L..1_000_000_000L
                "voice_install_http_status", "status_policy_protection_http_status", "status_policy_rules_http_status" -> 100L..599L
                "task_deadline_window_seconds", "task_deadline_overdue_seconds", "task_deadline_elapsed_seconds", "task_deadline_remaining_seconds", "acceptance_cleanup_reserve", "acceptance_fixture_start_minimum",
                "acceptance_ready_read_count", "acceptance_additional_ready_read_count", "acceptance_additional_blocked_family_count" -> 0L..16_000_000L
                "backend_terminal_status", "gateway_http_status", "gateway_upstream_status", "gateway_reconciliation_status", "branch_protection_http_status", "branch_rules_http_status" -> 100L..599L
                "gateway_protocol_version" -> 1L..1000L
                "model_preparation_load_requests" -> 0L..1L
                "conflict_pending_tool_count" -> 0L..128L
                "conflict_handoff_sequence" -> 1L..16_000_000L
                in githubCooldownLongs -> 0L..Long.MAX_VALUE
                in capacityCeilingFields -> 1L..16_000_000L
                in attempts -> 0L..10_000L
                in durations -> 0L..86_400_000L
                in dev.chungjungsoo.gptmobile.data.network.RequestByteBudget.counts -> 0L..Int.MAX_VALUE.toLong()
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

        private fun element(value: Any?, maxListItems: Int = MAX_CRITERIA): JsonElement? = when (value) {
            is JsonElement -> value
            is String -> value.takeIf { it.length <= 128 }?.let(::JsonPrimitive)
            is Boolean -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is List<*> -> value.takeIf { it.size <= maxListItems }?.let { JsonArray(it.map { item -> string(item)?.let(::JsonPrimitive) ?: return null }) }
            else -> null
        }
    }
}
