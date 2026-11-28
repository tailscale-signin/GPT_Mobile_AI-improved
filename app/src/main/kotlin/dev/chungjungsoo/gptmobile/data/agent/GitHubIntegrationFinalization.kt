package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.GitHubTestChecklist
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A runner stop withholds schemas; it does not revoke the authorized catalog. */
internal object GitHubIntegrationFinalization {
    enum class Reason(val description: String) {
        USER_STOP("Stopped at the saved user decision."),
        UNAVAILABLE_CALLS("The requested calls could not execute in this response."),
        ARGUMENT_REPAIR_EXHAUSTED("The bounded argument corrections made no progress."),
        REPEATED_READS("Repeated reads produced no new evidence."),
        DISCOVERY_STALLED("Repeated discovery exposed no additional tools."),
        MODEL_TOOL_ROUND_LIMIT("The accepted model/tool round limit was reached."),
        COMBINED_WORK_BUDGET_EXHAUSTED("The accepted combined work allowance was exhausted."),
        TOOL_AND_OUTPUT_BUDGET_EXHAUSTED("The shared tool-call and result-byte allowances were exhausted."),
        TOOL_CALL_LIMIT("The accepted executable tool-call allowance was exhausted."),
        TOOL_OUTPUT_BUDGET_EXHAUSTED("The accepted tool-result byte allowance was exhausted."),
        CONTEXT_LIMIT("The remaining request and receipts could not fit the resolved context allowance."),
        CONSECUTIVE_OPERATION_FAILURES("GitHub operations failed for three consecutive rounds."),
        NO_PROGRESS("The GitHub investigation stopped making progress."),
        FINAL_RESPONSE_REQUESTED("The runner stopped execution and requested a final response.")
    }

    fun failureForCheckpoint(failure: ProviderEvent.Failed, checkpoint: JsonObject): ProviderEvent.Failed? {
        val complete = (checkpoint["requirements"] as? JsonArray)?.isNotEmpty() == true &&
            (checkpoint["remaining_requirements"] as? JsonArray)?.isEmpty() == true
        return failure.takeUnless { complete }
    }

    fun failure(
        githubTask: Boolean,
        combined: Boolean,
        task: String?,
        finalResponseRequested: Boolean,
        pendingCalls: Boolean,
        reason: Reason?,
        rounds: Int,
        toolCalls: Int,
        roundLimit: Int,
        toolCallLimit: Int
    ): ProviderEvent.Failed? {
        if (!githubTask || combined || !finalResponseRequested || pendingCalls || !GitHubTestChecklist.integration(task.orEmpty())) return null
        val stop = reason ?: Reason.FINAL_RESPONSE_REQUESTED
        val code = when (stop) {
            Reason.MODEL_TOOL_ROUND_LIMIT -> "FINAL_RESPONSE_REQUIRED"
            Reason.TOOL_CALL_LIMIT, Reason.TOOL_AND_OUTPUT_BUDGET_EXHAUSTED -> "TOOL_BUDGET_EXHAUSTED"
            else -> "GITHUB_TASK_INCOMPLETE"
        }
        return ProviderEvent.Failed(
            stop.description + " Saved operation receipts and unfinished checks are retained; no additional tool was dispatched for finalization.",
            errorCode = code,
            taskOutcome = "BLOCKED",
            failureDetails = buildJsonObject {
                put("failed_stage", "final_synthesis")
                put("stop_reason", stop.name)
                put("finalization_requested", true)
                put("tools_withheld_for_finalization", true)
                put("rounds_used", rounds)
                put("round_limit", roundLimit)
                put("tool_calls_used", toolCalls)
                put("tool_call_limit", toolCallLimit)
                put("dispatched", false)
                put("retryable", false)
                put("permission_bypass_allowed", false)
            }
        )
    }
}
