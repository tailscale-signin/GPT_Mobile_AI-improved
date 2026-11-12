package dev.chungjungsoo.gptmobile.data.diagnostics

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
        if (schemaVersion != 1 || area !in areas || capturedAtMillis !in 1..9_999_999_999_999L || captureMode !in captureModes) return null
        val allowed = fieldsByArea.getValue(area)
        if (values.keys.any { it !in allowed } || omittedFields.any { it !in allowed || it in values } || omittedFields.distinct().size != omittedFields.size) return null
        if (captureMode == "NO_CAPTURE" && (values.isNotEmpty() || omittedFields.isNotEmpty())) return null
        val copied = linkedMapOf<String, JsonElement>()
        values.forEach { (key, value) -> copied[key] = validatedValue(key, value) ?: return null }
        if (!alignedCriteria(copied)) return null
        val expectedUnavailable = coreFields.getValue(area).filterNot { it in copied }
        if (unavailableFields.toSet() != expectedUnavailable.toSet() || unavailableFields.distinct().size != unavailableFields.size) return null
        val snapshot = copy(values = copied.toMap(), unavailableFields = unavailableFields.toList(), omittedFields = omittedFields.toList())
        return snapshot.takeIf { IssueReport.json.encodeToString(serializer(), it).encodeToByteArray().size <= MAX_BYTES }
    }

    companion object {
        const val MAX_BYTES = 3072
        const val MAX_CRITERIA = 8
        val areas = setOf("PROVIDER", "TOOL", "ACCEPTANCE", "VOICE", "GATEWAY", "APP")
        private val captureModes = setOf("INCIDENT", "SELECTED_CAPTURE", "NO_CAPTURE")
        private val hashPattern = Regex("[a-f0-9]{64}")
        private val commonHashes = setOf("run_id_hash", "invocation_id_hash", "call_id_hash", "job_id_hash", "profile_hash", "model_hash")
        private val hashFields = commonHashes + setOf(
            "voice_session_id_hash", "original_error_code_hash", "original_reason_code_hash", "server_request_id_hash", "generation_id_hash",
            "privacy_policy_hash", "backend_terminal_code_hash", "tool_name_hash", "tool_action_hash", "tool_schema_hash", "argument_shape_hash", "checkpoint_hash"
        )
        private val hashLists = setOf("unmet_criterion_id_hashes", "unmet_criterion_reason_hashes")
        private val flags = setOf(
            "transport_complete", "generation_completed", "request_replay_allowed", "logical_continuation_allowed", "privacy_zdr_required", "privacy_fallbacks_allowed",
            "voice_engine_warm", "voice_permission_granted", "voice_foreground", "voice_muted", "voice_input_silenced"
        )
        private val attempts = setOf("backend_attempts", "backend_completed_attempts", "backend_failed_attempt", "repair_attempts", "repair_attempt_limit")
        private val durations = setOf(
            "voice_response_to_queue_ms",
            "voice_queue_to_synthesis_ms",
            "voice_synthesis_to_first_audio_ms",
            "voice_response_to_first_audio_ms",
            "voice_warmup_ms"
        )
        private val counts = setOf(
            "configured_output_limit", "effective_output_limit", "backend_output_limit", "input_tokens", "output_tokens", "reasoning_tokens", "visible_output_bytes", "reasoning_output_bytes",
            "privacy_provider_allowlist_count", "privacy_provider_denylist_count", "privacy_provider_order_count", "missing_field_count", "invalid_field_count", "validation_error_count",
            "effective_call_limit", "used_calls", "remaining_calls", "verification_reserve", "pending_continuations", "unresolved_outcomes", "unmet_criterion_count", "unmet_criterion_omitted_count",
            "voice_capture_attempts", "voice_read_calls", "voice_captured_samples", "voice_nonzero_samples", "voice_recognized_characters"
        )
        private val finishReasons = setOf("STOP", "TOOL_CALLS", "LENGTH", "CONTENT_FILTER", "OTHER", "UNKNOWN")
        private val enums = mapOf(
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
            "gateway_state" to setOf("QUEUED", "RUNNING", "WAITING_FOR_CLIENT_TOOL", "WAITING_FOR_APPROVAL", "PAUSED", "CANCELLING", "CANCELLED", "COMPLETED", "FAILED"),
            "app_exit_kind" to setOf("JVM_CRASH", "NATIVE_CRASH", "APP_NOT_RESPONDING", "STARTUP_FAILED")
        )
        private val enumLists = mapOf(
            "invalid_field_classes" to setOf("MISSING_REQUIRED", "WRONG_TYPE", "EMPTY_STRING", "OUT_OF_RANGE", "INVALID_ENUM", "PATTERN_MISMATCH", "UNKNOWN_FIELD", "COMPOSITE_CONSTRAINT"),
            "unmet_criterion_categories" to setOf("BUILD_REVISION_BINDING", "AUTHORIZED_CATALOG", "FIXTURE_LIFECYCLE", "TOOL_ACTION_COVERAGE", "EVIDENCE_SEPARATION", "DEVICE_EXECUTION", "REPOSITORY_GRAPH", "CI_VERIFICATION", "API_OPERATION", "REPOSITORY_LIFECYCLE", "REQUIREMENT"),
            "unmet_criterion_states" to setOf("PASS", "FAIL", "BLOCKED", "NOT_TESTED"),
            "unmet_criterion_blockers" to setOf(
                "OUT_OF_SCOPE", "REPOSITORY_SCOPE_EXCLUDED", "OPERATION_IDENTITY_REQUIRED", "AUTHORIZATION_REQUIRED", "HISTORICAL_CONTRACT_CHANGED", "APPROVAL_REQUIRED", "FIXTURE_APPROVAL_MODE_REQUIRED",
                "LIVE_EVIDENCE_REQUIRED", "CONTINUATION_REQUIRED", "PRECHECK_REJECTED", "OPERATION_FAILED", "USER_DECISION_REQUIRED", "CHECK_SUBSCRIPTION_REQUIRED", "OWNED_FIXTURE_REQUIRED",
                "ISSUE_ID_REQUIRED", "PULL_REQUEST_ID_REQUIRED", "WORKFLOW_RUN_OR_JOB_REQUIRED", "RELEASE_OR_TAG_REQUIRED", "REPOSITORY_INPUT_REQUIRED", "REPOSITORY_LIFECYCLE_REQUIRED",
                "EXECUTION_NOT_EXERCISED", "BLOCKER_DETAIL_NOT_RECORDED", "NONE", "VERIFICATION_FAILED", "REQUIRED_OPERATION_BLOCKED", "DEVICE_EXECUTION_NOT_OBSERVED", "CATALOG_NOT_EXHAUSTED",
                "FIXTURE_PROOF_INCOMPLETE", "GRAPH_QUALIFICATION_INCOMPLETE", "REPOSITORY_OPERATION_UNVERIFIED", "MATCHING_EVIDENCE_MISSING"
            )
        )
        private val criterionFields = hashLists + setOf("unmet_criterion_categories", "unmet_criterion_states", "unmet_criterion_blockers")
        private val fieldsByArea = mapOf(
            "PROVIDER" to commonHashes + setOf(
                "original_error_code_hash", "server_request_id_hash", "generation_id_hash", "response_format", "finish_reason", "configured_output_limit", "effective_output_limit", "backend_output_limit",
                "input_tokens", "output_tokens", "reasoning_tokens", "visible_output_bytes", "reasoning_output_bytes", "backend_attempts", "backend_completed_attempts", "backend_failed_attempt",
                "repair_attempts", "repair_attempt_limit", "transport_complete", "generation_completed", "request_replay_allowed", "logical_continuation_allowed", "usage_source", "output_byte_scope",
                "privacy_policy_hash", "privacy_zdr_required", "privacy_provider_allowlist_count", "privacy_provider_denylist_count", "privacy_provider_order_count", "privacy_fallbacks_allowed", "privacy_observation",
                "backend_terminal_code_hash", "backend_terminal_status", "backend_finish_reason", "backend_status"
            ),
            "TOOL" to commonHashes + setOf("tool_name_hash", "tool_action_hash", "tool_schema_hash", "original_error_code_hash", "original_reason_code_hash", "argument_shape_hash", "missing_field_count", "invalid_field_count", "validation_error_count", "invalid_field_classes"),
            "ACCEPTANCE" to commonHashes + criterionFields + setOf("effective_call_limit", "used_calls", "remaining_calls", "verification_reserve", "pending_continuations", "unresolved_outcomes", "unmet_criterion_count", "unmet_criterion_omitted_count", "checkpoint_hash", "acceptance_evidence_scope"),
            "VOICE" to setOf("voice_session_id_hash", "profile_hash", "model_hash", "voice_generation", "voice_engine", "voice_phase", "voice_capture_attempts", "voice_read_calls", "voice_captured_samples", "voice_nonzero_samples", "voice_recognized_characters", "voice_response_to_queue_ms", "voice_queue_to_synthesis_ms", "voice_synthesis_to_first_audio_ms", "voice_response_to_first_audio_ms", "voice_warmup_ms", "voice_engine_warm", "voice_permission_granted", "voice_foreground", "voice_muted", "voice_focus_result", "voice_input_silenced", "voice_first_audio_basis"),
            "GATEWAY" to commonHashes + setOf("checkpoint_hash", "original_error_code_hash", "gateway_state", "transport_complete", "request_replay_allowed", "logical_continuation_allowed"),
            "APP" to setOf("original_error_code_hash", "app_exit_kind")
        )
        private val coreFields = mapOf(
            "PROVIDER" to listOf("profile_hash", "model_hash", "configured_output_limit", "output_tokens", "finish_reason", "backend_attempts", "transport_complete"),
            "TOOL" to listOf("tool_name_hash", "tool_action_hash", "tool_schema_hash", "original_error_code_hash", "missing_field_count", "invalid_field_count"),
            "ACCEPTANCE" to listOf("unmet_criterion_id_hashes", "unmet_criterion_categories", "unmet_criterion_states", "effective_call_limit", "used_calls", "remaining_calls", "checkpoint_hash"),
            "VOICE" to listOf("voice_engine", "voice_phase", "voice_capture_attempts", "voice_recognized_characters", "voice_response_to_queue_ms", "voice_synthesis_to_first_audio_ms", "voice_response_to_first_audio_ms"),
            "GATEWAY" to listOf("job_id_hash", "gateway_state", "checkpoint_hash", "transport_complete"),
            "APP" to listOf("original_error_code_hash", "app_exit_kind")
        )

        /** Unknown/private input is not copied. Explicit area selection is required to opt into context v5. */
        fun fromFields(source: String, fields: Map<String, Any?>, occurredAtMillis: Long): IssueReportDiagnostics? {
            if (source !in IssueReportContext.sources || occurredAtMillis !in 1..9_999_999_999_999L) return null
            val area = string(fields["diagnostic_area"])?.takeIf { it in areas } ?: return null
            val mode = if ("diagnostic_capture_mode" in fields) string(fields["diagnostic_capture_mode"]) ?: return null else "INCIDENT"
            if (mode !in captureModes) return null
            val values = linkedMapOf<String, JsonElement>()
            if (mode != "NO_CAPTURE") {
                fieldsByArea.getValue(area).forEach { key ->
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
            fun snapshot() = IssueReportDiagnostics(1, area, occurredAtMillis, mode, values.toMap(), coreFields.getValue(area).filterNot { it in values }, omitted.toList())
            var result = snapshot()
            // Bound before preview/approval, and say exactly which otherwise valid fields did not fit.
            while (IssueReport.json.encodeToString(serializer(), result).encodeToByteArray().size > MAX_BYTES && values.isNotEmpty()) {
                val key = values.maxBy { it.key.length + it.value.toString().length }.key
                val remove = if (key in criterionFields) criterionFields.filter { it in values } else listOf(key)
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
            if (key in hashFields) return string(primitive)?.takeIf(hashPattern::matches)?.let(::JsonPrimitive)
            enums[key]?.let { allowed -> return string(primitive)?.takeIf { it in allowed }?.let(::JsonPrimitive) }
            if (key in flags) return primitive.takeUnless { it.isString }?.booleanOrNull?.let(::JsonPrimitive)
            val number = primitive.takeUnless { it.isString }?.longOrNull ?: return null
            val range = when (key) {
                "voice_generation" -> 0..Long.MAX_VALUE
                "backend_terminal_status" -> 100L..599L
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
