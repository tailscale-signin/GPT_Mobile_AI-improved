package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** All aggregate evidence and the sole flush deadline belong to one opt-in capture epoch. */
internal class FrameTimingWindow(startedMs: Long, private val intervalMs: Long = 5000) {
    init {
        require(intervalMs > 0)
    }

    /** Monotonic within the owning lifecycle; pair with its coverageId to join one report only. */
    var sequence = 0L
        private set
    var epoch: Long? = null
        private set
    var startedMs = startedMs
        private set
    private var deadlineMs = startedMs + intervalMs
    var frames = 0
        private set
    var observedFrames = 0L
        private set
    var slow = 0
        private set
    var dropped = 0
        private set
    var totalNs = 0L
        private set
    var maximumNs = 0L
        private set
    var minimumDeadlineNs = Long.MAX_VALUE
        private set
    var maximumDeadlineNs = 0L
        private set
    var fallbackDeadlines = 0
        private set
    private var firstCallbackMs: Long? = null
    private var lastCallbackMs: Long? = null
    private var maximumCallbackGapMs = 0L
    val histogram = FrameDurationHistogram()
    val work = FrameWorkBreakdown()
    val runCoverage = FrameRunCoverage()
    val evidence = FrameWindowEvidence()

    /** True tells the Android owner to discard its CPU/GC and operation-clock baselines too. */
    fun align(captureEpoch: Long?, nowMs: Long): Boolean {
        if (epoch == captureEpoch) return false
        epoch = captureEpoch
        observedFrames = 0
        clear(nowMs)
        return true
    }

    fun observe(durationNs: Long, deadlineNs: Long, lost: Int, fallbackDeadline: Boolean, callbackAtMs: Long? = null): Boolean {
        if (epoch == null) return false
        callbackAtMs?.takeIf { it >= startedMs }?.let { now ->
            if (firstCallbackMs == null) firstCallbackMs = now
            lastCallbackMs?.let { previous -> maximumCallbackGapMs = maxOf(maximumCallbackGapMs, (now - previous).coerceAtLeast(0)) }
            lastCallbackMs = now
        }
        observedFrames++
        frames++
        if (durationNs > deadlineNs) slow++
        dropped += lost.coerceAtLeast(0)
        totalNs += durationNs
        maximumNs = maxOf(maximumNs, durationNs)
        minimumDeadlineNs = minOf(minimumDeadlineNs, deadlineNs)
        maximumDeadlineNs = maxOf(maximumDeadlineNs, deadlineNs)
        if (fallbackDeadline) fallbackDeadlines++
        histogram.add(durationNs)
        return true
    }

    fun due(nowMs: Long) = nowMs >= deadlineMs
    fun nextDelayMs(nowMs: Long) = (deadlineMs - nowMs).coerceAtLeast(1)

    /** Silence can mean an idle surface or missing callbacks; it cannot establish a jank rate. */
    fun callbackCoverage(nowMs: Long) = buildJsonObject {
        put("windowSequence", sequence)
        put("denominator", "DELIVERED_WINDOW_FRAME_METRICS")
        put(
            "coverage",
            if (frames == 0) {
                "NO_CALLBACKS"
            } else if (dropped > 0) {
                "CALLBACKS_DROPPED"
            } else {
                "OBSERVED_CALLBACKS_ONLY"
            }
        )
        put("windowMs", (nowMs - startedMs).coerceAtLeast(0))
        put("observedCallbacks", frames)
        put("droppedCallbacks", dropped)
        put("firstCallbackDelayMs", firstCallbackMs?.let { JsonPrimitive((it - startedMs).coerceAtLeast(0)) } ?: JsonNull)
        put("lastCallbackAgeMs", lastCallbackMs?.let { JsonPrimitive((nowMs - it).coerceAtLeast(0)) } ?: JsonNull)
        put("maximumCallbackGapMs", maximumCallbackGapMs)
        put("sessionJankRateAvailable", false)
        put("callbackGapsAreJankEvidence", false)
    }

    /** A threshold flush and a queued timer both advance this same deadline. */
    fun clear(nowMs: Long) {
        sequence++
        startedMs = nowMs
        deadlineMs = nowMs + intervalMs
        frames = 0
        slow = 0
        dropped = 0
        totalNs = 0
        maximumNs = 0
        minimumDeadlineNs = Long.MAX_VALUE
        maximumDeadlineNs = 0
        fallbackDeadlines = 0
        firstCallbackMs = null
        lastCallbackMs = null
        maximumCallbackGapMs = 0
        histogram.clear()
        work.clear()
        runCoverage.clear()
        evidence.clear()
    }
}
