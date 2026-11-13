package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.ProviderRecoveryFailure
import dev.chungjungsoo.gptmobile.data.github.GatewayInputRejectionDetails
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Public evidence is a closed projection of this incident, never a message or the latest unrelated run. */
@Serializable
internal data class IssueReportContext(
    val schemaVersion: Int,
    val occurredAtMillis: Long,
    val source: String,
    val unavailableFields: List<String>,
    val stage: String? = null,
    val provider: String? = null,
    val operation: String? = null,
    val httpStatus: Int? = null,
    val retryAfterMillis: Long? = null,
    val dispatched: Boolean? = null,
    val retryable: Boolean? = null,
    val resumeAllowed: Boolean? = null,
    val requiresReconciliation: Boolean? = null,
    val remoteAcceptance: String? = null,
    val jobState: String? = null,
    val recoveryAction: String? = null,
    val reasonCode: String? = null,
    val requiredCriteria: Int? = null,
    val verifiedCriteria: Int? = null,
    val remainingCriteria: Int? = null,
    val missingFields: List<String>? = null,
    val invalidFields: List<String>? = null,
    val runIdHash: String? = null,
    val invocationIdHash: String? = null,
    val callIdHash: String? = null,
    val jobIdHash: String? = null,
    val activeJobIdHash: String? = null,
    val exceptionFingerprint: String? = null,
    val originalFailureCode: String? = null,
    val originalHttpStatus: Int? = null,
    val checkpointBindingHash: String? = null,
    val checkpointDigestHash: String? = null,
    val resumeClaimIdHash: String? = null,
    val ownershipFence: Long? = null,
    val diagnostics: IssueReportDiagnostics? = null
) {
    fun withException(type: String?, frames: List<String>): IssueReportContext =
        if (type == null) this else copy(exceptionFingerprint = digest("exception", (listOf(type) + frames).joinToString("\n"))).withAvailability()

    fun withDiagnostics(snapshot: IssueReportDiagnostics): IssueReportContext =
        copy(schemaVersion = if (schemaVersion == 6 || snapshot.schemaVersion == 2) 6 else 5, diagnostics = requireNotNull(snapshot.validated()) { "Invalid report diagnostics." }).withAvailability()

    private fun withAvailability(): IssueReportContext {
        val present = IssueReport.json.encodeToJsonElement(serializer(), this).jsonObject.keys
        val fields = when (schemaVersion) {
            5, 6 -> optionalFields + acceptanceFields + recoveryFields + "diagnostics"
            4 -> optionalFields + acceptanceFields + recoveryFields
            2, 3 -> optionalFields + acceptanceFields
            else -> optionalFields
        }
        return copy(unavailableFields = fields.filterNot { it in present })
    }

    companion object {
        val sources = setOf("CHAT_FAILURE", "STRUCTURED_EVENT", "TOOL_RESULT", "VOICE_STATE", "EXCEPTION", "PROCESS_EXIT", "CODE_ONLY", "MANUAL_REPORT")
        val providers = setOf("OPENAI", "ANTHROPIC", "GOOGLE", "GROQ", "OLLAMA", "OPENROUTER", "LLAMA", "GATEWAY", "LOCAL", "CUSTOM", "LITERT_LM", "FREE", "NVIDIA")
        val operations = setOf("github_context", "github_read", "github_search", "github_pr", "github_issue", "github_ci", "github_commit", "github_review", "github_engineering", "repository_graph", "mcp", "web_search", "read_url", "voice_capture", "voice_recognition", "voice_playback")
        val stages = setOf("audio_focus", "capture_configuration", "capture_initialization", "capture_start", "capture_read", "retrieval", "precheck", "redirect_precheck", "admission", "planning", "repository_admission", "provider_admission", "request_validation", "message_admission", "conversation_admission", "backend_admission", "binding_admission", "execution_admission", "connection_preflight", "dns_resolution", "dns", "connection", "response_stream", "tls", "cancelled", "transport", "provider_generation", "model_invocation", "tool_precheck", "tool_readiness", "tool_admission", "task_budget", "inventory_pagination", "tool_dispatch", "tool_execution", "tool_continuation", "tool_reconciliation", "backend_template", "gateway_recovery", "gateway_dispatch", "review_compatibility", "answer_review", "engineering_guard", "claim_extraction", "claim_verification", "verification", "final_synthesis", "delivery", "lifecycle", "voice_capture", "voice_recognition", "voice_playback")
        val jobStates = setOf("QUEUED", "RUNNING", "WAITING_FOR_CLIENT_TOOL", "WAITING_FOR_APPROVAL", "PAUSED", "CANCELLING", "CANCELLED", "COMPLETED", "FAILED")
        val recoveryActions = setOf("OPEN_ACTIVE_JOB", "RETURN_CLIENT_TOOL_RESULTS", "WAIT_FOR_ACTIVE_JOB", "QUEUE_INSTRUCTION", "RESOLVE_ACTIVE_JOB", "RECONCILE_ACTIVE_GATEWAY_JOB", "REFRESH_REPOSITORY_ACCESS", "CHANGE_REPOSITORY", "SELECT_REF", "RECONCILE_OUTCOME", "REVIEW_AUTHORIZATION", "RETRY_AFTER_BACKOFF", "RETRY_AFTER_CONFIGURATION_CHANGE", "MICROPHONE_PERMISSION", "RETRY_RECOGNITION", "RETRY_PLAYBACK", "SPEECH_SERVICE", "SPEECH_LANGUAGE", "LOCAL_MODEL")
        val reasonCodes = setOf("SELECTION_NOT_OBJECT", "SELECTION_SCHEMA_INVALID", "SELECTION_IDENTITY_INVALID", "SELECTION_CREDENTIAL_REFERENCE_INVALID")
        private val admissionStages = setOf("model_preparation")
        private val acceptanceStages = setOf("acceptance_verification")
        private val reviewStages = setOf("review_comparison", "approval")
        private val reviewRecoveryActions = setOf("SELECT_REVIEW_BASELINE", "RESTORE_REVIEW_COMPARISON_EVIDENCE")
        private val recoveryReasonCodes = ProviderRecoveryFailure.CODES + setOf("GITHUB_EXTRACTION_OUTPUT_LIMIT", "GITHUB_EXTRACTION_INVALID_RESPONSE", "GITHUB_EXTRACTION_TIMEOUT", "GITHUB_EXTRACTION_UNAVAILABLE", "GITHUB_EXTRACTION_FAILED")
        private val toolPrecheckReasonCodes = setOf("BUDGET_EXHAUSTED", "TOOL_BATCH_OVERSIZE", "DUPLICATE_INVOCATION", "REPEATED_FAILURE", "BUDGET_CHECKPOINT_UNAVAILABLE", "UNSUPPORTED_TOOL_CONTRACT", "UNSUPPORTED_OPERATION", "UNKNOWN_TOOL", "SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_CURSOR_ALTERED")
        private val lifecycleRecoveryActions = setOf(
            "REVIEW_SAVED_RECOVERY",
            "RECONCILE_TOOL_RECEIPTS",
            "RESUME_THIS_BATCH_WITH_A_SMALLER_CLAIM_LIMIT",
            "RESUME_THIS_BATCH_WITH_STRICT_SCHEMA",
            "RESUME_UNFINISHED_EXTRACTION_BATCHES",
            "INSPECT_THE_RECORDED_PROVIDER_FAILURE",
            "RETRY_CLAIM_EXTRACTION_FROM_SAVED_RECEIPTS"
        )
        private val acceptanceReasonCodes = setOf(
            "UNVERIFIED_ACCEPTANCE_CRITERIA", "ACCEPTED_TASK_DEADLINE_REACHED", "ACCEPTANCE_EVIDENCE_MISSING",
            "TOOL_BUDGET_EXHAUSTED", "VERIFICATION_RESERVE_REACHED", "FIXTURE_CLEANUP_UNVERIFIED",
            "AUTHORIZED_CATALOG_INCOMPLETE", "RECORDED_OPERATION_BLOCKERS", "BOUNDED_INVENTORY_SAMPLE_REACHED",
            "INVENTORY_CONTINUATION_BLOCKED", "CAPABILITY_PREREQUISITES_MISSING", "PRIMARY_EVIDENCE_INCOMPLETE",
            "SAVED_VIEW_PERSISTENCE_UNAVAILABLE", "RECONCILE_REMOTE_OUTCOME", "RECORDED_EXECUTION_FAILURE",
            "WAITING_FOR_APPROVAL", "APPROVAL_REJECTED", "GITHUB_EVIDENCE_INCOMPLETE"
        )
        private val acceptanceRecoveryActions = setOf(
            "RESUME_UNFINISHED_WORK", "RESUME_VERIFICATION", "RECONCILE_REMOTE_OUTCOME", "RESUME_UNVERIFIED_REQUIREMENTS",
            "RECONCILE_CATALOG_CONTRACTS", "RESUME_ISSUED_CATALOG_CONTINUATION", "RECONCILE_CATALOG_SNAPSHOT",
            "RESOLVE_BOUND_APPROVAL", "RECHECK_OWNED_FIXTURE_PREFLIGHT", "RECONCILE_OWNED_FIXTURE",
            "REVIEW_REMAINING_SCOPE_AND_BUDGET", "RESOLVE_CAPABILITY_PREREQUISITE", "NEW_TASK_REQUIRED",
            "RESTORE_AUTHORIZED_CONFIGURATION", "INSPECT_EXECUTION_FAILURE"
        )
        private val acceptanceFields = listOf("requiredCriteria", "verifiedCriteria", "remainingCriteria")
        private val recoveryFields = listOf("originalFailureCode", "originalHttpStatus", "checkpointBindingHash", "checkpointDigestHash", "resumeClaimIdHash", "ownershipFence")
        val selectionFields = setOf("profile_uid", "profile_revision", "provider_connection_id", "provider_connection_revision", "provider_kind", "configured_endpoint", "requested_model_id", "generation_parameters", "privacy_scope", "tool_owner", "required_capability_policy", "credential_reference", "credential_account_identity", "approved_alias_mapping")
        val optionalFields = listOf("stage", "provider", "operation", "httpStatus", "retryAfterMillis", "dispatched", "retryable", "resumeAllowed", "requiresReconciliation", "remoteAcceptance", "jobState", "recoveryAction", "reasonCode", "missingFields", "invalidFields", "runIdHash", "invocationIdHash", "callIdHash", "jobIdHash", "activeJobIdHash", "exceptionFingerprint")

        private fun needsReviewContext(stage: String?, recoveryAction: String?) = stage in reviewStages || recoveryAction in reviewRecoveryActions

        /** Some approved v2 reports already contain review extensions. Negotiate them without rewriting their bytes. */
        fun requiredServiceVersions(version: Int, stage: String?, recoveryAction: String?): Set<Int> =
            if (version == 2 && needsReviewContext(stage, recoveryAction)) setOf(2, 3) else setOf(version)

        /** Validate the new extension at approval and again before either route consumes saved bytes. */
        fun validDiagnosticsIn(value: JsonElement?): Boolean {
            if (value == null || value == JsonNull) return true
            val context = value as? JsonObject ?: return false
            val version = (context["schemaVersion"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            if (version !in setOf(5, 6)) return version != null && version in 1..4 && "diagnostics" !in context
            val fields = (optionalFields + acceptanceFields + recoveryFields + "diagnostics").toSet()
            val required = setOf("schemaVersion", "occurredAtMillis", "source", "unavailableFields") + if (version == 5) setOf("diagnostics") else emptySet()
            if (!context.keys.containsAll(required) || (context.keys - fields - required).isNotEmpty()) return false
            fun text(key: String) = (context[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            fun number(key: String) = (context[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            val enums = mapOf(
                "source" to sources,
                "stage" to stages + acceptanceStages + reviewStages + if (version == 6) admissionStages else emptySet(),
                "provider" to providers,
                "operation" to operations,
                "remoteAcceptance" to setOf("YES", "NO", "UNKNOWN"),
                "jobState" to jobStates,
                "recoveryAction" to recoveryActions + acceptanceRecoveryActions + reviewRecoveryActions + lifecycleRecoveryActions,
                "reasonCode" to reasonCodes + acceptanceReasonCodes + recoveryReasonCodes + toolPrecheckReasonCodes + if (version == 6) GatewayInputRejectionDetails.reasonCodes + IssueReportDiagnostics.modelPreparationReasonCodes else emptySet(),
                "originalFailureCode" to IssueReportCodes.allowed
            )
            if (enums.any { (key, choices) -> key in context && text(key) !in choices }) return false
            if (text("reasonCode") in IssueReportDiagnostics.modelPreparationReasonCodes && text("stage") != "model_preparation") return false
            if (text("reasonCode") in toolPrecheckReasonCodes &&
                (text("source") != "TOOL_RESULT" || text("stage") != "tool_precheck" || context["dispatched"] != JsonPrimitive(false) || context["requiresReconciliation"] == JsonPrimitive(true))
            ) {
                return false
            }
            val numbers = mapOf(
                "occurredAtMillis" to 1L..9_999_999_999_999L,
                "httpStatus" to 100L..599L,
                "originalHttpStatus" to 100L..599L,
                "retryAfterMillis" to 0L..86_400_000L,
                "requiredCriteria" to 0L..100_000L,
                "verifiedCriteria" to 0L..100_000L,
                "remainingCriteria" to 0L..100_000L,
                "ownershipFence" to 0L..Long.MAX_VALUE
            )
            if (numbers.any { (key, range) -> key in context && number(key)?.let { it in range } != true }) return false
            val requiredCount = number("requiredCriteria")
            val verified = number("verifiedCriteria")
            val remaining = number("remainingCriteria")
            if (requiredCount != null && (verified != null && verified > requiredCount || remaining != null && remaining > requiredCount || verified != null && remaining != null && verified + remaining != requiredCount)) return false
            if (listOf("dispatched", "retryable", "resumeAllowed", "requiresReconciliation").any { it in context && context[it] !in setOf(JsonPrimitive(true), JsonPrimitive(false)) }) return false
            val hashes = listOf("runIdHash", "invocationIdHash", "callIdHash", "jobIdHash", "activeJobIdHash", "exceptionFingerprint", "checkpointBindingHash", "checkpointDigestHash", "resumeClaimIdHash")
            if (hashes.any { it in context && text(it)?.matches(Regex("[a-f0-9]{64}")) != true }) return false
            for (key in listOf("missingFields", "invalidFields")) {
                if (key !in context) continue
                val items = context[key] as? JsonArray ?: return false
                val names = items.map { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.contentOrNull ?: return false }
                val allowedNames = selectionFields + if (version == 6 && text("reasonCode") in GatewayInputRejectionDetails.reasonCodes) GatewayInputRejectionDetails.fieldNames else emptySet()
                if (names.any { it !in allowedNames } || names.distinct().size != names.size) return false
            }
            val unavailable = context["unavailableFields"] as? JsonArray ?: return false
            val names = unavailable.map { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.contentOrNull ?: return false }
            val diagnostics = context["diagnostics"]?.let { IssueReportDiagnostics.fromJson(it) ?: return false }
            if (version == 5 && diagnostics?.schemaVersion != 1) return false
            return names.distinct().size == names.size && names.toSet() == fields - context.keys
        }

        /** A historical provider interruption cannot reclassify this local refusal as a fresh 429. */
        fun localRecoveryCode(fields: Map<String, Any?>): String? {
            fun text(value: Any?) = when (value) {
                is String -> value
                is JsonPrimitive -> value.takeIf { it.isString }?.contentOrNull
                else -> null
            }
            if (text(fields["failure_origin"]) != "local_recovery") return null
            return text(fields["recovery_code"])?.takeIf { it in ProviderRecoveryFailure.CODES }
        }

        fun fromFields(source: String, fields: Map<String, Any?> = emptyMap(), occurredAtMillis: Long = System.currentTimeMillis()): IssueReportContext {
            require(source in sources)
            require(occurredAtMillis in 1..9_999_999_999_999L)
            fun value(vararg keys: String): Any? = keys.firstNotNullOfOrNull { fields[it] }
            fun text(vararg keys: String): String? = when (val item = value(*keys)) {
                is String -> item.takeIf { it.length <= 256 }
                is JsonPrimitive -> item.takeIf { it.isString }?.contentOrNull?.takeIf { it.length <= 256 }
                else -> null
            }
            fun flag(vararg keys: String): Boolean? = when (val item = value(*keys)) {
                is Boolean -> item
                is JsonPrimitive -> item.takeUnless { it.isString }?.booleanOrNull
                else -> null
            }
            fun number(vararg keys: String): Long? = when (val item = value(*keys)) {
                is Int -> item.toLong()
                is Long -> item
                is JsonPrimitive -> item.takeUnless { it.isString }?.longOrNull
                else -> null
            }
            fun names(allowed: Set<String>, vararg keys: String): List<String>? {
                val items = when (val item = value(*keys)) {
                    is JsonArray -> item
                    is List<*> -> item.takeIf { it.size <= allowed.size } ?: return null
                    else -> return null
                }
                if (items.size > allowed.size) return null
                val names = items.map { item -> if (item is String) item else (item as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull }
                if (names.any { it !in allowed }) return null
                return names.filterNotNull().distinct().sorted()
            }
            val dispatchState = text("dispatch_state", "dispatchState", "attempt_dispatch_state", "executionState")?.uppercase(Locale.ROOT)
            val explicitDispatch = flag("dispatched")
            val dispatched = when {
                dispatchState == "UNKNOWN_OUTCOME" -> explicitDispatch.takeIf { it == true }
                explicitDispatch != null -> explicitDispatch
                dispatchState in setOf("NOT_DISPATCHED", "PRECHECK_REJECTED") -> false
                dispatchState == "DISPATCHED" -> true
                else -> null
            }
            val stage = text("failed_stage", "failedStage", "failurePhase", "phase", "stage")?.lowercase(Locale.ROOT)
                ?.takeIf { it in stages || it in acceptanceStages || it in reviewStages || it in admissionStages }
            val recoveryAction = text("recovery_action", "next_action", "recoveryAction", "nextAction")?.uppercase(Locale.ROOT)
                ?.takeIf { it in recoveryActions || it in acceptanceRecoveryActions || it in reviewRecoveryActions || it in lifecycleRecoveryActions }
            val localRecovery = localRecoveryCode(fields)
            val toolPrecheck = source == "TOOL_RESULT" && stage == "tool_precheck" && dispatched == false && flag("requires_reconciliation", "requiresReconciliation") != true
            val reasonCode = localRecovery ?: text("reasonCode", "reason_code", "stop_reason")?.uppercase(Locale.ROOT)
                ?.takeIf { it in reasonCodes || it in acceptanceReasonCodes || it in recoveryReasonCodes || it in GatewayInputRejectionDetails.reasonCodes || stage == "model_preparation" && it in IssueReportDiagnostics.modelPreparationReasonCodes || toolPrecheck && it in toolPrecheckReasonCodes }
            val engineering = reasonCode in GatewayInputRejectionDetails.reasonCodes
            val fieldNames = selectionFields + if (engineering) GatewayInputRejectionDetails.fieldNames else emptySet()
            val original = (value("original_interruption") as? Map<*, *>)?.takeIf { localRecovery != null }
            val originalDetails = original?.get("details") as? Map<*, *>
            val originalCode = when (val code = original?.get("code")) {
                is String -> code.takeIf { it.length <= 96 }
                is Int -> "429".takeIf { code == 429 }
                is Long -> "429".takeIf { code == 429L }
                is JsonPrimitive -> if (code.isString) code.contentOrNull?.takeIf { it.length <= 96 } else "429".takeIf { code.longOrNull == 429L }
                else -> null
            }?.uppercase(Locale.ROOT)?.let { if (it == "429") "PROVIDER_RATE_LIMITED" else IssueReportCodes.known(it) }
            val originalStatus = when (val status = originalDetails?.get("http_status")) {
                is Int -> status.toLong()
                is Long -> status
                is JsonPrimitive -> status.takeUnless { it.isString }?.longOrNull
                else -> null
            }?.takeIf { it in 100..599 }?.toInt()
            fun recoveryHash(key: String, kind: String, pattern: Regex): String? = text(key)?.lowercase(Locale.ROOT)
                ?.takeIf { localRecovery != null && pattern.matches(it) }?.let { identifierHash(kind, it) }
            fun criterionCount(key: String): Int? = number(key)?.takeIf { it in 0..100_000 }?.toInt()
            val required = criterionCount("required_criteria")
            val verified = criterionCount("verified_criteria")
            val remaining = criterionCount("remaining_criteria")
            val consistent = (required == null || verified == null || verified <= required) &&
                (required == null || remaining == null || remaining <= required) &&
                (required == null || verified == null || remaining == null || verified + remaining == required)
            val requiredCriteria = required.takeIf { consistent }
            val verifiedCriteria = verified.takeIf { consistent }
            val remainingCriteria = remaining.takeIf { consistent }
            val acceptanceContext = stage in acceptanceStages ||
                reasonCode in acceptanceReasonCodes ||
                recoveryAction in acceptanceRecoveryActions ||
                requiredCriteria != null ||
                verifiedCriteria != null ||
                remainingCriteria != null
            val runIdHash = identifierHash("run", text("run_id", "runId"))
            val invocationIdHash = identifierHash("invocation", text("invocation_id", "invocationId"))
            val callIdHash = identifierHash("call", text("call_id", "callId", "toolCallId"))
            val jobIdHash = identifierHash("job", text("job_id", "jobId", "gatewayJobId"))
            val diagnosticIdentities = mapOf("run_id_hash" to runIdHash, "invocation_id_hash" to invocationIdHash, "call_id_hash" to callIdHash, "job_id_hash" to jobIdHash).filterValues { it != null }
            val diagnostics = IssueReportDiagnostics.fromFields(source, fields + diagnosticIdentities, occurredAtMillis)
            return IssueReportContext(
                schemaVersion = when {
                    engineering || stage in admissionStages || diagnostics?.schemaVersion == 2 -> 6
                    diagnostics != null -> 5
                    localRecovery != null || reasonCode in recoveryReasonCodes || reasonCode in toolPrecheckReasonCodes || recoveryAction in lifecycleRecoveryActions -> 4
                    needsReviewContext(stage, recoveryAction) -> 3
                    acceptanceContext -> 2
                    else -> 1
                },
                occurredAtMillis = occurredAtMillis,
                source = source,
                unavailableFields = emptyList(),
                stage = stage,
                provider = text("provider_kind", "provider", "apiType")?.uppercase(Locale.ROOT)?.takeIf { it in providers },
                operation = text("operation", "tool", "tool_name")?.substringBefore("__")?.lowercase(Locale.ROOT)?.takeIf { it in operations },
                httpStatus = number("http_status", "httpStatus", "statusCode")?.takeIf { it in 100..599 }?.toInt(),
                // Only explicit relative durations; ProfileFailureRecord also has absolute deadlines.
                retryAfterMillis = number("retry_after_ms", "retryAfterMs")?.takeIf { it in 0..86_400_000 },
                dispatched = dispatched,
                retryable = flag("retryable"),
                resumeAllowed = flag("resume_allowed", "resumeAllowed"),
                requiresReconciliation = if (flag("command_outcome_unknown") == true || dispatchState == "UNKNOWN_OUTCOME") true else flag("requires_reconciliation", "requiresReconciliation"),
                remoteAcceptance = text("remote_acceptance", "remoteAcceptance")?.uppercase(Locale.ROOT)?.takeIf { it in setOf("YES", "NO", "UNKNOWN") },
                jobState = text("active_job_state", "job_state", "jobState")?.uppercase(Locale.ROOT)?.takeIf { it in jobStates },
                recoveryAction = recoveryAction,
                reasonCode = reasonCode,
                requiredCriteria = requiredCriteria,
                verifiedCriteria = verifiedCriteria,
                remainingCriteria = remainingCriteria,
                missingFields = names(fieldNames, "missing_fields"),
                invalidFields = names(fieldNames, "invalid_fields"),
                runIdHash = runIdHash,
                invocationIdHash = invocationIdHash,
                callIdHash = callIdHash,
                jobIdHash = jobIdHash,
                activeJobIdHash = identifierHash("job", text("active_job_id", "activeJobId")),
                originalFailureCode = originalCode,
                originalHttpStatus = originalStatus,
                checkpointBindingHash = recoveryHash("checkpoint_binding", "checkpoint-binding", Regex("[a-f0-9]{64}")),
                checkpointDigestHash = recoveryHash("checkpoint_digest", "checkpoint-digest", Regex("[a-f0-9]{64}")),
                resumeClaimIdHash = recoveryHash("resume_claim_id", "resume-claim", Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")),
                ownershipFence = number("ownership_fence")?.takeIf { localRecovery != null && it >= 0 },
                diagnostics = diagnostics
            ).withAvailability()
        }

        /** Domain-separated hashes join an explicit ID without publishing the original provider value. */
        fun identifierHash(kind: String, value: String?): String? = value?.takeIf { it.isNotBlank() && it.length <= 256 && it !in setOf("-1", "unknown") }?.let { digest(kind, it) }

        private fun digest(kind: String, value: String): String = MessageDigest.getInstance("SHA-256")
            .digest("gptmobile.issue-context.v1:$kind:$value".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
