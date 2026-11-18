package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

internal const val GITHUB_COMPLETION_PROGRESS_FADE_MILLIS = 1000
internal const val GITHUB_COMPLETION_ANSWER_FADE_MILLIS = 2000
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

internal data class GitHubCompletionReveal(
    val progressVisible: Boolean,
    val showAnswer: Boolean,
    val progressAlpha: Float,
    val answerAlpha: Float,
    val answerColor: Color
)

/**
 * The progress card is retired before the final answer is mounted. Repeated
 * recomposition, a saved conversation, and a temporarily empty terminal reply
 * cannot restart the animation.
 */
@Composable
internal fun rememberGitHubCompletionReveal(
    identity: Any,
    inProgress: Boolean,
    hasFinalAnswer: Boolean,
    animate: Boolean,
    accent: Color,
    settled: Color
): GitHubCompletionReveal {
    var observedLive by remember(identity) { mutableStateOf(false) }
    var outroCompleted by remember(identity) { mutableStateOf(false) }
    val progress = remember(identity) { Animatable(1f) }
    val answer = remember(identity) { Animatable(1f) }
    LaunchedEffect(identity, inProgress, hasFinalAnswer, animate) {
        when {
            inProgress -> {
                observedLive = true
                outroCompleted = false
                progress.snapTo(1f)
                answer.snapTo(1f)
            }
            observedLive && hasFinalAnswer && !outroCompleted && animate -> {
                progress.snapTo(1f)
                progress.animateTo(0f, tween(GITHUB_COMPLETION_PROGRESS_FADE_MILLIS, easing = LinearEasing))
                // Mount the finished document at zero opacity only after the
                // transient summaries and previews have fully disappeared.
                answer.snapTo(0f)
                outroCompleted = true
                answer.animateTo(1f, tween(GITHUB_COMPLETION_ANSWER_FADE_MILLIS, easing = LinearEasing))
            }
            !hasFinalAnswer -> {
                // An error/blocked turn has no final text to celebrate.
                progress.snapTo(0f)
                answer.snapTo(1f)
            }
            else -> {
                outroCompleted = true
                progress.snapTo(0f)
                answer.snapTo(1f)
            }
        }
    }
    val fadingProgress = !inProgress && observedLive && hasFinalAnswer && !outroCompleted && animate
    return GitHubCompletionReveal(
        progressVisible = inProgress || fadingProgress,
        showAnswer = !fadingProgress,
        progressAlpha = if (inProgress) 1f else progress.value,
        answerAlpha = if (inProgress) 1f else answer.value,
        answerColor = lerp(accent, settled, if (inProgress) 1f else answer.value)
    )
}

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
