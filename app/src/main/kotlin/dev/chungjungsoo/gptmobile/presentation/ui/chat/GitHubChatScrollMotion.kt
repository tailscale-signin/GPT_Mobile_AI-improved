package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyLayoutScrollScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/** A single cancellable animation, with the distance corrected as lazy rows are measured. */
internal suspend fun LazyListState.animateToGitHubPrompt(
    targetItem: Int,
    isUserDragging: () -> Boolean = { false },
    shouldAnchor: () -> Boolean = { true }
) {
    snapshotFlow { layoutInfo }.first { it.totalItemsCount > targetItem && it.viewportSize.height > 0 }
    // Reattachment is requested from nested scroll while the user's scroll mutation
    // still owns the list. Wait for it instead of losing the animation to its priority.
    snapshotFlow { isScrollInProgress || isUserDragging() }.first { !it }
    if (!shouldAnchor()) return
    scroll {
        val layout = LazyLayoutScrollScope(this@animateToGitHubPrompt, this)
        var previous = 0f
        animate(0f, 1f, animationSpec = tween(GitHubPromptAnchor.PROMPT_ANIMATION_MILLIS, easing = FastOutSlowInEasing)) { fraction, _ ->
            val remainingFraction = (1f - previous).coerceAtLeast(0.000001f)
            val share = ((fraction - previous) / remainingFraction).coerceIn(0f, 1f)
            layout.scrollBy(layout.calculateDistanceTo(targetItem).toFloat() * share)
            previous = fraction
        }
    }
}
