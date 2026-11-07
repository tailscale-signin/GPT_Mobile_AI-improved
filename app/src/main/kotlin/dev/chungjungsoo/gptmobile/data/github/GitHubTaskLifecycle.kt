package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Task state is persisted alongside its receipts, independently of a provider's terminal frame. */
internal object GitHubTaskLifecycle {
    enum class State { PLANNED, RUNNING, BLOCKED, PARTIAL, FAILED, COMPLETED }

    fun progress(failure: ProviderEvent.Failed?, receiptCount: Int): State = when {
        failure == null -> State.RUNNING
        failure.taskOutcome in setOf("BLOCKED", "CONFIGURATION_INVALIDATED") -> State.BLOCKED
        failure.errorCode == "GITHUB_REVIEW_BLOCKED" || failure.errorCode == "REVIEW_INPUT_REQUIRED" -> State.BLOCKED
        !ProfileFailureRecord.fromProvider(failure.message, failure.errorCode, failure.failureDetails).retryAllowed -> State.BLOCKED
        receiptCount > 0 || failure.taskOutcome == "PARTIAL" -> State.PARTIAL
        else -> State.FAILED
    }

    fun terminal(verdict: JsonObject): State {
        val requested = (verdict["task_outcome"] as? JsonPrimitive)?.contentOrNull
        val checklist = verdict["acceptance_ledger"] as? JsonObject
        return when {
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

    /** Absence is explicitly unknown; prior tool dispatch does not prove review dispatch. */
    fun failureMetadata(failure: ProviderEvent.Failed): JsonObject {
        val classification = ProfileFailureRecord.fromProvider(failure.message, failure.errorCode, failure.failureDetails)
        val details = failure.failureDetails.orEmpty()
        val review = failure.errorCode?.startsWith("GITHUB_REVIEW_") == true || classification.phase == "answer_review"
        return buildJsonObject {
            put("failure_code", failure.errorCode ?: classification.category)
            put("failed_stage", details["failed_stage"] ?: JsonPrimitive(if (review) "answer_review" else classification.phase.ifBlank { "stage_not_recorded" }))
            details["dispatched"]?.let { put("failed_stage_dispatched", it) }
                ?: classification.dispatched?.let { put("failed_stage_dispatched", it) }
            put("retryable_without_new_input", failure.taskOutcome !in setOf("BLOCKED", "CONFIGURATION_INVALIDATED") && classification.retryAllowed)
            details["changed_policy_fields"]?.let { put("changed_policy_fields", it) }
            details["resume_required"]?.let { put("resume_required", it) }
            details["missing_requirements"]?.let { put("missing_requirements", it) }
            details["corrective_actions"]?.let { put("corrective_actions", it) }
        }
    }
}
