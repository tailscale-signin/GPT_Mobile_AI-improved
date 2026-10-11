package dev.chungjungsoo.gptmobile.data.localruntime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Counts causes without retaining conversation text or claiming unavailable KV-token counts. */
object LocalReuseDiagnostics {
    private val counts = ConcurrentHashMap<String, AtomicLong>()
    fun record(reason: String) {
        counts.getOrPut(reason) { AtomicLong() }.incrementAndGet()
    }
    fun snapshot(): Map<String, Long> = counts.mapValues { it.value.get() }
}
