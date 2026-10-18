package dev.chungjungsoo.gptmobile.data.telemetry.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExecutionOwner
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.OwnedAgentTool
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Child calls inherit their parent's collection/temporary-chat decision and never acquire a budget permit. */
class ObservedTool(private val delegate: AgentTool, private val identity: ToolIdentity) : OwnedAgentTool {
    override val definition = delegate.definition
    override val managesExecutionBudget = delegate.managesExecutionBudget
    override val coordinatesChildWork = delegate.coordinatesChildWork
    override val executionOwner = (delegate as? OwnedAgentTool)?.executionOwner ?: AgentToolExecutionOwner.CLIENT
    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val parent = currentCoroutineContext()[ToolStatisticsContext] ?: return delegate.execute(callId, arguments)
        if (parent.subject == identity.key) return delegate.execute(callId, arguments)
        val id = parent.repository.begin(identity, parent.profile, parent.root, executionOwner.name, callId)
            ?: return delegate.execute(callId, arguments)
        var result: AgentToolResult? = null
        val started = System.nanoTime()
        try {
            return withContext(ToolStatisticsContext(parent.repository, id, identity.key, parent.profile, parent.root)) {
                delegate.execute(callId, arguments)
            }.also { result = it }
        } finally {
            withContext(NonCancellable) {
                val value = result
                if (value?.dispatched == false) {
                    parent.repository.rejectedBeforeDispatch(id)
                } else {
                    parent.repository.complete(
                        id,
                        when {
                            value == null || value.errorCode in setOf("TIMEOUT_OUTCOME_UNKNOWN", "DISPATCH_OUTCOME_UNKNOWN") -> ToolOutcome.UNKNOWN
                            value.isError -> ToolOutcome.FAILURE
                            else -> ToolOutcome.SUCCESS
                        },
                        when {
                            value == null -> ToolDelivery.FAILED
                            value.outputBudgetExhausted || (value.retainedContent != null && value.retainedContent != value.content) -> ToolDelivery.TRUNCATED
                            else -> ToolDelivery.DELIVERED
                        },
                        value?.errorCode,
                        (System.nanoTime() - started) / 1_000_000,
                        value?.sharedResult == true
                    )
                }
            }
        }
    }
}
