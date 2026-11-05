package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyLayoutScrollScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** A single cancellable animation, with the distance corrected as lazy rows are measured. */
internal suspend fun LazyListState.animateToGitHubPrompt(
    targetItem: Int,
    isUserDragging: () -> Boolean = { false },
    shouldAnchor: () -> Boolean = { true }
) {
    snapshotFlow { !shouldAnchor() || layoutInfo.totalItemsCount > targetItem && layoutInfo.viewportSize.height > 0 }.first { it }
    if (!shouldAnchor()) return
    // Reattachment is requested from nested scroll while the user's scroll mutation
    // still owns the list. Wait for it instead of losing the animation to its priority.
    snapshotFlow { !shouldAnchor() || !isScrollInProgress && !isUserDragging() }.first { it }
    if (!shouldAnchor()) return
    scroll {
        val layout = LazyLayoutScrollScope(this@animateToGitHubPrompt, this)
        var previous = 0f
        animate(0f, 1f, animationSpec = tween(GitHubPromptAnchor.PROMPT_ANIMATION_MILLIS, easing = FastOutSlowInEasing)) { fraction, _ ->
            if (!shouldAnchor() || isUserDragging()) throw CancellationException("GitHub prompt positioning superseded by user intent")
            val remainingFraction = (1f - previous).coerceAtLeast(0.000001f)
            val share = ((fraction - previous) / remainingFraction).coerceIn(0f, 1f)
            layout.scrollBy(layout.calculateDistanceTo(targetItem).toFloat() * share)
            previous = fraction
        }
    }
}

/** The bottom follower owns this motion; buttons only change its intent. */
internal suspend fun LazyListState.animateToGitHubBottom() {
    // A user fling can outlive the drag interaction. Keep the follow request
    // pending instead of losing this coroutine to the higher-priority mutation.
    snapshotFlow { isScrollInProgress }.first { !it }
    val info = layoutInfo
    val lastIndex = info.totalItemsCount - 1
    if (lastIndex < 0 || !canScrollForward) return
    if (info.visibleItemsInfo.none { it.index == lastIndex }) {
        // Preserve lazy-list seeking for a distant destination. Near the bottom,
        // streaming growth is followed in one scroll mutation with a moving target.
        animateScrollToItem(lastIndex)
        return
    }
    scroll {
        var previousFrame = withFrameNanos { it }
        while (canScrollForward) {
            val frame = withFrameNanos { it }
            val current = layoutInfo
            val footer = current.visibleItemsInfo.lastOrNull { it.index == current.totalItemsCount - 1 } ?: break
            val remaining = (footer.offset + footer.size + current.afterContentPadding - current.viewportEndOffset).toFloat()
            val step = gitHubBottomFollowStep(remaining, frame - previousFrame)
            previousFrame = frame
            if (step <= 0f || scrollBy(step) <= 0f) break
        }
    }
}
