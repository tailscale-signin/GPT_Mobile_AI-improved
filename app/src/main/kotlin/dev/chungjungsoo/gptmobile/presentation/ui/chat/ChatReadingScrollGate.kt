package dev.chungjungsoo.gptmobile.presentation.ui.chat

import kotlin.math.abs

/** A windowed user-gesture speed gate; programmatic streaming scroll never drives it. */
internal class ChatReadingScrollGate(
    private val readingThresholdDpPerSecond: Float = 135f,
    private val windowMillis: Long = 90L
) {
    private var windowStart = -1L
    private var distanceDp = 0f
    var fasterThanReading: Boolean = false
        private set

    fun recordDelta(deltaDp: Float, nowMillis: Long): Boolean {
        if (!deltaDp.isFinite() || deltaDp == 0f) return fasterThanReading
        if (windowStart < 0L || nowMillis < windowStart || nowMillis - windowStart > 400L) {
            windowStart = nowMillis
            distanceDp = 0f
            fasterThanReading = false
        }
        distanceDp += abs(deltaDp)
        val elapsed = nowMillis - windowStart
        if (elapsed >= windowMillis) {
            fasterThanReading = distanceDp * 1000f / elapsed >= readingThresholdDpPerSecond
            windowStart = nowMillis
            distanceDp = 0f
        }
        return fasterThanReading
    }

    fun recordFling(velocityDpPerSecond: Float): Boolean {
        fasterThanReading = velocityDpPerSecond.isFinite() &&
            abs(velocityDpPerSecond) >= readingThresholdDpPerSecond
        return fasterThanReading
    }

    fun stop() {
        fasterThanReading = false
        windowStart = -1L
        distanceDp = 0f
    }
}
