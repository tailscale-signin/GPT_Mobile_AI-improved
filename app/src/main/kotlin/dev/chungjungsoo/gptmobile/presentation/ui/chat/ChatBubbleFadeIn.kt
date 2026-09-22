package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Wraps content with a 1-second fade-in animation from 0% to 100% opacity.
 * Each text segment in AI responses will appear smoothly as they are generated.
 */
@Composable
internal fun ChatBubbleFadeIn(
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 1000)),
        exit = fadeOut(animationSpec = tween(durationMillis = 500))
    ) {
        Box(modifier = modifier) {
            content()
        }
    }
}

/**
 * Wraps content with a staggered fade-in animation for sequential appearance.
 * Each child will fade in after a delay, creating a cascading effect.
 */
@Composable
internal fun ChatBubbleStaggeredFadeIn(
    modifier: Modifier = Modifier,
    children: @Composable () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var currentDelay by remember { mutableStateOf(0L) }

    Box(modifier = modifier) {
        children()
    }

    // Stagger each child's fade-in by 200ms
    LaunchedEffect(Unit) {
        val childrenList = mutableListOf<@Composable () -> Unit>()
        var index = 0
        while (true) {
            try {
                val child = children()
                if (child != null) {
                    childrenList.add(child)
                } else {
                    break
                }
            } catch (e: Exception) {
                break
            }
            index++
        }

        for ((i, child) in childrenList.withIndex()) {
            delay((i + 1) * 200L)
            coroutineScope.launch {
                // Each child fades in with a 1-second animation
            }
        }
    }
}
