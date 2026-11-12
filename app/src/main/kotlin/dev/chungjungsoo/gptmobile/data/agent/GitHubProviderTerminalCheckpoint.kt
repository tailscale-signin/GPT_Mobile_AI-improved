package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.network.GitHubProviderCheckpoint
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A terminal owner can retain its evidence, but cannot keep advertising an automatic wake. */
internal object GitHubProviderTerminalCheckpoint {
    fun reconcile(checkpoint: JsonObject, expected: GitHubProviderCheckpoint, status: String, terminalError: String?): JsonObject? {
        if (status !in terminalStatuses || !expected.matches(checkpoint)) return null
        val requiresReview = status !in setOf("COMPLETED", "CANCELED")
        val code = terminalError?.trim()?.let { recoveryGuard.find(it)?.groupValues?.get(1) }
            ?: "PROVIDER_WAIT_TERMINATED"
        val updates = linkedMapOf<String, JsonElement>(
            "state" to JsonPrimitive(status),
            "terminal_run_status" to JsonPrimitive(status),
            "resume_allowed" to JsonPrimitive(false),
            "user_input_required" to JsonPrimitive(requiresReview),
            "blocking_condition" to JsonPrimitive(if (requiresReview) "RECONCILE_RETAINED_RESULTS" else "RUN_TERMINAL"),
            "next_action" to JsonPrimitive(if (requiresReview) "RECONCILE_RETAINED_RESULTS" else "NONE")
        )
        if (status != "COMPLETED") {
            updates["failure_code"] = JsonPrimitive(code)
            updates["failure_details"] = JsonObject(
                mapOf(
                    "code" to JsonPrimitive(code),
                    "failed_stage" to JsonPrimitive("provider_recovery"),
                    "retryable" to JsonPrimitive(false),
                    "request_replay_allowed" to JsonPrimitive(false),
                    "resume_allowed" to JsonPrimitive(false),
                    "user_input_required" to JsonPrimitive(requiresReview)
                )
            )
        }
        (checkpoint["failure_code"] as? JsonPrimitive)?.contentOrNull?.let {
            updates["prior_interruption_code"] = JsonPrimitive(it)
        }
        checkpoint["failure_details"]?.let { updates["prior_interruption_details"] = it }
        return JsonObject(checkpoint - setOf("failure_code", "failure_details") + updates)
    }

    val terminalStatuses = setOf("COMPLETED", "FAILED", "CANCELED", "INTERRUPTED", "PARTIAL", "EVIDENCE_MISSING", "BLOCKED", "WAITING_FOR_APPROVAL")
    private val recoveryGuard = Regex("^(?:An unexpected error occurred: )?(PROVIDER_WAIT_CHECKPOINT_CHANGED|RESEARCH_RECOVERY_ACTION_CHANGED|PROVIDER_WAIT_REVIEW_REQUIRED|PROVIDER_WAIT_INPUT_CHANGED|PROVIDER_WAIT_PREFIX_CHANGED|PROVIDER_WAIT_CHECKPOINT_INVALID|USER_CANCELLED|SERVICE_STOPPED|EXCLUDED_FROM_COMBINED_TURN)(?=$|[:;])")
}
