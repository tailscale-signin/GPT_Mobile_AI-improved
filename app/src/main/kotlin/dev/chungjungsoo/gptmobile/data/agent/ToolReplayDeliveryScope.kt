package dev.chungjungsoo.gptmobile.data.agent

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A completed outer response cannot attest bodies replaced by an inner continuation. */
internal class ToolReplayDeliveryScope : AbstractCoroutineContextElement(Key) {
    @Volatile private var substituted = false

    val acknowledgesCallerBodies: Boolean get() = !substituted

    /** Reopening may send bounded prose or a different exact protocol projection. */
    fun replaySubstituted() {
        substituted = true
    }

    companion object Key : CoroutineContext.Key<ToolReplayDeliveryScope>
}
