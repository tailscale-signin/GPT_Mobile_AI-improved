package dev.chungjungsoo.gptmobile.data.readiness

import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Per-preparation counters, safe across concurrently acquired catalogues. No keys or source text. */
internal class ReadinessCacheObservation : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ReadinessCacheObservation>
    val hits = AtomicLong()
    val misses = AtomicLong()
    val stale = AtomicLong()
    fun read(found: Boolean, staleUsable: Boolean = false) {
        if (found) hits.incrementAndGet() else misses.incrementAndGet()
        if (found && staleUsable) stale.incrementAndGet()
    }
}
