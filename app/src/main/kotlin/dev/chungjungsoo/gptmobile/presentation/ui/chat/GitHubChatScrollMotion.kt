package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyLayoutScrollScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

internal fun LazyListState.gitHubDistanceToBottom(): Float {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return 0f
    val footer = info.visibleItemsInfo.lastOrNull { it.index == info.totalItemsCount - 1 } ?: return Float.POSITIVE_INFINITY
    return (footer.offset + footer.size + info.afterContentPadding - info.viewportEndOffset).coerceAtLeast(0).toFloat()
}

internal fun LazyListState.isAtGitHubBottom(): Boolean =
    layoutInfo.totalItemsCount > 0 && gitHubDistanceToBottom() <= 1f

/** One coroutine owns prompt anchoring and bottom following; raw deltas never launch scroll jobs. */
@Composable
internal fun GitHubScrollMotion(
    listState: LazyListState,
    enabled: Boolean,
    mode: GitHubScrollMode,
    dragging: Boolean,
    promptKey: String?,
    promptItem: Int,
    revision: Int,
    fontScale: Float,
    animate: Boolean
) {
    ChatBottomAutoScroller(
        listState,
        isEnabled = enabled && !dragging && mode == GitHubScrollMode.FOLLOW_BOTTOM,
        animate = animate,
        githubMotion = true
    )
    val currentItem by rememberUpdatedState(promptItem)
    val currentMode by rememberUpdatedState(mode)
    val currentDragging by rememberUpdatedState(dragging)
    LaunchedEffect(listState, enabled, mode, dragging, promptKey, revision, fontScale, animate) {
        if (enabled && !dragging && mode == GitHubScrollMode.PIN_PROMPT) {
            if (promptKey == null || currentItem < 0) return@LaunchedEffect
            snapshotFlow {
                val layout = listState.layoutInfo
                listOf(currentItem, layout.viewportSize.height, layout.beforeContentPadding, layout.afterContentPadding)
            }.distinctUntilChanged().conflate().collect {
                listState.animateToGitHubPrompt(
                    currentItem,
                    isUserDragging = { currentDragging },
                    shouldAnchor = { currentMode == GitHubScrollMode.PIN_PROMPT && !currentDragging },
                    currentTargetItem = { currentItem },
                    animate = animate
                )
            }
        }
    }
}

/** A single cancellable animation, with the distance corrected as lazy rows are measured. */
internal suspend fun LazyListState.animateToGitHubPrompt(
    targetItem: Int,
    isUserDragging: () -> Boolean = { false },
    shouldAnchor: () -> Boolean = { true },
    currentTargetItem: () -> Int = { targetItem },
    animate: Boolean = true
) {
    snapshotFlow { !shouldAnchor() || layoutInfo.totalItemsCount > currentTargetItem() && layoutInfo.viewportSize.height > 0 }.first { it }
    if (!shouldAnchor()) return
    // Reattachment is requested from nested scroll while the user's scroll mutation
    // still owns the list. Wait for it instead of losing the animation to its priority.
    snapshotFlow { !shouldAnchor() || !isScrollInProgress && !isUserDragging() }.first { it }
    if (!shouldAnchor()) return
    if (!animate || currentCoroutineContext()[MotionDurationScale]?.scaleFactor == 0f) {
        scrollToItem(currentTargetItem())
        return
    }
    scroll {
        val layout = LazyLayoutScrollScope(this@animateToGitHubPrompt, this)
        var previous = 0f
        androidx.compose.animation.core.animate(0f, 1f, animationSpec = tween(GitHubPromptAnchor.PROMPT_ANIMATION_MILLIS, easing = FastOutSlowInEasing)) { fraction, _ ->
            if (!shouldAnchor() || isUserDragging()) throw CancellationException("GitHub prompt positioning superseded by user intent")
            val remainingFraction = (1f - previous).coerceAtLeast(0.000001f)
            val share = ((fraction - previous) / remainingFraction).coerceIn(0f, 1f)
            layout.scrollBy(layout.calculateDistanceTo(currentTargetItem()).toFloat() * share)
            previous = fraction
        }
    }
}

/** The bottom follower owns this motion; buttons only change its intent. */
internal suspend fun LazyListState.animateToGitHubBottom(animate: Boolean = true, durationMillis: Int = GITHUB_BOTTOM_FOLLOW_MILLIS) {
    // A user fling can outlive the drag interaction. Keep the follow request
    // pending instead of losing this coroutine to the higher-priority mutation.
    snapshotFlow { isScrollInProgress }.first { !it }
    val info = layoutInfo
    val lastIndex = info.totalItemsCount - 1
    if (lastIndex < 0 || !canScrollForward) return
    if (!animate || currentCoroutineContext()[MotionDurationScale]?.scaleFactor == 0f) {
        scrollToItem(lastIndex)
        return
    }
    scroll {
        val layout = LazyLayoutScrollScope(this@animateToGitHubBottom, this)
        var previous = 0f
        androidx.compose.animation.core.animate(0f, 1f, animationSpec = tween(durationMillis, easing = FastOutSlowInEasing)) { fraction, _ ->
            val current = layoutInfo
            val target = current.totalItemsCount - 1
            if (target >= 0) {
                val footer = current.visibleItemsInfo.lastOrNull { it.index == target }
                val remaining = if (footer != null) {
                    (footer.offset + footer.size + current.afterContentPadding - current.viewportEndOffset).toFloat()
                } else {
                    layout.calculateDistanceTo(target).toFloat()
                }
                scrollBy(gitHubBottomFollowStep(remaining, previous, fraction))
            }
            previous = fraction
        }
    }
}
