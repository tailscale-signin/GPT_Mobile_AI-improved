package dev.chungjungsoo.gptmobile.presentation.ui.chat

/** Only released, fast momentum pauses at the newest response. Touch always takes control. */
internal class LatestResponseBoundary(private val pauseMillis: Long = 1_000L) {
    private var flinging = false
    private var hitAt: Long? = null

    fun beginGesture() {
        endFling()
    }

    fun beginFling(upwardVelocity: Float, minimumVelocity: Float) {
        flinging = upwardVelocity >= minimumVelocity
        hitAt = null
    }

    fun endFling() {
        flinging = false
        hitAt = null
    }

    fun consume(upwardPixels: Float, remainingPixels: Float, now: Long, isGesture: Boolean): Float {
        if (isGesture || !flinging || upwardPixels <= 0f || remainingPixels < 0f) return 0f
        if (hitAt?.let { now - it >= pauseMillis } == true) {
            endFling()
            return 0f
        }
        if (upwardPixels >= remainingPixels) {
            if (hitAt == null) hitAt = now
            return upwardPixels - remainingPixels
        }
        return 0f
    }
}
