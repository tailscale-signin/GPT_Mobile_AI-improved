package dev.chungjungsoo.gptmobile.util

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Animation utilities for Compose-based UI animations.
 * Replaces deprecated ObjectAnimator with modern Compose Animatable API.
 */
object AnimationUtil {
    
    companion object {
        const val DEFAULT_DURATION_MS = 1000L
        const val SEGMENT_DELAY_MS = 50L
        
        /**
         * Creates a staggered fade-in modifier for text segments.
         * Each segment fades in sequentially with a delay between them.
         */
        @Composable
        fun StaggeredFadeInModifier(
            index: Int,
            totalSegments: Int,
            duration: Long = DEFAULT_DURATION_MS,
            baseDelay: Long = 0L
        ): Modifier {
            val segmentDelay = baseDelay + (SEGMENT_DELAY_MS * index)
            val alphaAnim = remember { Animatable(0f) }
            
            LaunchedEffect(index, segmentDelay, duration) {
                if (segmentDelay > 0L) delay(segmentDelay.toLong())
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
         * Calculates staggered delay for a given segment index.
         */
        fun calculateStaggeredDelay(index: Int, baseDelay: Long = 0L): Long {
            return baseDelay + (SEGMENT_DELAY_MS * index)
        }
        
        /**
         * Creates a pulsating LED effect modifier for notification icons.
         */
        @Composable
        fun PulsatingLedModifier(
            color1: Color = Color(0xFF00E5FF),
            color2: Color = Color(0xFF00BCD4),
            duration: Long = 1500L,
            repeatCount: Int = Int.MAX_VALUE
        ): Modifier {
            val scaleAnim = remember { Animatable(1f) }
            
            LaunchedEffect(duration, repeatCount) {
                while (repeatCount == Int.MAX_VALUE || scaleAnim.repeatCount < repeatCount) {
                    scaleAnim.animateTo(
                        targetValue = 1.05f,
                        animationSpec = tween(durationMillis = duration / 2, easing = LinearEasing)
                    )
                    scaleAnim.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = duration / 2, easing = LinearEasing)
                    )
                }
            }
            
            return Modifier.scale(scaleAnim.value)
        }
    }
}
