package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException
import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** The admitted policy survives preparation, provider queues and native callbacks. */
internal class PrivateTurn private constructor(
    private val store: PrivacyModeStore,
    val conversation: ConversationPrivacyContext,
    val policy: PrivacyModeState,
    private val owner: Job?
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PrivateTurn> {
        suspend fun capture(store: PrivacyModeStore): PrivateTurn {
            val context = currentCoroutineContext()
            val conversation = context[ConversationPrivacyContext] ?: ConversationPrivacyContext.capture() ?: ConversationPrivacyContext(null, false)
            return PrivateTurn(store, conversation, store.effectiveSnapshot(conversation.enabled), context[Job])
        }
    }

    suspend fun validate() {
        owner?.ensureActive()
        currentCoroutineContext().ensureActive()
        if (store.effectiveSnapshot(conversation.enabled) != policy) throw DispatchPolicyChangedException(listOf("devicePrivacy"))
    }

    /** Native IPC reinstalls this guard without the marker, so it owns the captured policy. */
    fun authority(inherited: RequestAuthorizationGuard?): RequestAuthorizationGuard =
        RequestAuthorizationGuard("${inherited?.revision.orEmpty()}:device-privacy-${policy.revision}", inherited?.authority) {
            validate()
            inherited?.validate?.invoke()
            // The inherited profile/tool check may itself suspend.
            validate()
        }
}

/** Capture once at collection, before any chat preparation can suspend or broaden its tools. */
internal fun <T> privateTurnFlow(store: PrivacyModeStore?, block: suspend FlowCollector<T>.() -> Unit): Flow<T> = flow {
    currentCoroutineContext().ensureActive()
    if (store == null) {
        emitAll(flow(block))
    } else {
        val turn = PrivateTurn.capture(store)
        val inherited = currentCoroutineContext()[RequestAuthorizationGuard]
        // Availability never promotes or unlocks a conversation. Protection changes
        // still invalidate an admitted mask, including a change back to prior values.
        val marker = if (turn.policy.enabled) turn else EmptyCoroutineContext
        emitAll(
            flow {
                turn.validate()
                block()
            }.flowOn(turn.conversation + marker + turn.authority(inherited))
        )
    }
}
