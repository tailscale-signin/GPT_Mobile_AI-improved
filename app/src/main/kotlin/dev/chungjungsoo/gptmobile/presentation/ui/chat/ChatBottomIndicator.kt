package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Follow intent has the same presentation after a button press or a deliberate bottom arrival. */
@Composable
internal fun ChatBottomIndicator(
    listState: LazyListState,
    following: Boolean,
    generating: Boolean,
    animate: Boolean,
    scrollInput: ChatScrollInput? = null,
    onFollow: () -> Unit
) {
    if (!following) {
        // Do not infer reading speed from programmatic list movement. The shared
        // input only reports real user gestures and already applies a speed gate.
        // Once a deliberate scroll reveals the arrow, keep it available for five
        // seconds after scrolling settles instead of blinking away on release.
        var heldVisible by remember(scrollInput, listState) { mutableStateOf(false) }
        val fastUserScroll = scrollInput?.scrollingFasterThanReading == true
        val scrolling = listState.isScrollInProgress
        val hasOlderContent = listState.canScrollForward
        LaunchedEffect(scrollInput, fastUserScroll, scrolling, hasOlderContent) {
            when {
                !hasOlderContent -> heldVisible = false
                fastUserScroll -> heldVisible = true
                !scrolling -> {
                    delay(5_000L)
                    heldVisible = false
                }
            }
        }
        val reveal = if (scrollInput == null) true else heldVisible && hasOlderContent
        val fadeMillis = chatScrollArrowFadeMillis(animate)
        AnimatedVisibility(visible = reveal, enter = fadeIn(tween(fadeMillis)), exit = fadeOut(tween(fadeMillis))) {
            ScrollToBottomButton(isGenerating = generating, onClick = onFollow)
        }
        return
    }
    // Dots latch only for the current follow session once the actual bottom
    // has been reached. Layout growth must not flicker them off while the
    // measured-line follower catches up. Detaching removes this composition
    // branch and resets the latch for the next explicit return.
    var reachedBottom by remember(listState) { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isAtGitHubBottom() }.first { it }
        reachedBottom = true
    }
    // Explicit arrow presses and manual arrivals share this visible entrance.
    // Keep idle/detached indicators unmounted as before.
    val dotsAlpha by animateFloatAsState(
        targetValue = if (reachedBottom && generating) 1f else 0f,
        animationSpec = tween(if (animate) 650 else 0),
        label = "Bottom follow generation dots"
    )
    if (!reachedBottom || !generating) return
    // The shared phase respects app/system motion preferences, lifecycle and frame pressure.
    val phase = rememberGitHubProgressMotion(animate)
    val color = MaterialTheme.colorScheme.primary
    Row(
        Modifier.size(width = 48.dp, height = 48.dp)
            .graphicsLayer { alpha = dotsAlpha }
            .semantics { contentDescription = "Generating response" }
            .testTag("chat_bottom_generation"),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            Spacer(
                Modifier.size(5.dp)
                    .graphicsLayer {
                        val staggered = (phase.value + index / 3f) % 1f
                        alpha = if (animate) 0.35f + 0.65f * (1f - abs(staggered * 2f - 1f)) else 1f
                    }
                    .background(color, CircleShape)
            )
        }
    }
}

@Composable
fun ScrollToBottomButton(isGenerating: Boolean = false, onClick: () -> Unit) {
    // Preserve a generous touch target, but draw *only* the arrow glyph.
    Box(Modifier.size(48.dp).testTag("chat_jump_to_bottom").clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Rounded.KeyboardArrowDown,
            stringResource(R.string.scroll_to_bottom_icon),
            modifier = Modifier.size(26.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

/** Motion-off settings skip the jump arrow's entrance/exit tween. */
internal fun chatScrollArrowFadeMillis(animate: Boolean): Int = if (animate) 1000 else 0
