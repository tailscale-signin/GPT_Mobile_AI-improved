package dev.chungjungsoo.gptmobile.util

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay

/**
 * Animation utilities for Compose-based animations.
 * Replaces deprecated ObjectAnimator with modern Compose APIs.
 */
object AnimationUtil {
    
    companion object {
        const val DEFAULT_DURATION_MS: Long = 1000L
        const val SEGMENT_DELAY_MS: Long = 50L
    }
    
    /**
     * Creates a staggered fade-in modifier for text segments.
     * Each segment fades in sequentially with a delay between them.
     */
    fun StaggeredFadeInModifier(
        textSegments: List<String>,
        durationMs: Long = DEFAULT_DURATION_MS,
        delayMs: Long = SEGMENT_DELAY_MS
    ): Modifier {
        return Modifier.animateEachIndexed { index, value ->
            val startDelay = (index * delayMs).toLong()
            delay(startDelay)
            value.alpha = 1f
        }
    }
    
    /**
     * Creates a pulsating LED modifier with cyan gradient colors.
     * Simulates an LED notification indicator.
     */
    fun PulsatingLedModifier(
        durationMs: Long = DEFAULT_DURATION_MS,
        color1: Color = Color(0xFF00E5FF),
        color2: Color = Color(0xFF00BCD4)
    ): Modifier {
        return Modifier.animateEachIndexed { index, value ->
            val startDelay = (index * SEGMENT_DELAY_MS).toLong()
            delay(startDelay)
            value.alpha = 1f
            value.scale = 1.0f
        }
    }
}
