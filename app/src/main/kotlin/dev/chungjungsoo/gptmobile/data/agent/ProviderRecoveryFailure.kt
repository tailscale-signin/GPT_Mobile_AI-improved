package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.research.ResearchRecoveryEvidence
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A local recovery refusal is distinct from the earlier provider interruption. */
internal class ProviderRecoveryFailure(val code: String, val original: JsonObject? = null, val correlation: JsonObject = JsonObject(emptyMap())) : IllegalStateException(code) {
    init {
        kotlin.require(code in CODES)
    }

    val nextAction: String get() = if (code == "PROVIDER_WAIT_OUTCOME_UNKNOWN") "RECONCILE_TOOL_RECEIPTS" else "REVIEW_SAVED_RECOVERY"

    fun event(): ProviderEvent.Failed = ProviderEvent.Failed(
        if (code == "RESEARCH_RECOVERY_ACTION_CHANGED") ResearchRecoveryEvidence.message(correlation) else "Saved work could not resume because its recovery evidence needs review. Completed tool receipts remain retained.",
        errorCode = code,
        taskOutcome = "BLOCKED",
        failureDetails = buildJsonObject {
            put("failed_stage", "lifecycle")
            put("code", code)
            put("task_outcome", "BLOCKED")
            put("failure_origin", "local_recovery")
            put("recovery_code", code)
            put("attempt_dispatch_state", "PRECHECK_REJECTED")
            put("dispatch_evidence_scope", "RECOVERY_ATTEMPT")
            put("dispatched", false)
            put("dispatch_state", "PRECHECK_REJECTED")
            put("remote_acceptance", "NO")
            put("retryable", false)
            put("request_replay_allowed", false)
            put("logical_continuation_allowed", false)
            put("resume_allowed", false)
            put("user_input_required", false)
            put("requires_reconciliation", code == "PROVIDER_WAIT_OUTCOME_UNKNOWN")
            put("next_action", nextAction)
            original?.let { put("original_interruption", it) }
            correlation.filterKeys { it in CORRELATION_KEYS }.forEach { (key, value) -> put(key, value) }
            if (code == "RESEARCH_RECOVERY_ACTION_CHANGED") ResearchRecoveryEvidence.retained(correlation).forEach { (key, value) -> put(key, value) }
        }
    )

    fun block(checkpoint: JsonObject): JsonObject = JsonObject(
        checkpoint + buildJsonObject {
            put("state", "BLOCKED_RECOVERY")
            put("resume_allowed", false)
            put("user_input_required", false)
            put("requires_reconciliation", code == "PROVIDER_WAIT_OUTCOME_UNKNOWN")
            put("next_action", nextAction)
            put("current_failure_code", code)
            put("latest_recovery_failure", requireNotNull(event().failureDetails))
            interruption(checkpoint)?.let { put("original_interruption", it) }
        }
    )

