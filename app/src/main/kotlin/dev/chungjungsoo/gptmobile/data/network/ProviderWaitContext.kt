package dev.chungjungsoo.gptmobile.data.network

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A visible wait belongs to the existing response channel; Stop still cancels the delay. */
internal class ProviderWaitContext(val notifyRemaining: suspend (Long) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ProviderWaitContext>
}
