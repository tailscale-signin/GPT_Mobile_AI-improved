package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Process heap samples and window frames overlap run activity; neither establishes causality. */
internal class FrameWindowEvidence(private val maximumRuns: Int = 16, private val heapSampleIntervalNanos: Long = 250_000_000L) {
    init {
        require(maximumRuns in 1..16 && heapSampleIntervalNanos > 0)
    }

    private class Samples {
        var first: Long? = null
        var last: Long? = null
        var frames = 0L
        var totalDurationNanos = 0L
        var maximumDurationNanos = 0L
        var heapSamples = 0L
        var heapPeak: Long? = null

        fun observe(at: Long, duration: Long, heap: Long?) {
            if (first == null) first = at
            last = at
            frames++
            totalDurationNanos = if (duration > Long.MAX_VALUE - totalDurationNanos) Long.MAX_VALUE else totalDurationNanos + duration
            maximumDurationNanos = maxOf(maximumDurationNanos, duration)
            if (heap != null && heap >= 0) {
                heapSamples++
                heapPeak = maxOf(heapPeak ?: 0, heap)
            }
        }

        fun json(): JsonObject = buildJsonObject {
            put("firstCallbackMonoNs", first?.let(::JsonPrimitive) ?: JsonNull)
            put("lastCallbackMonoNs", last?.let(::JsonPrimitive) ?: JsonNull)
            put("frameSamples", frames)
            put("frameDurationTotalNs", totalDurationNanos)
            put("frameDurationMaximumNs", maximumDurationNanos)
            put("heapSamples", heapSamples)
            put("sampledProcessHeapPeakBytes", heapPeak?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    private var epoch: Long? = null
    private var window = Samples()
    private val runs = linkedMapOf<String, Samples>()
    private var lastHeapSampleNanos: Long? = null
    private var truncated = false

    fun observe(atNanos: Long, frameDurationNanos: Long, activeRuns: Set<String>, captureEpoch: Long?, heapUsedBytes: () -> Long) {
        align(captureEpoch)
        if (captureEpoch == null || frameDurationNanos < 0 || window.last?.let { atNanos < it } == true) return
        val previous = lastHeapSampleNanos
        val heap = if (previous == null || atNanos - previous >= heapSampleIntervalNanos) {
            lastHeapSampleNanos = atNanos
            heapUsedBytes().takeIf { it >= 0 }
        } else {
            null
        }
        window.observe(atNanos, frameDurationNanos, heap)
        activeRuns.forEach { run ->
            if (run.length !in 1..128) {
                truncated = true
                return@forEach
            }
            if (run in runs || runs.size < maximumRuns) {
                runs.getOrPut(run) { Samples() }.observe(atNanos, frameDurationNanos, heap)
            } else {
                truncated = true
            }
        }
    }

    fun snapshot(captureEpoch: Long?): JsonObject {
        align(captureEpoch)
        return buildJsonObject {
            put("scope", "OBSERVED_FRAME_CALLBACK_WINDOW")
            put("clockOrigin", "processSessionId_System.nanoTime")
            put("runAssociation", "ACTIVE_RUN_PHASE_AT_CALLBACK")
            put("causalAttribution", false)
            put("heapScope", "SAMPLED_PROCESS_JAVA_HEAP_WHILE_OBSERVED; shared with other work; not a lifetime or exact run peak")
            put("heapSampleIntervalNanos", heapSampleIntervalNanos)
            put("window", window.json())
            put("runs", JsonArray(runs.map { (run, samples) -> JsonObject(samples.json() + ("runId" to JsonPrimitive(run))) }))
            put("runsTruncated", truncated)
        }
    }

    private fun align(captureEpoch: Long?) {
        if (epoch != captureEpoch) {
            clear()
            epoch = captureEpoch
        }
    }

    fun clear() {
        window = Samples()
        runs.clear()
        lastHeapSampleNanos = null
        truncated = false
    }
}
