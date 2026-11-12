package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Task state is persisted alongside its receipts, independently of a provider's terminal frame. */
internal object GitHubTaskLifecycle {
    enum class State { PLANNED, RUNNING, WAITING_FOR_APPROVAL, WAITING_FOR_CONNECTION, REJECTED, BLOCKED, PARTIAL, FAILED, COMPLETED }

    fun progress(failure: ProviderEvent.Failed?, receiptCount: Int): State = when {
        failure == null -> State.RUNNING
        failure.errorCode == "WAITING_FOR_APPROVAL" -> State.WAITING_FOR_APPROVAL
        failure.taskOutcome == "REJECTED" || failure.errorCode == "APPROVAL_REJECTED" -> State.REJECTED
        failure.failureDetails?.get("recovery_state") == JsonPrimitive("WAITING_FOR_CONNECTION") -> State.WAITING_FOR_CONNECTION
        failure.taskOutcome in setOf("BLOCKED", "CONFIGURATION_INVALIDATED") -> State.BLOCKED
        failure.errorCode == "GITHUB_REVIEW_BLOCKED" || failure.errorCode == "REVIEW_INPUT_REQUIRED" -> State.BLOCKED
        ProfileFailureRecord.fromProvider(failure.message, failure.errorCode, failure.failureDetails).isTaskVerification -> State.PARTIAL
        !ProfileFailureRecord.fromProvider(failure.message, failure.errorCode, failure.failureDetails).retryAllowed -> State.BLOCKED
        receiptCount > 0 || failure.taskOutcome == "PARTIAL" -> State.PARTIAL
        else -> State.FAILED
    }

    fun terminal(verdict: JsonObject): State {
        val requested = (verdict["task_outcome"] as? JsonPrimitive)?.contentOrNull
        val checklist = verdict["acceptance_ledger"] as? JsonObject
        return when {
            verdict["blocking_condition"] == JsonPrimitive("WAITING_FOR_APPROVAL") || verdict["task_state"] == JsonPrimitive("WAITING_FOR_APPROVAL") -> State.WAITING_FOR_APPROVAL
            requested == "REJECTED" || verdict["task_state"] == JsonPrimitive("REJECTED") -> State.REJECTED
            verdict["recovery_state"] == JsonPrimitive("WAITING_FOR_CONNECTION") -> State.WAITING_FOR_CONNECTION
            requested == "BLOCKED" || requested == "CONFIGURATION_INVALIDATED" -> State.BLOCKED
            requested == "COMPLETED" &&
                verdict["completion_verified"] == JsonPrimitive(true) &&
                verdict["response_delivered"] == JsonPrimitive(true) &&
                checklist?.get("completion_verified") == JsonPrimitive(true) &&
                verdict["evidence_retention_complete"] != JsonPrimitive(false) -> State.COMPLETED
            requested == "FAILED" -> State.FAILED
            else -> State.PARTIAL
        }
    }

    fun configurationInvalidated(policy: dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException): ProviderEvent.Failed = ProviderEvent.Failed(
        policy.message.orEmpty(),
        errorCode = "DISPATCH_POLICY_CHANGED",
        taskOutcome = "CONFIGURATION_INVALIDATED",
        failureDetails = buildJsonObject {
            put("failed_stage", "provider_admission")
            put("dispatched", false)
            put("changed_policy_fields", kotlinx.serialization.json.JsonArray(policy.fields.map(::JsonPrimitive)))
            put("resume_required", true)
            put("recovery", "Revalidate the selected profile, connection and grants, then resume from retained receipts. Reconcile uncertain writes before any mutation.")
        }
    )

    /** Use this exact delivered verdict for fresh and retained reports; never borrow another run. */
    fun acceptanceFailureDetails(verdict: JsonObject, automaticContinuations: Int? = null): JsonObject = buildJsonObject {
        val originalFailure = verdict["original_failure"] as? JsonObject
        val extractionCode = (verdict["failure_code"] as? JsonPrimitive)?.contentOrNull
        val extractionFailure = (verdict["verification_failure"] as? JsonObject)?.takeIf { it["failed_stage"] == JsonPrimitive("claim_extraction") }
        if (originalFailure == null) {
            put("failed_stage", if (extractionFailure != null || extractionCode?.startsWith("GITHUB_EXTRACTION_") == true) "claim_extraction" else "acceptance_verification")
        } else {
            // A saved primary cause remains primary. Historical attempt flags do
            // not describe this retained report's current local delivery.
            listOf("failed_stage", "review_stage")
                .forEach { key -> originalFailure[key]?.let { put(key, it) } }
        }
        extractionFailure?.get("batch")?.let { put("extraction_batch", it) }
        val extractionReason = ((extractionFailure?.get("code") as? JsonPrimitive)?.contentOrNull ?: extractionCode)
            ?.takeIf { it in setOf("GITHUB_EXTRACTION_OUTPUT_LIMIT", "GITHUB_EXTRACTION_INVALID_RESPONSE", "GITHUB_EXTRACTION_TIMEOUT", "GITHUB_EXTRACTION_UNAVAILABLE", "GITHUB_EXTRACTION_FAILED") }
        if (originalFailure == null) extractionReason?.let { put("reason_code", it) }
        listOf("extraction_batches_completed", "extraction_batches_total").forEach { key -> verdict[key]?.let { put(key, it) } }
        val stop = verdict["stop_reason"] as? JsonObject
        put("stop_reason", originalFailure?.get("stop_reason") ?: stop?.get("code") ?: originalFailure?.get("code") ?: JsonPrimitive("UNVERIFIED_ACCEPTANCE_CRITERIA"))
        stop?.let { put("acceptance_stop", it) }
        val domain = originalFailure?.get("failure_domain") ?: verdict["failure_domain"]
        if (domain != null || originalFailure == null) put("failure_domain", domain ?: JsonPrimitive("APPLICATION_VERIFICATION"))
        val ledger = verdict["execution_ledger"] as? JsonObject
        val unresolved = (ledger?.get("unresolved_remote_outcomes") as? JsonPrimitive)?.intOrNull?.let { it > 0 } == true
        val extractionRecovery = extractionFailure?.get("recovery_action").takeIf { originalFailure == null }
        val recovery = if (unresolved) JsonPrimitive("RECONCILE_REMOTE_OUTCOME") else originalFailure?.get("recovery_action") ?: originalFailure?.get("next_action") ?: extractionRecovery ?: stop?.get("recovery_action") ?: ledger?.get("recovery")
        if (recovery != null || originalFailure == null) put("recovery_action", recovery ?: JsonPrimitive("RESUME_UNFINISHED_WORK"))
        automaticContinuations?.let { put("automatic_continuations", it) }
        listOf("required_criteria", "verified_criteria", "remaining_criteria").forEach { key -> ledger?.get(key)?.let { put(key, it) } }
    }

