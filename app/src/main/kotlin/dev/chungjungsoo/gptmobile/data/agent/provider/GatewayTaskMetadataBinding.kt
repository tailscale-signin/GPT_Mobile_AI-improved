package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayResponseMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/** Bind the physical task before a review/UI flow can buffer its public metadata event. */
internal fun AgentProviderSession.withGatewayTaskMetadataBinding(
    requestRole: String,
    bind: suspend (GatewayResponseMetadata) -> Unit
): AgentProviderSession {
    if (!gatewayTaskRole(requestRole) && requestRole != "synthesis") return this
    val delegate = this
    return object : AgentProviderSession by delegate {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> =
            delegate.streamRound(tools, exchanges).onEach { event ->
                if (event is ProviderEvent.GatewayMetadataCaptured && event.metadata.transportVerified && event.metadata.jobId != null) {
                    bind(event.metadata)
                }
            }
    }
}
