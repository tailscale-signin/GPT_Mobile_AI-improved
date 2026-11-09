package dev.chungjungsoo.gptmobile.data.diagnostics

/** A bounded timing window. Overlap is correlation, never proof that an operation caused jank. */
internal class FrameOperationCoverage(private val capacity: Int = 128) {
    data class Sample(val operation: String, val traceId: Int, val startedNanos: Long, val endedNanos: Long, val runId: String?)
    data class Snapshot(val samples: List<Sample>, val truncated: Boolean)

    private val samples = ArrayDeque<Sample>()
    private var latestEvictedEndNanos: Long? = null
    private var generation = 0L

    init {
        require(capacity > 0)
    }

    @Synchronized
    fun generation(): Long = generation

    @Synchronized
    fun observe(sample: Sample): Boolean = observe(sample, generation)

    @Synchronized
    fun observe(sample: Sample, capturedGeneration: Long): Boolean {
        if (capturedGeneration != generation || sample.endedNanos < sample.startedNanos) return false
        samples.addLast(sample)
        while (samples.size > capacity) {
            val removed = samples.removeFirst()
            latestEvictedEndNanos = maxOf(latestEvictedEndNanos ?: Long.MIN_VALUE, removed.endedNanos)
        }
        return true
    }

    @Synchronized
    fun snapshot(startedNanos: Long, endedNanos: Long): Snapshot = Snapshot(
        samples.filter { it.startedNanos < endedNanos && it.endedNanos >= startedNanos },
        latestEvictedEndNanos?.let { it >= startedNanos } == true
    )

    @Synchronized
    fun clear() {
        generation++
        samples.clear()
        latestEvictedEndNanos = null
    }
}
