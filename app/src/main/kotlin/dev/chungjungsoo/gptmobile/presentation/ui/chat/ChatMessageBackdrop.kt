package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import dev.chungjungsoo.gptmobile.presentation.common.glassBackdropSource

@Composable
internal fun Modifier.chatMessageBackdrop(
    topFadeStart: Dp,
    topFade: Dp,
    bottomFadeStartFromBottom: Dp,
    bottomFadeEndFromBottom: Dp,
    contentVisible: Boolean = true,
    topFadeConcentration: Float = 0f
): Modifier = this
    .graphicsLayer {
        compositingStrategy = if (topFade.value > 0f || bottomFadeStartFromBottom.value > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }
    .drawWithContent {
        drawContent()
        if (size.height <= 0f || (topFade.value <= 0f && bottomFadeStartFromBottom.value <= 0f)) return@drawWithContent

        val topStart = (topFadeStart.toPx() / size.height).coerceIn(0f, .45f)
        val topStop = (topFade.toPx() / size.height).coerceIn(topStart, .45f)
        val topStops = chatTopFadeStops(topStart, topStop, topFadeConcentration)
        val bottomOpaqueStop = (1f - (bottomFadeStartFromBottom.toPx() / size.height))
            .coerceIn(topStop, 1f)
        val bottomTransparentStop = (1f - (bottomFadeEndFromBottom.toPx() / size.height))
            .coerceIn(bottomOpaqueStop, 1f)
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                topStart to Color.Transparent,
                topStops.first to Color.Transparent,
                topStops.second to Color.Black,
                topStop to Color.Black,
                bottomOpaqueStop to Color.Black,
                bottomTransparentStop to Color.Transparent,
                1f to Color.Transparent
            ),
            blendMode = BlendMode.DstIn
        )
    }
    // Record the unfaded messages. The outer mask affects only their visible draw,
    // so the composer still samples real content after the viewport fades it out.
    .glassBackdropSource()
    // Entry visibility belongs inside the source; only viewport edge masks are excluded.
    .graphicsLayer { alpha = if (contentVisible) 1f else 0f }

/**
 * A collapsed header retains a broad soft gradient. Expanding it delays the
 * opaque portion and compresses the visible transition, so the top edge is
 * more transparent without fading a larger fraction of the conversation.
 * Normalized stops also keep narrow landscape windows safe.
 */
internal fun chatTopFadeStops(start: Float, stop: Float, expansion: Float): Pair<Float, Float> {
    val low = start.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    val high = stop.takeIf(Float::isFinite)?.coerceIn(low, 1f) ?: low
    val progress = expansion.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    val eased = progress * progress * (3f - 2f * progress)
    val range = high - low
    // Exactly preserve the original full-width linear fade when collapsed.
    // Expansion retains stronger transparency near the top, then reaches
    // opacity over the final half of the mask for a sharper falloff.
    return (low + range * (0.64f * eased)) to
        (low + range * (1f - 0.14f * eased))
}
