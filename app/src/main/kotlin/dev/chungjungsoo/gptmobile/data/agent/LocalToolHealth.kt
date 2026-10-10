package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class LocalToolHealthState(
    val contextTokens: Int = 0,
    val selected: List<String> = emptyList(),
    val omitted: List<String> = emptyList(),
    val evidenceBytes: Int = 0,
    val lastTool: String = "None",
    val lastStatus: String = "No execution yet",
    val lastError: String? = null,
    val dispatched: Boolean = false,
    val compacted: Boolean = false,
    val retainedBytes: Int = 0,
    val admittedBytes: Int = 0,
    val supportingEvidence: Boolean = false
)

/** Operational metadata only. No arguments, locations, credentials or result bodies. */
internal object LocalToolHealth {
    private val mutable = MutableStateFlow(LocalToolHealthState())
    val state = mutable.asStateFlow()
    fun planned(context: Int, selected: List<String>, omitted: List<String>, evidenceBytes: Int) {
        mutable.value = LocalToolHealthState(context, selected, omitted, evidenceBytes)
    }
    fun completed(tool: String, result: AgentToolResult) {
        val admitted = ToolResultEnvelope.element(result.content).toString().toByteArray(Charsets.UTF_8).size
        val retained = ToolResultEnvelope.element(result.retainedContent ?: result.content).toString().toByteArray(Charsets.UTF_8).size
        val status = when (result.errorCode) {
            "MODEL_EVIDENCE_LIMIT", "BUDGET_EXHAUSTED" -> "Blocked by app allowance; provider not implicated"
            "INVALID_ARGUMENTS", "UNKNOWN_TOOL", "REPEATED_FAILURE", "RECONCILIATION_REQUIRED" -> "Blocked before dispatch: ${result.errorCode}"
            "AUTH_REQUIRED" -> "Permission or authentication required"
            "TIMEOUT_OUTCOME_UNKNOWN" -> "Dispatched outcome unknown; reconciliation required"
            else -> if (result.isError) "Provider execution failed" else "Completed"
        }
        mutable.value = mutable.value.copy(lastTool = tool, lastStatus = status, lastError = result.errorCode, dispatched = result.dispatched, compacted = result.retainedContent != null, retainedBytes = retained, admittedBytes = admitted, supportingEvidence = mutable.value.supportingEvidence || (!result.isError && !ToolResultEnvelope.element(result.content).toString().contains("\"evidenceOmitted\":true")))
    }
}
