package dev.chungjungsoo.gptmobile.data.localruntime

/** Only increasing work counters extend an idle deadline; repeated heartbeats never do. */
internal class NativeProgressTracker {
    private data class Counts(val processed: Long, val sampled: Long)
    private val operations = linkedMapOf<Long, Counts>()
    fun advance(progress: GgufNativeProgress?): Boolean {
        if (progress == null || progress.operationId <= 0) return false
        val previous = operations[progress.operationId] ?: Counts(0, 0)
        val processed = progress.processedTokens.coerceAtLeast(0)
        val sampled = progress.sampledTokens.coerceAtLeast(0)
        val advanced = processed > previous.processed || sampled > previous.sampled
        operations[progress.operationId] = Counts(maxOf(previous.processed, processed), maxOf(previous.sampled, sampled))
        while (operations.size > 64) operations.remove(operations.keys.first())
        return advanced
    }
}
