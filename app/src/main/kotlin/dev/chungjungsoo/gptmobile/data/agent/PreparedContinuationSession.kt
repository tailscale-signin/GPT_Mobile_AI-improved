package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The planner and the wire share the same schemas and exact verified pending tool batch. */
internal class PreparedContinuationSession(
    private val delegate: AgentProviderSession,
    private val plannedTools: List<AgentToolDefinition>,
    private val protocolExchanges: List<AgentToolExchange>
) : AgentProviderSession by delegate {
    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> {
        check(exchanges.isEmpty() || protocolExchanges.isEmpty() || exchanges == protocolExchanges) {
            "CLIENT_TOOL_RESULT_IDENTITY_MISMATCH: A prepared continuation cannot replace its verified pending batch."
        }
        return delegate.streamRound(plannedTools, protocolExchanges.ifEmpty { exchanges }).map { event ->
            if (event is ProviderEvent.RequestConfigured) event.copy(advertisedToolCount = plannedTools.count { it.modelVisible }) else event
        }
    }
}
