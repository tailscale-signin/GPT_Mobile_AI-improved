package dev.chungjungsoo.gptmobile.presentation.ui.chat

internal enum class GitHubScrollMode { PIN_PROMPT, FOLLOW_BOTTOM, USER_HISTORY }

/** The sole GitHub scroll intent owner. User input is never consumed to defend an anchor. */
internal class GitHubPromptAnchor(
    private val releaseDistance: Float,
    private val resumeDistance: Float,
    initialMode: GitHubScrollMode = GitHubScrollMode.PIN_PROMPT
) {
    var mode: GitHubScrollMode = initialMode
        private set
    val attached: Boolean get() = mode != GitHubScrollMode.USER_HISTORY
    var dragging: Boolean = false
        private set
    private var modeBeforeGesture = initialMode
    private var towardHistory = 0f
    private var towardCurrent = 0f
    private var historyStartedAt: Long? = null
    private var lastHistoryAt: Long? = null
    private var nearBottom = false

    data class Movement(val consumed: Float = 0f, val reattach: Boolean = false)

    fun attach() {
        mode = GitHubScrollMode.PIN_PROMPT
        resetGesture()
    }

    fun followBottom() {
        mode = GitHubScrollMode.FOLLOW_BOTTOM
        resetGesture()
    }

    fun detach() {
        mode = GitHubScrollMode.USER_HISTORY
        resetGesture()
    }

    fun beginGesture() {
        modeBeforeGesture = mode
        dragging = true
        clearDirection()
    }

    /** Hysteresis is based on the measured footer, never a guessed token/line count. */
    fun observeBottom(distance: Float, enterDistance: Float, leaveDistance: Float) {
        require(leaveDistance >= enterDistance)
        nearBottom = distance <= if (nearBottom) leaveDistance else enterDistance
    }

    /** Returning downward selects its destination only after the finger has lifted. */
    fun endGesture(distanceToBottom: Float, enterDistance: Float, leaveDistance: Float, cancelled: Boolean = false) {
        if (!dragging) return
        observeBottom(distanceToBottom, enterDistance, leaveDistance)
        if (!cancelled && towardCurrent >= resumeDistance && nearBottom) {
            mode = GitHubScrollMode.FOLLOW_BOTTOM
        } else if (!cancelled && towardHistory >= releaseDistance) {
            // A short decisive swipe should stay where the finger left it.
            mode = GitHubScrollMode.USER_HISTORY
        } else if (mode != GitHubScrollMode.USER_HISTORY) {
            mode = modeBeforeGesture
        }
        resetGesture()
    }

    /** Inertia and layout changes never manufacture user intent. */
    @Suppress("UNUSED_PARAMETER")
    fun fling(velocity: Float, threshold: Float): Movement = Movement()

    /** Positive motion reveals history; negative motion returns toward the active turn. */
    @Suppress("UNUSED_PARAMETER")
    fun move(delta: Float, distanceToPrompt: Float, userInput: Boolean, nowMillis: Long = System.nanoTime() / 1_000_000): Movement {
        if (!userInput || delta == 0f) return Movement()
        if (!dragging) beginGesture()
        if (delta < 0f) {
            towardHistory = 0f
            historyStartedAt = null
            lastHistoryAt = null
            towardCurrent -= delta
            return Movement()
        }
        towardCurrent = 0f
        if (lastHistoryAt?.let { nowMillis - it !in 0..MAX_DRAG_SAMPLE_GAP_MILLIS } == true) {
            towardHistory = 0f
            historyStartedAt = null
        }
        if (historyStartedAt == null) historyStartedAt = nowMillis
        lastHistoryAt = nowMillis
        towardHistory += delta
        if (towardHistory >= releaseDistance && nowMillis - requireNotNull(historyStartedAt) >= HISTORY_RELEASE_MILLIS) {
            mode = GitHubScrollMode.USER_HISTORY
        }
        return Movement()
    }

    private fun resetGesture() {
        dragging = false
        clearDirection()
    }

    private fun clearDirection() {
        towardHistory = 0f
        towardCurrent = 0f
        historyStartedAt = null
        lastHistoryAt = null
    }

    companion object {
        const val PROMPT_ANIMATION_MILLIS = 1500
        const val HISTORY_RELEASE_MILLIS = 250L
        private const val MAX_DRAG_SAMPLE_GAP_MILLIS = 125L
    }
}
