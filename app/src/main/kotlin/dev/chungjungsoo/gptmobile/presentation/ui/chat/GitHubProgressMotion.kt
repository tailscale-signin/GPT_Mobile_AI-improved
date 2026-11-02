package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode

internal const val GITHUB_PROGRESS_PAN_MILLIS = 1800

/** Use the same linear cycle for the header and latest preview; obey animation scale. */
@Composable
internal fun rememberGitHubProgressMotion(active: Boolean): State<Float> = if (active) {
    rememberInfiniteTransition(label = "github_activity").animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(GITHUB_PROGRESS_PAN_MILLIS, easing = LinearEasing), RepeatMode.Restart),
        label = "shared_pan"
    )
} else {
    remember { mutableFloatStateOf(0f) }
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
