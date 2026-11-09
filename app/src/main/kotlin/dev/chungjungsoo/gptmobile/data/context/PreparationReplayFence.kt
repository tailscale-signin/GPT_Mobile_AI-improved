package dev.chungjungsoo.gptmobile.data.context

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Rebuilding configuration is safe only before any physical inference or tool attempt. */
internal class PreparationReplayFence : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PreparationReplayFence>
    private val attempted = AtomicBoolean(false)
    val hasAttempted: Boolean get() = attempted.get()
    fun dispatched() {
        attempted.set(true)
    }

    /** This is checkpoint planning under a new catalog, never replay of the old request. */
    fun canResumeGitHubChecklist(error: Throwable, attempt: Long, durableChecklist: Boolean): Boolean =
        durableChecklist && attempt < 3L && error is DispatchPolicyChangedException && error.fields.isNotEmpty() && error.fields.all { it == "tools" }

    fun canRebuild(error: Throwable, attempt: Long): Boolean = !attempted.get() &&
        (
            // Re-capture the *entire* policy and evidence snapshot, never replay provider
            // or tool calls. Multiple rapid settings changes may need up to three rebuilds.
            (attempt < 3L && error is IllegalStateException && error.message?.startsWith("DISPATCH_POLICY_CHANGED:") == true) ||
                (attempt == 0L && error is IllegalArgumentException && error.message?.startsWith("MEMORY_STALE:") == true)
            )
}
