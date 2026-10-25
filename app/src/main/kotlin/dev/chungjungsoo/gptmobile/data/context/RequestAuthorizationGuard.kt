package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.SavedEvidenceAccessDenied
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Rechecks retained-source permissions at the physical boundary after queueing and serialization. */
internal class RequestAuthorizationGuard(val revision: String, val authority: CapturedProviderAuthority?, val validate: suspend () -> Unit) : AbstractCoroutineContextElement(Key) {
    constructor(revision: String, validate: suspend () -> Unit) : this(revision, null, validate)

    companion object Key : CoroutineContext.Key<RequestAuthorizationGuard>
}

internal fun AgentProviderSession.withRequestAuthorization(revision: String, authority: CapturedProviderAuthority? = null, validate: suspend () -> Unit): AgentProviderSession {
    val delegate = this
    return object : AgentProviderSession by delegate {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> =
            flow {
                // Check before constructing a native session as well as at its physical boundary.
                validate()
                emitAll(delegate.streamRound(tools, exchanges))
            }.flowOn(RequestAuthorizationGuard(revision, authority, validate)).catch { error ->
                if (error is SavedEvidenceAccessDenied) {
                    // An expected access change is a terminal result, not a process-level failure.
                    emit(ProviderEvent.Failed(error.message.orEmpty()))
                } else {
                    throw error
                }
            }
    }
}
