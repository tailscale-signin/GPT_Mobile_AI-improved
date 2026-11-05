package dev.chungjungsoo.gptmobile.data.diagnostics

/** Adaptive samples belong to one resumed window; startup draws remain diagnostic only. */
internal class FrameEffectsObservation(private val guard: FrameEffectsGuard, startedAtMs: Long) {
    private var stopped = false
    private var frames = 0
    private var slow = 0
    private var stalls = 0
    private var windowStartedMs = startedAtMs

    /** Returns false for callbacks queued before this window stopped observing. */
    @Synchronized
    fun observe(durationNs: Long, deadlineNs: Long, firstDraw: Boolean, nowMs: Long): Boolean {
        if (stopped) return false
        if (firstDraw) return true
        frames++
        if (durationNs > deadlineNs) slow++
        if (durationNs > 100_000_000L) stalls++
        guard.observeCriticalStall(durationNs, nowMs)
        if (frames >= 120 && nowMs - windowStartedMs >= 5_000L) {
            guard.observe(frames, slow, stalls, nowMs)
            frames = 0
            slow = 0
            stalls = 0
            windowStartedMs = nowMs
        }
        return true
    }

    @Synchronized
    fun stop() {
        stopped = true
    }
}
