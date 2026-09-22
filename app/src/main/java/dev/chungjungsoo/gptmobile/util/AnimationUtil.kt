package dev.chungjungsoo.gptmobile.util

import android.view.animation.DecelerateInterpolator
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Fades a view from 0% to 100% opacity over exactly 1 second
 * Uses DecelerateInterpolator for natural easing
 */
@Composable
fun fadeIn(view: android.view.View, animationSpec: AnimationSpec<Float> = tween(durationMillis = 1000L, easing = DecelerateInterpolator())) {
    val alpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = animationSpec,
        label = "fadeIn"
    )
    Box(
        modifier = Modifier
            .background(Color.Transparent)
            .then(Modifier.alpha(alpha))
    ) {
        view
    }
}

/**
 * Fades multiple views sequentially with a delay between each
 */
@Composable
fun fadeInSequentially(views: List<android.view.View>, delayMs: Long = 200L) {
    views.forEachIndexed { index, view ->
        val startDelay = animationSpecs.delay(delayMs * index.toLong())
        fadeIn(view, animationSpec = tween(durationMillis = 1000L, delayMillis = startDelay))
    }
}

/**
 * Custom decelerate interpolator for fade animations
 */
class DecelerateInterpolator : android.view.animation.Interpolator {
    override fun getInterpolation(input: Float): Float {
        return 1f - (1f - input) * (1f - input)
    }
}
