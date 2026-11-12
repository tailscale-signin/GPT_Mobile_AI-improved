package dev.chungjungsoo.gptmobile.data.diagnostics

import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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
    val missingFields: List<String>? = null,
    val invalidFields: List<String>? = null,
    val runIdHash: String? = null,
    val invocationIdHash: String? = null,
    val callIdHash: String? = null,
    val jobIdHash: String? = null,
    val activeJobIdHash: String? = null,
    val exceptionFingerprint: String? = null
) {
    fun withException(type: String?, frames: List<String>): IssueReportContext =
        if (type == null) this else copy(exceptionFingerprint = digest("exception", (listOf(type) + frames).joinToString("\n"))).withAvailability()

    private fun withAvailability(): IssueReportContext {
        val present = IssueReport.json.encodeToJsonElement(serializer(), this).jsonObject.keys
        return copy(unavailableFields = optionalFields.filterNot { it in present })
    }

    companion object {
        val sources = setOf("CHAT_FAILURE", "STRUCTURED_EVENT", "TOOL_RESULT", "VOICE_STATE", "EXCEPTION", "PROCESS_EXIT", "CODE_ONLY", "MANUAL_REPORT")
        val providers = setOf("OPENAI", "ANTHROPIC", "GOOGLE", "GROQ", "OLLAMA", "OPENROUTER", "LLAMA", "GATEWAY", "LOCAL", "CUSTOM", "LITERT_LM", "FREE", "NVIDIA")
        val operations = setOf("github_context", "github_read", "github_search", "github_pr", "github_issue", "github_ci", "github_commit", "github_review", "github_engineering", "repository_graph", "mcp", "web_search", "read_url", "voice_capture", "voice_recognition", "voice_playback")
        val stages = setOf("admission", "planning", "repository_admission", "provider_admission", "request_validation", "message_admission", "conversation_admission", "backend_admission", "binding_admission", "execution_admission", "connection_preflight", "dns_resolution", "dns", "connection", "response_stream", "tls", "cancelled", "transport", "provider_generation", "model_invocation", "tool_precheck", "tool_readiness", "tool_admission", "task_budget", "inventory_pagination", "tool_dispatch", "tool_execution", "tool_continuation", "tool_reconciliation", "backend_template", "gateway_recovery", "gateway_dispatch", "review_compatibility", "answer_review", "engineering_guard", "claim_extraction", "claim_verification", "verification", "final_synthesis", "delivery", "lifecycle", "voice_capture", "voice_recognition", "voice_playback")
        val jobStates = setOf("QUEUED", "RUNNING", "WAITING_FOR_CLIENT_TOOL", "WAITING_FOR_APPROVAL", "PAUSED", "CANCELLING", "CANCELLED", "COMPLETED", "FAILED")
        val recoveryActions = setOf("OPEN_ACTIVE_JOB", "RETURN_CLIENT_TOOL_RESULTS", "WAIT_FOR_ACTIVE_JOB", "QUEUE_INSTRUCTION", "RESOLVE_ACTIVE_JOB", "RECONCILE_ACTIVE_GATEWAY_JOB", "REFRESH_REPOSITORY_ACCESS", "CHANGE_REPOSITORY", "SELECT_REF", "RECONCILE_OUTCOME", "REVIEW_AUTHORIZATION", "RETRY_AFTER_BACKOFF", "RETRY_AFTER_CONFIGURATION_CHANGE", "MICROPHONE_PERMISSION", "RETRY_RECOGNITION", "RETRY_PLAYBACK", "SPEECH_SERVICE", "SPEECH_LANGUAGE", "LOCAL_MODEL")
        val reasonCodes = setOf("SELECTION_NOT_OBJECT", "SELECTION_SCHEMA_INVALID", "SELECTION_IDENTITY_INVALID", "SELECTION_CREDENTIAL_REFERENCE_INVALID")
        val selectionFields = setOf("profile_uid", "profile_revision", "provider_connection_id", "provider_connection_revision", "provider_kind", "configured_endpoint", "requested_model_id", "generation_parameters", "privacy_scope", "tool_owner", "required_capability_policy", "credential_reference", "credential_account_identity", "approved_alias_mapping")
        val optionalFields = listOf("stage", "provider", "operation", "httpStatus", "retryAfterMillis", "dispatched", "retryable", "resumeAllowed", "requiresReconciliation", "remoteAcceptance", "jobState", "recoveryAction", "reasonCode", "missingFields", "invalidFields", "runIdHash", "invocationIdHash", "callIdHash", "jobIdHash", "activeJobIdHash", "exceptionFingerprint")

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
            fun names(vararg keys: String): List<String>? {
                val items = when (val item = value(*keys)) {
                    is JsonArray -> item
                    is List<*> -> item.takeIf { it.size <= selectionFields.size } ?: return null
                    else -> return null
                }
                if (items.size > selectionFields.size) return null
                val names = items.map { item -> if (item is String) item else (item as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull }
                if (names.any { it !in selectionFields }) return null
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
            return IssueReportContext(
                schemaVersion = 1,
                occurredAtMillis = occurredAtMillis,
                source = source,
                unavailableFields = emptyList(),
                stage = text("failed_stage", "failedStage", "failurePhase", "phase", "stage")?.lowercase(Locale.ROOT)?.takeIf { it in stages },
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
                recoveryAction = text("recovery_action", "next_action", "recoveryAction", "nextAction")?.uppercase(Locale.ROOT)?.takeIf { it in recoveryActions },
                reasonCode = text("reasonCode", "reason_code")?.uppercase(Locale.ROOT)?.takeIf { it in reasonCodes },
                missingFields = names("missing_fields"),
                invalidFields = names("invalid_fields"),
                runIdHash = identifierHash("run", text("run_id", "runId")),
                invocationIdHash = identifierHash("invocation", text("invocation_id", "invocationId")),
                callIdHash = identifierHash("call", text("call_id", "callId", "toolCallId")),
                jobIdHash = identifierHash("job", text("job_id", "jobId", "gatewayJobId")),
                activeJobIdHash = identifierHash("job", text("active_job_id", "activeJobId"))
            ).withAvailability()
        }

        /** Domain-separated hashes join an explicit ID without publishing the original provider value. */
        fun identifierHash(kind: String, value: String?): String? = value?.takeIf { it.isNotBlank() && it.length <= 256 && it !in setOf("-1", "unknown") }?.let { digest(kind, it) }

        private fun digest(kind: String, value: String): String = MessageDigest.getInstance("SHA-256")
            .digest("gptmobile.issue-context.v1:$kind:$value".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
