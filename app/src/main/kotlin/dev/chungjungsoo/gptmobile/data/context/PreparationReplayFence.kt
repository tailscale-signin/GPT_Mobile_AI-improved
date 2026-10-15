package dev.chungjungsoo.gptmobile.data.context

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Rebuilding configuration is safe only before any physical inference or tool attempt. */
internal class PreparationReplayFence : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PreparationReplayFence>
    private val attempted = AtomicBoolean(false)
    fun dispatched() {
        attempted.set(true)
    }
    fun canRebuild(error: Throwable, attempt: Long): Boolean = attempt == 0L &&
        !attempted.get() &&
        ((error is IllegalStateException && error.message?.startsWith("DISPATCH_POLICY_CHANGED:") == true) ||
            (error is IllegalArgumentException && error.message?.startsWith("MEMORY_STALE:") == true))
}
