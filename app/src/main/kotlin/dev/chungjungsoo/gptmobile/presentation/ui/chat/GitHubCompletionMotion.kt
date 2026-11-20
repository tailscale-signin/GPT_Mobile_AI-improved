package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal const val GITHUB_COMPLETION_PROMPT_SCROLL_MILLIS = 2000
internal const val GITHUB_COMPLETION_GLOW_HOLD_MILLIS = 3000
internal const val GITHUB_COMPLETION_GLOW_FADE_MILLIS = 2000

/** Reopening historical messages must never masquerade as a newly completed generation. */
internal fun shouldBeginGitHubFinalReveal(
    github: Boolean,
    observedLiveTurn: Boolean,
    awaitingTurn: Boolean,
    hasCompletedAnswer: Boolean,
    delivered: Boolean
): Boolean = github && observedLiveTurn && !awaitingTurn && hasCompletedAnswer && !delivered

/** Draw only on the phone's outer edges; do not create a touch/focus surface. */
internal fun Modifier.githubCompletionEdgeGlow(amount: Float, accent: Color): Modifier = drawWithContent {
    drawContent()
    val opacity = amount.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    if (opacity <= 0f || size.width <= 0f || size.height <= 0f) return@drawWithContent
    val reach = 36.dp.toPx().coerceAtMost(size.minDimension / 3f)
    val glow = accent.copy(alpha = .32f * opacity)
    drawRect(
        Brush.horizontalGradient(listOf(glow, Color.Transparent), startX = 0f, endX = reach),
        topLeft = Offset.Zero,
        size = Size(reach, size.height)
    )
    drawRect(
        Brush.horizontalGradient(
            listOf(Color.Transparent, glow),
            startX = size.width - reach,
            endX = size.width
        ),
        topLeft = Offset(size.width - reach, 0f),
        size = Size(reach, size.height)
    )
    drawRect(
        Brush.verticalGradient(listOf(glow, Color.Transparent), startY = 0f, endY = reach),
        topLeft = Offset.Zero,
        size = Size(size.width, reach)
    )
    drawRect(
        Brush.verticalGradient(
            listOf(Color.Transparent, glow),
            startY = size.height - reach,
            endY = size.height
        ),
        topLeft = Offset(0f, size.height - reach),
        size = Size(size.width, reach)
    )
}

/** User reading position and active bottom following outrank cinematic completion. */
internal fun mayRepositionCompletedPrompt(mode: GitHubScrollMode, dragging: Boolean): Boolean =
    mode == GitHubScrollMode.PIN_PROMPT && !dragging
