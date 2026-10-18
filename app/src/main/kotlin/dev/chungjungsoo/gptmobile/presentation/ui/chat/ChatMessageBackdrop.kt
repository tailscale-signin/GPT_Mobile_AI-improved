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
    bottomFadeEndFromBottom: Dp
): Modifier = this
    .graphicsLayer {
        compositingStrategy = if (topFade.value > 0f || bottomFadeStartFromBottom.value > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }
    .drawWithContent {
        drawContent()
        if (size.height <= 0f || (topFade.value <= 0f && bottomFadeStartFromBottom.value <= 0f)) return@drawWithContent

        val topStart = (topFadeStart.toPx() / size.height).coerceIn(0f, 0.45f)
        val topStop = (topFade.toPx() / size.height).coerceIn(topStart, 0.5f)
        val bottomOpaqueStop = (1f - (bottomFadeStartFromBottom.toPx() / size.height))
            .coerceIn(topStop, 1f)
        val bottomTransparentStop = (1f - (bottomFadeEndFromBottom.toPx() / size.height))
            .coerceIn(bottomOpaqueStop, 1f)
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                topStart to Color.Transparent,
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
