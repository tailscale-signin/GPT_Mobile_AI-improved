package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn

/** Rechecks retained-source permissions at the physical boundary after queueing and serialization. */
internal class RequestAuthorizationGuard(val revision: String, val validate: suspend () -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestAuthorizationGuard>
}

internal fun AgentProviderSession.withRequestAuthorization(revision: String, validate: suspend () -> Unit): AgentProviderSession {
    val delegate = this
    return object : AgentProviderSession by delegate {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> =
            delegate.streamRound(tools, exchanges).flowOn(RequestAuthorizationGuard(revision, validate))
    }
}
