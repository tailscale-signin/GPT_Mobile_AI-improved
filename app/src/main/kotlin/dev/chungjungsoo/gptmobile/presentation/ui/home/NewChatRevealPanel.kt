package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.presentation.common.glassBackdrop

/** Reveal the panel bounds, keeping its text at its natural size throughout the animation. */
@Composable
internal fun NewChatRevealPanel(origin: Offset, content: @Composable () -> Unit) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (revealed || origin == Offset.Unspecified) 1f else 0f) }
    var panelOrigin by remember { mutableStateOf(Offset.Zero) }
    val radius = with(LocalDensity.current) { 36.dp.toPx() }
    LaunchedEffect(Unit) {
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) progress.animateTo(1f, tween(1000, easing = FastOutSlowInEasing)) else progress.snapTo(1f)
        revealed = true
    }
    Box(
        Modifier.fillMaxSize().onGloballyPositioned { panelOrigin = it.localToRoot(Offset.Zero) }.drawWithContent {
            val t = progress.value
            if (t >= 1f) {
                drawContent()
            } else {
                val center = if (origin == Offset.Unspecified) Offset(size.width - radius, size.height - radius) else origin - panelOrigin
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = (center.x - radius) * (1f - t),
                            top = (center.y - radius) * (1f - t),
                            right = center.x + radius + (size.width - center.x - radius) * t,
                            bottom = center.y + radius + (size.height - center.y - radius) * t,
                            cornerRadius = CornerRadius(radius * (1f - t))
                        )
                    )
                }
                clipPath(path) { this@drawWithContent.drawContent() }
            }
        }.glassBackdrop(RectangleShape, opacity = 0.78f, force = true)
    ) { content() }
}
