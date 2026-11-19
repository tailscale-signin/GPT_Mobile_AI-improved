package dev.chungjungsoo.gptmobile.presentation.ui.chat

internal enum class GitHubScrollMode { PIN_PROMPT, FOLLOW_BOTTOM, USER_HISTORY }

/** The sole GitHub scroll intent owner. User input is never consumed to defend an anchor. */
internal class GitHubPromptAnchor(
    private val releaseDistance: Float,
    initialMode: GitHubScrollMode = GitHubScrollMode.PIN_PROMPT
) {
    var mode: GitHubScrollMode = initialMode
        private set
    var dragging: Boolean = false
        private set
    var returningToBottom: Boolean = false
        private set
    private var historyDistance = 0f

    fun followBottom() {
        mode = GitHubScrollMode.FOLLOW_BOTTOM
        finishScroll()
    }

    fun detach() {
        mode = GitHubScrollMode.USER_HISTORY
        finishScroll()
    }

    /** A positive drag reports detachment once when it crosses touch slop, even after an external jump to the latest message. */
    fun move(delta: Float): Boolean {
        if (!delta.isFinite() || delta == 0f) return false
        dragging = true
        if (delta < 0f) {
            historyDistance = 0f
            returningToBottom = true
            return false
        }
        returningToBottom = false
        val firstCrossing = historyDistance == 0f || historyDistance < releaseDistance
        historyDistance += delta
        if (historyDistance < releaseDistance) return false
        mode = GitHubScrollMode.USER_HISTORY
        return firstCrossing
    }

    fun endGesture(cancelled: Boolean = false) {
        dragging = false
        historyDistance = 0f
        if (cancelled) returningToBottom = false
    }

    fun finishScroll() {
        endGesture(cancelled = true)
    }

    fun shouldFollowAtBottom(atBottom: Boolean): Boolean = atBottom && returningToBottom

    companion object {
        const val PROMPT_ANIMATION_MILLIS = 1500
    }
}