    companion object {
        val CODES = setOf("PROVIDER_WAIT_CHECKPOINT_CHANGED", "PROVIDER_WAIT_EVIDENCE_UNAVAILABLE", "PROVIDER_WAIT_OUTCOME_UNKNOWN", "PROVIDER_WAIT_TASK_CHANGED", "PROVIDER_WAIT_CHECKPOINT_INVALID", "PROVIDER_WAIT_INPUT_CHANGED", "PROVIDER_WAIT_PREFIX_CHANGED", "PROVIDER_WAIT_REVIEW_REQUIRED", "RESEARCH_RECOVERY_ACTION_CHANGED")
        private val CORRELATION_KEYS = setOf("checkpoint_binding", "checkpoint_digest", "resume_claim_id", "ownership_fence", "run_id", "observed_checkpoint_digest", "observed_checkpoint_state", "checkpoint_mismatch_reason", "expected_tool_journal_digest", "observed_tool_journal_digest", "expected_tool_evidence_digest", "observed_tool_evidence_digest", "tool_checkpoint_binding_sha256", "queued_tool_count", "unsettled_tool_count", "settled_tool_count", "checkpoint_validation_stage")
        val CHECKPOINT_MISMATCH_REASONS = setOf("MISSING_CHECKPOINT", "STATE_CHANGED", "RESUME_DISALLOWED", "DIGEST_CHANGED", "TASK_BINDING_CHANGED", "TOOL_BINDING_CHANGED", "EVIDENCE_DIGEST_INVALID", "TOOL_OUTCOME_UNKNOWN", "TOOL_RESULTS_PENDING", "TOOL_JOURNAL_CHANGED", "TOOL_RECEIPTS_CHANGED", "TOOL_REPLAY_PROJECTION_UNPROVEN", "TOOL_RESULT_SEMANTICS_UNPROVEN", "TOOL_RESULT_SEMANTICS_CHANGED", "TOOL_JOURNAL_CHANGED_DURING_RESTORE", "RECOVERY_OWNER_UNAVAILABLE", "CHECKPOINT_COMPARE_AND_SET_REJECTED")
        val CHECKPOINT_STATES = setOf("WAITING_PROVIDER_LIMIT", "WAITING_FOR_CONNECTION", "TOOL_RESULTS_READY", "TOOL_HANDOFF", "BLOCKED_RECOVERY", "COMPLETED", "PARTIAL", "FAILED", "GENERATING", "WRITING", "NO_OUTPUT_REPAIR", "OUTPUT_LIMIT", "INTERRUPTED", "BLOCKED_NO_PROGRESS", "UNKNOWN")

        fun checkpointCorrelation(expected: dev.chungjungsoo.gptmobile.data.network.GitHubProviderCheckpoint, observed: JsonObject?, reason: String? = null): JsonObject = buildJsonObject {
            correlation(expected, null, null, null).forEach { (key, value) -> put(key, value) }
            val state = (observed?.get("state") as? JsonPrimitive)?.contentOrNull
            put("observed_checkpoint_state", state?.takeIf { it in CHECKPOINT_STATES } ?: "UNKNOWN")
            observed?.let {
                put("observed_checkpoint_digest", java.security.MessageDigest.getInstance("SHA-256").digest(it.toString().toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) })
            }
            val mismatch = reason?.takeIf { it in CHECKPOINT_MISMATCH_REASONS } ?: when {
                observed == null -> "MISSING_CHECKPOINT"
                state != "WAITING_PROVIDER_LIMIT" -> "STATE_CHANGED"
                observed["resume_allowed"] != JsonPrimitive(true) -> "RESUME_DISALLOWED"
                !expected.matches(observed) -> "DIGEST_CHANGED"
                else -> null
            }
            mismatch?.let { put("checkpoint_mismatch_reason", it) }
        }

        fun correlation(checkpoint: dev.chungjungsoo.gptmobile.data.network.GitHubProviderCheckpoint?, claimId: String?, fence: Long?, runId: String?): JsonObject = buildJsonObject {
            checkpoint?.let {
                put("checkpoint_binding", it.binding)
                put("checkpoint_digest", it.digest)
            }
            claimId?.let { put("resume_claim_id", it) }
            fence?.let { put("ownership_fence", it) }
            runId?.let { put("run_id", it) }
        }

        /** Persist closed local facts, never an arbitrary exception or checkpoint body. */
        fun retainedDetails(details: JsonObject?): JsonObject? {
            if (details == null) return null
            val code = (details["recovery_code"] as? JsonPrimitive)?.contentOrNull
            // Continuation wrappers also carry historical interruption facts. They
            // are observations, not a local recovery refusal or retry authority.
            val interruptions = retainedInterruptions(details)
            if (code !in CODES) return interruptions
            return buildJsonObject {
                details.filterKeys { it in CORRELATION_KEYS || it in setOf("recovery_code", "failure_origin", "next_action", "attempt_dispatch_state", "dispatch_evidence_scope") }
                    .filterValues { it is JsonPrimitive && it.content.length <= 160 }.forEach { (key, value) -> put(key, value) }
                if (code == "RESEARCH_RECOVERY_ACTION_CHANGED") ResearchRecoveryEvidence.retained(details).forEach { (key, value) -> put(key, value) }
                interruptions?.forEach { (key, value) -> put(key, value) }
            }
        }

