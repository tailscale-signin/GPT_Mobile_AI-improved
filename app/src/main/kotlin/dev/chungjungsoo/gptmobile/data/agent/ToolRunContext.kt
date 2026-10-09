package dev.chungjungsoo.gptmobile.data.agent

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Propagates provenance through delegate/crawler wrappers without global mutable request state. */
class ToolRunContext(val runId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolRunContext>
}
fun AgentTool.withRunContext(runId: String): AgentTool {
    val original = this
    return object : OwnedAgentTool {
        override val definition = original.definition
        override val managesExecutionBudget = original.managesExecutionBudget
        override val executionOwner = (original as? OwnedAgentTool)?.executionOwner ?: AgentToolExecutionOwner.CLIENT
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = withContext(ToolRunContext(runId)) { original.execute(callId, arguments) }
    }
}
