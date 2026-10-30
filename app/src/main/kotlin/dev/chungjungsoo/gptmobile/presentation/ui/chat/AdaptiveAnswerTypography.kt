package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity

internal data class AnswerPane(val widthDp: Float = 360f, val heightDp: Float = 640f)
internal val LocalAnswerPane = staticCompositionLocalOf<AnswerPane?> { null }
internal val LocalAnswerTextScale = staticCompositionLocalOf { 1f }
internal val LocalAdaptiveAnswerText = staticCompositionLocalOf { true }

@Composable
internal fun AdaptiveAnswerTypography(source: String, answerLength: Int?, content: @Composable () -> Unit) {
    if (answerLength == null || !LocalAdaptiveAnswerText.current) {
        CompositionLocalProvider(LocalAnswerTextScale provides 1f, content = content)
        return
    }
    val config = LocalConfiguration.current
    val pane = LocalAnswerPane.current ?: AnswerPane(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat())
    val density = LocalDensity.current
    val body = MaterialTheme.typography.bodyMedium
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Tables, code and formulas retain their layout; prose uses the full answer's
        // length, including when research/Combined output is split into small blocks.
        val structured = answerLength >= 1200 || source.contains("```") || source.contains("~~~") || source.lineSequence().take(100).any { it.count { char -> char == '|' } >= 2 } || source.contains("\\[") || source.contains("$$")
        val target = if (structured) {
            1f
        } else {
            adaptiveAnswerScale(
                answerLength,
                if (maxWidth.value.isFinite()) maxWidth.value else pane.widthDp,
                pane.heightDp,
                body.fontSize.value,
                density.fontScale,
                source.count { it == '\n' } + 1
            )
        }
        val scale by animateFloatAsState(target, tween(180), label = "answerFontScale")
        CompositionLocalProvider(LocalAnswerTextScale provides scale, content = content)
    }
}
