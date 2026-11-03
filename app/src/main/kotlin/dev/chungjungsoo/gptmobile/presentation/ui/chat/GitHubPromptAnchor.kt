package dev.chungjungsoo.gptmobile.presentation.ui.chat

/** Intent comes only from a sustained user drag, never streaming layout changes or inertia. */
internal class GitHubPromptAnchor(private val releaseDistance: Float, private val resumeDistance: Float) {
    var attached = true
        private set
    private var towardHistory = 0f
    private var towardCurrent = 0f
    private var historyStartedAt: Long? = null
    private var lastHistoryAt: Long? = null

    data class Movement(val consumed: Float = 0f, val reattach: Boolean = false)

    fun attach() {
        attached = true
        beginGesture()
    }

    fun detach() {
        attached = false
        beginGesture()
    }

    fun beginGesture() {
        towardHistory = 0f
        towardCurrent = 0f
        historyStartedAt = null
        lastHistoryAt = null
    }

    /** A quick upward flick must not bypass the one-second history-release gesture. */
    fun fling(velocity: Float, threshold: Float): Movement {
        if (!attached && velocity < -threshold) {
            attach()
            return Movement(velocity, reattach = true)
        }
        return Movement()
    }

    /** Positive motion reveals history; negative motion returns to the current prompt. */
    fun move(delta: Float, distanceToPrompt: Float, userInput: Boolean, nowMillis: Long = System.nanoTime() / 1_000_000): Movement {
        if (delta == 0f) return Movement()
        if (!userInput) return Movement(if (attached && delta > 0) (delta - distanceToPrompt.coerceAtLeast(0f)).coerceAtLeast(0f) else 0f)
        if (!attached) {
            towardCurrent = if (delta < 0) towardCurrent - delta else 0f
            if (towardCurrent >= resumeDistance) {
                attach()
                return Movement(delta, reattach = true)
            }
            return Movement()
        }
        if (delta <= 0) {
            beginGesture()
            return Movement()
        }
        val excess = (delta - distanceToPrompt.coerceAtLeast(0f)).coerceAtLeast(0f)
        if (excess == 0f) {
            beginGesture()
            return Movement()
        }
        // Holding a finger still, reversing direction or separate swipes cannot accumulate time.
        if (lastHistoryAt?.let { nowMillis - it !in 0..250 } == true) beginGesture()
        if (historyStartedAt == null) historyStartedAt = nowMillis
        lastHistoryAt = nowMillis
        towardHistory += excess
        if (towardHistory >= releaseDistance && nowMillis - requireNotNull(historyStartedAt) >= HISTORY_RELEASE_MILLIS) {
            detach()
            return Movement()
        }
        return Movement(excess)
    }

    companion object {
        const val PROMPT_ANIMATION_MILLIS = 1500
        const val HISTORY_RELEASE_MILLIS = 1000L
    }
}
