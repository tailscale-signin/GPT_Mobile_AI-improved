package dev.chungjungsoo.gptmobile.util

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.delay

/**
 * Animation utilities for Compose-based animations.
 * Replaces deprecated ObjectAnimator with modern Compose APIs.
 */
object AnimationUtil {

    const val DEFAULT_DURATION_MS: Long = 1000L
    const val SEGMENT_DELAY_MS: Long = 50L

    /**
     * Creates a staggered alpha modifier for per-segment fade-in animation.
     * Each segment fades in sequentially with the specified delay.
     *
     * @param index The index of the segment (used to calculate delay)
     * @param totalSegments Total number of segments (for timing calculations)
     * @param duration Duration of each fade-in animation in milliseconds
     * @param delay Initial delay before first segment starts fading in
     * @return Modifier with staggered alpha animation
     */
    @Composable
    fun StaggeredFadeInModifier(
        index: Int,
        totalSegments: Int = 1,
        duration: Long = DEFAULT_DURATION_MS,
        delay: Long = 0L
    ): Modifier {
        val segmentDelay = delay + (SEGMENT_DELAY_MS * index)
        val alphaAnim = remember(index) { Animatable(0f) }

        LaunchedEffect(index, segmentDelay, duration) {
            if (segmentDelay > 0L) {
                delay(segmentDelay)
            }
            alphaAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = duration.toInt(),
                    easing = FastOutSlowInEasing
                )
            )
        }

        return Modifier.alpha(alphaAnim.value)
    }

    /**
     * Calculates the staggered delay for a given segment index.
     */
    fun calculateStaggeredDelay(index: Int, baseDelay: Long = 0L): Long {
        return baseDelay + (SEGMENT_DELAY_MS * index)
    }

    /**
     * Animation parameters for per-segment fade-in.
     */
    data class FadeInParams(
        val duration: Long = DEFAULT_DURATION_MS,
        val segmentDelay: Long = SEGMENT_DELAY_MS,
        val baseDelay: Long = 0L
    )

    val defaultParams = FadeInParams()
}
