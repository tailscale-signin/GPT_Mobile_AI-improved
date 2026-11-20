package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import kotlinx.coroutines.delay

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
    if (following) return
    // Do not infer reading speed from programmatic list movement. The shared
    // input only reports real user gestures and already applies a speed gate.
    // Keep the target available after release. Only another user gesture
    // can extend the hold, so streaming layout changes do not reset it.
    var heldVisible by remember(scrollInput, listState) { mutableStateOf(false) }
    val fastUserScroll = scrollInput?.scrollingFasterThanReading == true
    val userScrolling = listState.isScrollInProgress && scrollInput?.dragging == true
    val hasOlderContent = listState.canScrollForward
    LaunchedEffect(scrollInput, fastUserScroll, userScrolling, hasOlderContent) {
        when {
            !hasOlderContent -> heldVisible = false
            fastUserScroll -> heldVisible = true
            !userScrolling && heldVisible -> {
                delay(5_000L)
                heldVisible = false
            }
        }
    }
    val reveal = if (scrollInput == null) true else (fastUserScroll || heldVisible) && hasOlderContent
    val fadeMillis = chatScrollArrowFadeMillis(animate)
    AnimatedVisibility(visible = reveal, enter = fadeIn(tween(fadeMillis)), exit = fadeOut(tween(fadeMillis))) {
        ScrollToBottomButton(isGenerating = generating, onClick = onFollow)
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
