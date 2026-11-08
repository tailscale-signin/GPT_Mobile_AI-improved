package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.github.GitHubRunAcceptance

/** Flow termination is delivery evidence only. Objective acceptance lives in the task/evidence ledger. */
internal data class AgentRunTerminalDiagnostics(
    val reason: String,
    val stage: String,
    val executionState: String,
    val taskVerification: String = "NOT_MEASURED",
    val dispatched: Boolean? = null,
    val remoteAcceptance: RemoteRequestAcceptance = RemoteRequestAcceptance.UNKNOWN,
    val invocationId: String? = null
) {
    companion object {
        fun from(status: String, failure: ProfileFailureRecord?, acceptance: GitHubRunAcceptance.Assessment? = null): AgentRunTerminalDiagnostics {
            val completed = status == AgentRunStatus.COMPLETED
            val unknown = failure?.category in setOf("TOOL_OUTCOME_UNKNOWN", "GATEWAY_OUTCOME_UNKNOWN") || acceptance?.verification == "UNKNOWN_OUTCOME"
            val state = when {
                unknown -> "UNKNOWN_OUTCOME"
                completed -> "COMPLETED"
                failure?.dispatched == false -> "PRECHECK_REJECTED"
                failure?.dispatched == null -> if (acceptance != null) "OBSERVED" else "NOT_RECORDED"
                else -> "FAILED"
            }
            return AgentRunTerminalDiagnostics(
                reason = if (completed) "RESPONSE_FLOW_COMPLETED" else failure?.category ?: "TASK_INCOMPLETE",
                stage = if (completed) "none" else failure?.phase?.takeIf { it.matches(Regex("[a-zA-Z0-9_:-]{1,80}")) } ?: "stage_not_recorded",
                executionState = state,
                taskVerification = when {
                    unknown && acceptance != null -> "UNKNOWN_OUTCOME"
                    acceptance?.verification == "VERIFIED" && !completed -> "PARTIAL"
                    else -> acceptance?.verification ?: "NOT_MEASURED"
                },
                // A rejected recovery attempt does not prove an earlier remote
                // operation was unsent. Keep that ambiguity in the terminal receipt.
                dispatched = if (unknown) true.takeIf { failure?.dispatched == true } else failure?.dispatched.takeUnless { completed },
                remoteAcceptance = if (unknown || completed) RemoteRequestAcceptance.UNKNOWN else failure?.remoteAcceptance ?: RemoteRequestAcceptance.UNKNOWN,
                invocationId = failure?.invocationId.takeUnless { completed }
            )
        }
    }
}
