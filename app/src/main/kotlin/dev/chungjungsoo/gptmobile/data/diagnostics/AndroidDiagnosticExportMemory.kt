package dev.chungjungsoo.gptmobile.data.diagnostics

import android.os.Debug

/** Heap headroom is live; bounded ART/native observations are refreshed at most four times a second. */
object AndroidDiagnosticExportMemory {
    private data class Counters(val atNanos: Long, val atMillis: Long, val native: Long?, val gcCount: Long?, val gcTime: Long?, val allocated: Long?, val freed: Long?)

    @Volatile private var last: Counters? = null

    fun sample(): DiagnosticExportMemory {
        val heap = DiagnosticExportMemory.sample()
        val now = System.nanoTime()
        val retained = last
        val counters = if (retained != null && now - retained.atNanos in 0..250_000_000) {
            retained
        } else {
            fun stat(name: String): Long? = runCatching { Debug.getRuntimeStat(name)?.toLongOrNull()?.takeIf { it >= 0 } }.getOrNull()
            Counters(
                now,
                heap.capturedAtMillis,
                runCatching { Debug.getNativeHeapAllocatedSize().takeIf { it >= 0 } }.getOrNull(),
                stat("art.gc.gc-count"),
                stat("art.gc.gc-time"),
                stat("art.gc.bytes-allocated"),
                stat("art.gc.bytes-freed")
            ).also { last = it }
        }
        return heap.copy(
            nativeAllocatedBytes = counters.native,
            gcCount = counters.gcCount,
            gcTimeMillis = counters.gcTime,
            allocatedBytes = counters.allocated,
            freedBytes = counters.freed,
            nativeGcSampledAtMillis = counters.atMillis
        )
    }
}