    /** Absence is explicitly unknown; prior tool dispatch does not prove review dispatch. */
    fun failureMetadata(failure: ProviderEvent.Failed): JsonObject {
        val classification = ProfileFailureRecord.fromProvider(failure.message, failure.errorCode, failure.failureDetails)
        val details = failure.failureDetails.orEmpty()
        val review = failure.errorCode?.startsWith("GITHUB_REVIEW_") == true || classification.phase == "answer_review" || details["review_stage"] == JsonPrimitive("answer_review")
        val outcome = buildJsonObject {
            put("schema", "github.failure-outcome.v1")
            put("failure_code", failure.errorCode ?: classification.category)
            put("failure_domain", if (classification.isTaskVerification) "TASK_VERIFICATION" else "EXECUTION")
            put("failure_scope", "CURRENT_STAGE")
            if (classification.isTaskVerification) put("continuation_required", true)
            put("failed_stage", details["failed_stage"] ?: JsonPrimitive(if (review) "answer_review" else classification.phase.ifBlank { "stage_not_recorded" }))
            if (review) put("review_stage", "answer_review")
            details["dispatched"]?.let { put("failed_stage_dispatched", it) }
                ?: classification.dispatched?.let { put("failed_stage_dispatched", it) }
            put("retryable_without_new_input", details["retryable_without_new_input"] != JsonPrimitive(false) && failure.taskOutcome !in setOf("BLOCKED", "CONFIGURATION_INVALIDATED") && classification.retryAllowed)
            put("remote_acceptance", classification.remoteAcceptance.name)
            put("remote_acceptance_scope", "CURRENT_STAGE_ONLY_PRIOR_RECEIPTS_UNCHANGED")
            classification.serverRequestId?.let { put("server_request_id", it) }
            classification.generationCompleted?.let { put("generation_completed", it) }
            classification.requestReplayAllowed?.let { put("request_replay_allowed", it) }
            if (classification.causeTypes.isNotEmpty()) put("cause_types", kotlinx.serialization.json.JsonArray(classification.causeTypes.map(::JsonPrimitive)))
            if (failure.errorCode == "WAITING_FOR_APPROVAL") {
                put("user_input_required", true)
                put("blocking_condition", "WAITING_FOR_APPROVAL")
            }
            details["recovery_state"]?.let { put("recovery_state", it) }
            details["pending_approvals"]?.let { put("pending_approvals", it) }
            details["changed_policy_fields"]?.let { put("changed_policy_fields", it) }
            details["resume_required"]?.let { put("resume_required", it) }
            details["missing_requirements"]?.let { put("missing_requirements", it) }
            details["corrective_actions"]?.let { put("corrective_actions", it) }
            listOf(
                "stop_reason", "next_action", "resume_allowed", "resume_kind", "user_input_required", "requires_reconciliation",
                "automatic_retry_allowed", "user_resumable", "resume_requires_changed_input_or_configuration",
                "pending_invocation_fingerprints", "continuations_used", "continuation_limit", "rounds_used", "round_limit",
                "tool_calls_used", "tool_call_limit", "tool_calls_remaining", "execution_state"
            ).forEach { key -> details[key]?.let { put(key, it) } }
            if (failure.errorCode == "OUTPUT_CONTINUATION_PENDING_TOOLS") {
                put("execution_state", "WAITING_FOR_RECEIPT_RECONCILIATION")
                put("next_action", "RECONCILE_TOOL_RECEIPTS")
                put("resume_allowed", false)
                put("retryable_without_new_input", false)
            }
        }
        return JsonObject(outcome + ("failure_outcome" to outcome))
    }
}
