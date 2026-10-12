package dev.chungjungsoo.gptmobile.data.telemetry.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExecutionOwner
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.OwnedAgentTool
import dev.chungjungsoo.gptmobile.data.agent.PreparableAgentTool
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Changes attribution only. The parent budget still owns authorization and collection consent. */
class ToolStatisticsProfile(val uid: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolStatisticsProfile>
}

fun AgentTool.forStatisticsProfile(uid: String): AgentTool {
    val delegate = this
    return object : PreparableAgentTool, OwnedAgentTool {
        override val definition = delegate.definition
        override val managesExecutionBudget = delegate.managesExecutionBudget
        override val executionOwner = (delegate as? OwnedAgentTool)?.executionOwner ?: AgentToolExecutionOwner.CLIENT
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = prepareExecution(callId, arguments).invoke()
        override suspend fun prepareExecution(callId: String, arguments: JsonObject): suspend () -> AgentToolResult {
            val prepared = withContext(ToolStatisticsProfile(uid)) {
                if (delegate is PreparableAgentTool) delegate.prepareExecution(callId, arguments) else suspend { delegate.execute(callId, arguments) }
            }
            return { withContext(ToolStatisticsProfile(uid)) { prepared() } }
        }
    }
}
