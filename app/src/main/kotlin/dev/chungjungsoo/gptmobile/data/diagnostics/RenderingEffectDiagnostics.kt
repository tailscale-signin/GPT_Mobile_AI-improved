package dev.chungjungsoo.gptmobile.data.diagnostics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Correlate frame stalls/driver logs with active UI effects without inferring a cause. */
internal object RenderingEffectDiagnostics {
    enum class Effect { GLASS_BLUR, STREAMING_FADE }
    private val active = ConcurrentHashMap<Effect, AtomicInteger>()
    private val lastReportedNanos = AtomicLong()

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
        if (!AppLogRecorder.enabled.value) return
        val now = System.nanoTime()
        val previous = lastReportedNanos.get()
        if (previous != 0L && now - previous < 1_000_000_000L || !lastReportedNanos.compareAndSet(previous, now)) return
        AppLogRecorder.record("Rendering", "GRAPHICS_EFFECT_STATE · activeEffects=${snapshot()} · logSamplingMs=1000 · driverCauseEstablished=false")
    }
}
