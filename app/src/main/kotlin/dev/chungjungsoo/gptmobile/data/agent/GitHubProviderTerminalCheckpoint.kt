package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.network.GitHubProviderCheckpoint
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A terminal owner can retain its evidence, but cannot keep advertising an automatic wake. */
internal object GitHubProviderTerminalCheckpoint {
    fun reconcile(checkpoint: JsonObject, expected: GitHubProviderCheckpoint, status: String, terminalError: String?, recoveryFailure: JsonObject? = null, correlation: JsonObject = JsonObject(emptyMap())): JsonObject? {
        if (status !in terminalStatuses || !expected.matches(checkpoint)) return null
        val requiresReview = status !in setOf("COMPLETED", "CANCELED")
        val code = (recoveryFailure?.get("recovery_code") as? JsonPrimitive)?.contentOrNull?.takeIf { it in ProviderRecoveryFailure.CODES }
            ?: terminalError?.trim()?.let { recoveryGuard.find(it)?.groupValues?.get(1) }
            ?: "PROVIDER_WAIT_TERMINATED"
        if (requiresReview && code in ProviderRecoveryFailure.CODES) {
            val failure = ProviderRecoveryFailure(code, ProviderRecoveryFailure.interruption(checkpoint), correlation)
            return JsonObject(failure.block(checkpoint) + ("terminal_run_status" to JsonPrimitive(status)))
        }
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
                    "failed_stage" to JsonPrimitive("lifecycle"),
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
        ProviderRecoveryFailure.interruption(checkpoint)?.let { updates["original_interruption"] = it }
        return JsonObject(checkpoint - setOf("failure_code", "failure_details") + updates)
    }

    val terminalStatuses = setOf("COMPLETED", "FAILED", "CANCELED", "INTERRUPTED", "PARTIAL", "EVIDENCE_MISSING", "BLOCKED", "WAITING_FOR_APPROVAL")
    private val recoveryGuard = Regex("^(?:An unexpected error occurred: )?(${(ProviderRecoveryFailure.CODES + setOf("USER_CANCELLED", "SERVICE_STOPPED", "EXCLUDED_FROM_COMBINED_TURN")).joinToString("|")})(?=$|[:;])")
}
