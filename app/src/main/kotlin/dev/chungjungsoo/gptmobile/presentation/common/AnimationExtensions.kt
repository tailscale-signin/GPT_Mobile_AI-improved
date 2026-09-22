package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color

/**
 * Applies a fade-in animation to a composable.
 * Starts at 0% opacity and fades to 100% over the specified duration (default: 1000ms).
 * 
 * @param durationMs Duration of the fade animation in milliseconds
 * @param targetAlpha Target alpha value (default: 1.0f)
 */
@Composable
fun Modifier.fadeInAnimation(
    durationMs: Int = 1000,
    targetAlpha: Float = 1.0f
): Modifier {
    val alpha by remember {
        Animatable(0f).also {
            LaunchedEffect(Unit) {
                it.animateTo(targetValue = targetAlpha, animationSpec = tween(durationMillis = durationMs))
            }
        }
    }
    return this.alpha(alpha.value)
}

/**
 * Applies a fade-in animation to text content.
 * Starts at 0% opacity and fades to 100% over the specified duration (default: 1000ms).
 * 
 * @param durationMs Duration of the fade animation in milliseconds
 * @param targetAlpha Target alpha value (default: 1.0f)
 */
@Composable
fun BoxScope.fadeInTextAnimation(
    text: String,
    durationMs: Int = 1000,
    targetAlpha: Float = 1.0f
) {
    val alpha by remember {
        Animatable(0f).also {
            LaunchedEffect(Unit) {
                it.animateTo(targetValue = targetAlpha, animationSpec = tween(durationMillis = durationMs))
            }
        }
    }
    
    androidx.compose.material3.Text(
        text = text,
        color = Color.Unspecified.copy(alpha = alpha.value),
        modifier = Modifier.alpha(alpha.value)
    )
}
