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
    val compacted: Boolean = false
)

/** Operational metadata only. No arguments, locations, credentials or result bodies. */
internal object LocalToolHealth {
    private val mutable = MutableStateFlow(LocalToolHealthState())
    val state = mutable.asStateFlow()
    fun planned(context: Int, selected: List<String>, omitted: List<String>, evidenceBytes: Int) {
        mutable.value = LocalToolHealthState(context, selected, omitted, evidenceBytes)
    }
    fun completed(tool: String, result: AgentToolResult) {
        mutable.value = mutable.value.copy(lastTool = tool, lastStatus = if (result.isError) "Error" else "Completed", lastError = result.errorCode, dispatched = result.dispatched, compacted = result.retainedContent != null)
    }
}
