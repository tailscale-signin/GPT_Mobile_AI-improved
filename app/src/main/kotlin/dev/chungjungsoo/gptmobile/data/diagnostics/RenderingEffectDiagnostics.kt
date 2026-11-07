package dev.chungjungsoo.gptmobile.data.diagnostics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Correlate frame stalls/driver logs with active UI effects without inferring a cause. */
internal object RenderingEffectDiagnostics {
    enum class Effect { GLASS_BLUR, STREAMING_FADE }
    private val active = ConcurrentHashMap<Effect, AtomicInteger>()
    private val lastReportedNanos = AtomicLong()
    private val lastReportedState = AtomicReference<String?>()

    fun enter(effect: Effect): () -> Unit {
        val count = active.getOrPut(effect) { AtomicInteger() }
        count.incrementAndGet()
        report()
        val disposed = AtomicBoolean()
        return {
            if (disposed.compareAndSet(false, true)) {
                count.decrementAndGet()
                report()
            }
        }
    }

    fun snapshot(): String = Effect.entries.mapNotNull { effect ->
        active[effect]?.get()?.takeIf { it > 0 }?.let { "${effect.name}:$it" }
    }.joinToString(",").ifEmpty { "NONE" }

    private fun report() {
        if (!AppLogRecorder.enabled.value) {
            lastReportedState.set(null)
            return
        }
        val state = snapshot()
        if (lastReportedState.get() == state) return
        val now = System.nanoTime()
        val previous = lastReportedNanos.get()
        if (previous != 0L && now - previous < 1_000_000_000L || !lastReportedNanos.compareAndSet(previous, now)) return
        if (lastReportedState.getAndSet(state) == state) return
        AppLogRecorder.record("Rendering", "GRAPHICS_EFFECT_STATE · activeEffects=$state · logSamplingMs=1000 · changedSinceLastReport=true · driverCauseEstablished=false")
    }
}