        fun retainedInterruptions(details: JsonObject?): JsonObject? = buildJsonObject {
            for (key in listOf("original_interruption", "latest_interruption")) {
                retainedInterruption(details?.get(key) as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { put(key, it) }
            }
        }.takeIf { it.isNotEmpty() }

        fun retainedInterruption(original: JsonObject?): JsonObject? = original?.let {
            buildJsonObject {
                (original["code"] as? JsonPrimitive)?.takeIf { it.content.length <= 96 }?.let { put("code", it) }
                (original["details"] as? JsonObject)?.let { facts ->
                    put("details", JsonObject(facts.filterKeys { it in setOf("code", "failed_stage", "http_status", "dispatched", "dispatch_state", "remote_acceptance", "retry_after_ms", "retry_at_ms", "retry_source", "consecutive_rejections", "invocation_id", "run_id", "provider_failure_kind", "provider_limit_source", "request_replay_allowed", "logical_continuation_allowed", "generation_completed", "retryable") }.filterValues { it is JsonPrimitive && it.content.length <= 160 }))
                }
            }
        }

        fun interruption(checkpoint: JsonObject): JsonObject? = retainedInterruption(checkpoint["original_interruption"] as? JsonObject) ?: checkpointInterruption(checkpoint)

        fun latestInterruption(checkpoint: JsonObject): JsonObject? = retainedInterruption(checkpoint["latest_interruption"] as? JsonObject) ?: checkpointInterruption(checkpoint) ?: interruption(checkpoint)

        private fun checkpointInterruption(checkpoint: JsonObject): JsonObject? = checkpoint["failure_code"]?.let { code ->
            retainedInterruption(
                buildJsonObject {
                    put("code", code)
                    (checkpoint["failure_details"] as? JsonObject)?.let { put("details", it) }
                }
            )
        }

        /** Capture the physical invocation even when its adapter did not repeat it in details. */
        fun interruptionFrom(failure: ProviderEvent.Failed): JsonObject? =
            retainedInterruption(failure.failureDetails?.get("latest_interruption") as? JsonObject) ?: (failure.errorCode ?: failure.providerLimit?.httpStatus?.toString() ?: failure.providerLimit?.category)?.let { code ->
                retainedInterruption(
                    buildJsonObject {
                        put("code", code)
                        put(
                            "details",
                            buildJsonObject {
                                failure.failureDetails?.forEach { (key, value) -> put(key, value) }
                                failure.invocationId?.let { put("invocation_id", it) }
                                failure.providerLimit?.let { limit ->
                                    if (failure.failureDetails?.get("failed_stage") == null) put("failed_stage", "model_invocation")
                                    put("retry_at_ms", limit.retryAt)
                                    limit.httpStatus?.let { put("http_status", it) }
                                    limit.retrySource?.let { put("retry_source", it) }
                                    put("consecutive_rejections", limit.consecutiveRejections)
                                }
                            }
                        )
                    }
                )
            }

        /** A local terminal wrapper explains its own refusal without discarding the provider cause. */
        fun withInterruption(failure: ProviderEvent.Failed, checkpoint: JsonObject?, currentFailureIsCause: Boolean = false): ProviderEvent.Failed {
            val original = checkpoint?.let(::interruption) ?: return failure
            val latest = if (currentFailureIsCause) interruptionFrom(failure) else latestInterruption(checkpoint)
            return failure.copy(
                failureDetails = buildJsonObject {
                    failure.failureDetails?.forEach { (key, value) -> put(key, value) }
                    put("original_interruption", original)
                    latest?.let { put("latest_interruption", it) }
                }
            )
        }

        fun require(condition: Boolean, code: String, correlation: JsonObject = JsonObject(emptyMap())) {
            if (!condition) throw ProviderRecoveryFailure(code, correlation = correlation)
        }
    }
}
