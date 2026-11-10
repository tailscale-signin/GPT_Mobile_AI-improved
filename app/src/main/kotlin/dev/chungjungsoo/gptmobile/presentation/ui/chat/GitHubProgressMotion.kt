package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.diagnostics.FrameEffectPressure

internal const val GITHUB_PROGRESS_PAN_MILLIS = 1800
internal const val GITHUB_ACTIVITY_FADE_MILLIS = 1500

/** Fade an arriving item once, without changing its measured position or replaying its text. */
@Composable
internal fun rememberGitHubActivityEntry(animate: Boolean = true): State<Float> {
    val animationsEnabled = rememberGitHubActivityAnimationsEnabled()
    val enabled = animate && animationsEnabled
    val opacity = remember { Animatable(if (enabled) 0f else 1f) }
    LaunchedEffect(enabled) {
        if (enabled) opacity.animateTo(1f, tween(GITHUB_ACTIVITY_FADE_MILLIS, easing = LinearEasing)) else opacity.snapTo(1f)
    }
    return opacity.asState()
}

/** A replacement fades in as the previous text fades out, with only the current text announced. */
@Composable
internal fun GitHubActivityCrossfade(text: String, modifier: Modifier = Modifier, animateChanges: Boolean = true, content: @Composable (String, Boolean) -> Unit) {
    val animationsEnabled = rememberGitHubActivityAnimationsEnabled()
    if (!animateChanges || !animationsEnabled) {
        Box(modifier) { content(text, true) }
    } else {
        Crossfade(text, modifier, tween(GITHUB_ACTIVITY_FADE_MILLIS, easing = LinearEasing), label = "github_activity_replacement") { displayed ->
            val current = displayed == text
            Box(if (current) Modifier else Modifier.clearAndSetSemantics { }) { content(displayed, current) }
        }
    }
}

@Composable
private fun rememberGitHubActivityAnimationsEnabled(): Boolean {
    val reduced by FrameEffectPressure.guard.reduced.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    return !reduced && (durationScale?.scaleFactor ?: 1f) > 0f && lifecycle.isAtLeast(Lifecycle.State.STARTED)
}

/** Use the same linear cycle for the header and latest preview; obey animation scale. */
@Composable
internal fun rememberGitHubProgressMotion(active: Boolean): State<Float> {
    val animationsEnabled = rememberGitHubActivityAnimationsEnabled()
    // Infinite transitions can settle on either endpoint when system animations
    // are disabled. Both endpoints put this indeterminate bar outside the view.
    return if (active && animationsEnabled) {
        rememberInfiniteTransition(label = "github_activity").animateFloat(
            0f,
            1f,
            infiniteRepeatable(tween(GITHUB_PROGRESS_PAN_MILLIS, easing = LinearEasing), RepeatMode.Restart),
            label = "shared_pan"
        )
    } else {
        // A stationary middle phase keeps an indeterminate bar visible.
        remember { mutableFloatStateOf(0.5f) }
    }
}

/** Equal ends and exactly one repeated period make the restart visually continuous. */
internal fun githubProgressBrush(width: Float, phase: Float, base: Color, highlight: Color): Brush {
    val period = width.coerceAtLeast(1f)
    val offset = phase * period
    return Brush.linearGradient(
        listOf(base, highlight, base),
        start = Offset(offset, 0f),
        end = Offset(offset + period, 0f),
        tileMode = TileMode.Repeated
    )
}
