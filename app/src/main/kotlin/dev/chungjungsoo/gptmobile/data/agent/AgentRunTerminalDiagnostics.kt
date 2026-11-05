package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus

/** Flow termination is delivery evidence only. Objective acceptance lives in the task/evidence ledger. */
internal data class AgentRunTerminalDiagnostics(
    val reason: String,
    val stage: String,
    val executionState: String,
    val taskVerification: String = "NOT_MEASURED"
) {
    companion object {
        fun from(status: String, failure: ProfileFailureRecord?): AgentRunTerminalDiagnostics {
            val completed = status == AgentRunStatus.COMPLETED
            val state = when {
                completed -> "COMPLETED"
                failure?.category in setOf("TOOL_OUTCOME_UNKNOWN", "GATEWAY_OUTCOME_UNKNOWN") -> "UNKNOWN_OUTCOME"
                failure?.dispatched == false -> "PRECHECK_REJECTED"
                failure?.dispatched == null -> "UNKNOWN_OUTCOME"
                else -> "FAILED"
            }
            return AgentRunTerminalDiagnostics(
                reason = if (completed) "RESPONSE_FLOW_COMPLETED" else failure?.category ?: "TASK_INCOMPLETE",
                stage = if (completed) "none" else failure?.phase?.takeIf { it.matches(Regex("[a-zA-Z0-9_:-]{1,80}")) } ?: "stage_not_recorded",
                executionState = state
            )
        }
    }
}
