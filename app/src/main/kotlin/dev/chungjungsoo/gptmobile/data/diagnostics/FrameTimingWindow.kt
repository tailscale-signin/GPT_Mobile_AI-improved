package dev.chungjungsoo.gptmobile.data.diagnostics

/** All aggregate evidence and the sole flush deadline belong to one opt-in capture epoch. */
internal class FrameTimingWindow(startedMs: Long, private val intervalMs: Long = 5000) {
    init {
        require(intervalMs > 0)
    }

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

    fun observe(durationNs: Long, deadlineNs: Long, lost: Int, fallbackDeadline: Boolean): Boolean {
        if (epoch == null) return false
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

    /** A threshold flush and a queued timer both advance this same deadline. */
    fun clear(nowMs: Long) {
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
        histogram.clear()
        work.clear()
        runCoverage.clear()
        evidence.clear()
    }
}
