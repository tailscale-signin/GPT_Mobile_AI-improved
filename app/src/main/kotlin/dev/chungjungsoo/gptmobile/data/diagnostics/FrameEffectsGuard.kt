package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Two sustained bad windows reduce optional effects; clean windows restore them. */
internal class FrameEffectsGuard {
    private var badWindows = 0
    private var goodWindows = 0
    private var reducedUntil = 0L
    private var lastWindow: Long? = null
    private val mutableReduced = MutableStateFlow(false)
    val reduced = mutableReduced.asStateFlow()

    @Synchronized
    fun observe(frames: Int, slow: Int, stalls: Int, nowMs: Long) {
        if (frames < 120) return
        if (lastWindow?.let { nowMs - it !in 0..30_000L } == true) {
            badWindows = 0
            goodWindows = 0
        }
        lastWindow = nowMs
        val overloaded = slow.toLong() * 10 >= frames || stalls >= 2
        if (overloaded) {
            badWindows++
            goodWindows = 0
            if (badWindows >= 2) {
                reducedUntil = nowMs + 30_000L
                mutableReduced.value = true
            }
        } else {
            badWindows = 0
            goodWindows++
            if (goodWindows >= 2 && nowMs >= reducedUntil) mutableReduced.value = false
        }
    }
}

internal object FrameEffectPressure {
    val guard = FrameEffectsGuard()
}
