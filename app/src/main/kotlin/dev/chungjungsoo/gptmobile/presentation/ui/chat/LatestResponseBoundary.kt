package dev.chungjungsoo.gptmobile.presentation.ui.chat

/** A fling or held gesture cannot release the boundary it just reached. */
internal class LatestResponseBoundary(private val pauseMillis: Long = 1_000L) {
    private var armed = false
    private var hitAt: Long? = null
    private var releaseOnUpwardGesture = false

    fun rearm() {
        armed = true
        hitAt = null
        releaseOnUpwardGesture = false
    }

    fun beginGesture(now: Long) {
        releaseOnUpwardGesture = hitAt?.let { now - it >= pauseMillis } == true
    }

    fun consume(upwardPixels: Float, remainingPixels: Float, now: Long, isGesture: Boolean): Float {
        if (upwardPixels <= 0f || !armed) return 0f
        if (isGesture && releaseOnUpwardGesture) {
            armed = false
            hitAt = null
            releaseOnUpwardGesture = false
            return 0f
        }
        val allowed = remainingPixels.coerceAtLeast(0f)
        if (upwardPixels >= allowed) {
            if (hitAt == null) hitAt = now
            return (upwardPixels - allowed).coerceAtLeast(0f)
        }
        return 0f
    }

    fun returningToResponse() {
        if (!armed) rearm()
    }
}
