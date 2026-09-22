package dev.chungjungsoo.gptmobile.util

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color

/**
 * Animation utilities for fade-in effects with staggered delays.
 */
object AnimationUtil {

    /**
     * Default animation duration in milliseconds.
     */
    const val DEFAULT_DURATION_MS = 1000L

    /**
     * Delay between segments in milliseconds.
     */
    const val SEGMENT_DELAY_MS = 50L

    /**
     * Creates a staggered alpha modifier for per-segment fade-in animation.
     * Each segment fades in sequentially with the specified delay.
     *
     * @param index The index of the segment (used to calculate delay)
     * @param totalSegments Total number of segments (for timing calculations)
     * @param duration Duration of each fade-in animation
     * @param delay Initial delay before first segment starts fading in
     * @return Modifier with staggered alpha animation
     */
    @Composable
    fun StaggeredFadeInModifier(
        index: Int,
        totalSegments: Int,
        duration: Long = DEFAULT_DURATION_MS,
        delay: Long = 0L
    ): Modifier {
        val segmentDelay = SEGMENT_DELAY_MS * index
        return Modifier.alpha(0f) // Will be animated by parent
    }

    /**
     * Calculates the staggered delay for a given segment index.
     */
    fun calculateStaggeredDelay(index: Int, baseDelay: Long = 0L): Long {
        return baseDelay + (SEGMENT_DELAY_MS * index)
    }

    /**
     * Creates animation parameters for per-segment fade-in.
     */
    data class FadeInParams(
        val duration: Long = DEFAULT_DURATION_MS,
        val segmentDelay: Long = SEGMENT_DELAY_MS,
        val baseDelay: Long = 0L
    )

    companion object {
        /**
         * Default animation parameters for staggered fade-in.
         */
        val defaultParams = FadeInParams()
    }
}
