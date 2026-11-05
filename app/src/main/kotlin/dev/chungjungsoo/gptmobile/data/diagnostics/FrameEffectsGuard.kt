package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Critical stalls or sustained bad windows reduce optional effects for a bounded cooldown. */
internal class FrameEffectsGuard {
    private var badWindows = 0
    private var reducedUntil = 0L
    private var lastWindow: Long? = null
    private val mutableReduced = MutableStateFlow(false)
    val reduced = mutableReduced.asStateFlow()

    /** A quarter-second stall needs immediate relief even before 120 frames arrive. */
    @Synchronized
    fun observeCriticalStall(durationNs: Long, nowMs: Long) {
        if (durationNs < 250_000_000L) return
        reducedUntil = nowMs + 30_000L
        mutableReduced.value = true
    }

    @Synchronized
    fun observe(frames: Int, slow: Int, stalls: Int, nowMs: Long) {
        if (frames < 120) return
        if (lastWindow?.let { nowMs - it !in 0..30_000L } == true) {
            badWindows = 0
        }
        lastWindow = nowMs
        val overloaded = slow.toLong() * 10 >= frames || stalls >= 2
        if (overloaded) {
            badWindows++
            if (mutableReduced.value || badWindows >= 2) {
                reducedUntil = nowMs + 30_000L
                mutableReduced.value = true
            }
        } else {
            badWindows = 0
            recoverAfterCooldown(nowMs)
        }
    }

    /**
     * Retry effects after quiet time, including when an idle screen produces no frames.
     * Returns the remaining delay if newer pressure extended the deadline, so an old
     * scheduled check cannot clear a more recent incident.
     */
    @Synchronized
    fun recoverAfterCooldown(nowMs: Long): Long? {
        if (!mutableReduced.value) return null
        val remaining = reducedUntil - nowMs
        if (remaining > 0) return remaining
        badWindows = 0
        lastWindow = null
        mutableReduced.value = false
        return null
    }
}

internal object FrameEffectPressure {
    val guard = FrameEffectsGuard()
}
