package dev.chungjungsoo.gptmobile.data.agent

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
        "Saved work could not resume because its recovery evidence needs review. Completed tool receipts remain retained.",
        errorCode = code,
        taskOutcome = "BLOCKED",
        failureDetails = buildJsonObject {
            put("failed_stage", "lifecycle")
            put("code", code)
            put("task_outcome", "BLOCKED")
            put("failure_origin", "local_recovery")
            put("recovery_code", code)
            put("attempt_dispatch_state", "PRECHECK_REJECTED")
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
        private val CORRELATION_KEYS = setOf("checkpoint_binding", "checkpoint_digest", "resume_claim_id", "ownership_fence", "run_id")

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
            val code = (details["recovery_code"] as? JsonPrimitive)?.contentOrNull ?: return null
            if (code !in CODES) return null
            return buildJsonObject {
                details.filterKeys { it in CORRELATION_KEYS || it in setOf("recovery_code", "failure_origin", "next_action", "attempt_dispatch_state") }
                    .filterValues { it is JsonPrimitive && it.content.length <= 160 }.forEach { (key, value) -> put(key, value) }
                val original = details["original_interruption"] as? JsonObject
                if (original != null) {
                    put(
                        "original_interruption",
                        buildJsonObject {
                            (original["code"] as? JsonPrimitive)?.takeIf { it.content.length <= 96 }?.let { put("code", it) }
                            (original["details"] as? JsonObject)?.let { facts ->
                                put("details", JsonObject(facts.filterKeys { it in setOf("code", "failed_stage", "http_status", "dispatched", "dispatch_state", "remote_acceptance", "retry_after_ms", "retry_at_ms", "retry_source", "consecutive_rejections", "invocation_id", "run_id") }.filterValues { it is JsonPrimitive && it.content.length <= 160 }))
                            }
                        }
                    )
                }
            }
        }

        fun interruption(checkpoint: JsonObject): JsonObject? = (checkpoint["original_interruption"] as? JsonObject) ?: checkpoint["failure_code"]?.let { code ->
            buildJsonObject {
                put("code", code)
                (checkpoint["failure_details"] as? JsonObject)?.let { put("details", it) }
            }
        }

        fun require(condition: Boolean, code: String) {
            if (!condition) throw ProviderRecoveryFailure(code)
        }
    }
}
