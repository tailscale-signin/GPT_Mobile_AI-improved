package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A bounded timing window. Overlap is correlation, never proof that an operation caused jank. */
internal class FrameOperationCoverage(private val capacity: Int = 128) {
    data class Sample(val operation: String, val traceId: Int, val startedNanos: Long, val endedNanos: Long, val runId: String?, val owner: CombinedOperationOwner? = null)
    data class ContextReport(val contexts: JsonArray, val truncated: Boolean)
    data class Snapshot(val samples: List<Sample>, val truncated: Boolean) {
        /** Format at report time only. Keep a separate bounded record below the log line limit. */
        fun contextReport(): ContextReport {
            val contexts = mutableListOf<JsonObject>()
            var characters = 2
            var omitted = false
            for (sample in samples.asReversed()) {
                val owner = sample.owner ?: continue
                if (contexts.size == MAX_REPORTED_CONTEXTS) {
                    omitted = true
                    continue
                }
                val context = buildJsonObject {
                    put("traceId", sample.traceId)
                    put("operation", sample.operation.take(96))
                    put("durationNs", sample.endedNanos - sample.startedNanos)
                    owner.frameContext().forEach { (key, value) -> put(key, value) }
                }
                val size = context.toString().length + if (contexts.isEmpty()) 0 else 1
                if (characters + size > MAX_CONTEXT_CHARACTERS) {
                    omitted = true
                    continue
                }
                contexts += context
                characters += size
            }
            return ContextReport(JsonArray(contexts), truncated || omitted)
        }
    }

    companion object {
        const val MAX_REPORTED_CONTEXTS = 16
        const val MAX_CONTEXT_CHARACTERS = 4096
    }

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
