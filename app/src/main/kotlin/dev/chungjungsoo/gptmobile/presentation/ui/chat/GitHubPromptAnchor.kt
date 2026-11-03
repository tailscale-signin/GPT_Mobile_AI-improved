package dev.chungjungsoo.gptmobile.presentation.ui.chat

/** Gesture distances are pixels supplied by the UI's current density. Streaming never moves the anchor. */
internal class GitHubPromptAnchor(private val releaseDistance: Float, private val resumeDistance: Float) {
    var attached = true
        private set
    private var towardHistory = 0f
    private var towardCurrent = 0f

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
    }

    /** onPreFling is a user gesture; a fast flick can express intent without a long drag. */
    fun fling(velocity: Float, threshold: Float): Movement {
        if (attached && velocity > threshold) {
            detach()
        } else if (!attached && velocity < -threshold) {
            attach()
            return Movement(velocity, reattach = true)
        }
        return Movement()
    }

    /** Positive motion reveals older messages. Inertial deltas never change intent; user flings are handled separately. */
    fun move(delta: Float, distanceToPrompt: Float, userInput: Boolean): Movement {
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
            towardHistory = 0f
            return Movement()
        }
        val excess = (delta - distanceToPrompt.coerceAtLeast(0f)).coerceAtLeast(0f)
        towardHistory += excess
        if (towardHistory >= releaseDistance) {
            detach()
            return Movement()
        }
        return Movement(excess)
    }
}
