package dev.chungjungsoo.gptmobile.data.agent

internal data class ToolBatchPlan(
    val executable: List<ProviderEvent.ToolCall>,
    val deferred: List<ProviderEvent.ToolCall>,
    val perToolSuppressedIds: Set<String>,
    val hostSuppressedIds: Set<String>,
    val disabledNames: Set<String>,
    val acceptedByName: Map<String, Int>
)

/** Pure dispatch planning, separated from the suspending execution/UI coordinator. */
internal fun planToolBatch(
    calls: List<ProviderEvent.ToolCall>,
    remaining: Int,
    availableNames: Set<String>,
    usedByName: Map<String, Int>,
    blockedScopes: Set<String>,
    sameToolLimit: Int,
    scope: (ProviderEvent.ToolCall) -> String
): ToolBatchPlan {
    val executable = mutableListOf<ProviderEvent.ToolCall>()
    val deferred = mutableListOf<ProviderEvent.ToolCall>()
    val suppressed = mutableSetOf<String>()
    val hostSuppressed = mutableSetOf<String>()
    val disabled = mutableSetOf<String>()
    val accepted = mutableMapOf<String, Int>()
    calls.forEachIndexed { index, call ->
        val used = (usedByName[call.name] ?: 0) + (accepted[call.name] ?: 0)
        val repeatLimit = if (Regex("(?i)context[ _-]*retrieve|search[ _-]*nodes|read[ _-]*graph|open[ _-]*nodes").containsMatchIn(call.name)) 3 else sameToolLimit
        when {
            index >= remaining -> deferred += call
            call.name !in availableNames -> {
                deferred += call
                suppressed += call.callId
            }
            scope(call) in blockedScopes -> {
                deferred += call
                hostSuppressed += call.callId
            }
            used >= repeatLimit -> {
                deferred += call
                suppressed += call.callId
                disabled += call.name
            }
            else -> {
                executable += call
                accepted[call.name] = (accepted[call.name] ?: 0) + 1
            }
        }
    }
    return ToolBatchPlan(executable, deferred, suppressed, hostSuppressed, disabled, accepted)
}
