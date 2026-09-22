package dev.chungjungsoo.gptmobile.util

import android.animation.ObjectAnimator
import android.view.View
import androidx.core.animation.doOnEnd
import kotlin.math.abs

/**
 * Utility class for fade-in animations.
 * Provides smooth 0% to 100% opacity transitions over 1 second.
 */
object AnimationUtil {
    
    /**
     * Fade in a single view from 0% to 100% opacity over 1 second.
     * Uses DecelerateInterpolator for natural feel.
     *
     * @param view The view to animate
     */
    fun fadeIn(view: View) {
        val animator = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f)
        animator.duration = 1000L // 1 second as requested
        animator.interpolator = DecelerateInterpolator()
        animator.start()
    }
    
    /**
     * Fade in a single view from 0% to 100% opacity over specified duration.
     *
     * @param view The view to animate
     * @param durationMs Duration in milliseconds (default: 1000ms)
     */
    fun fadeIn(view: View, durationMs: Long = 1000L) {
        val animator = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f)
        animator.duration = durationMs
        animator.interpolator = DecelerateInterpolator()
        animator.start()
    }
    
    /**
     * Fade in multiple views sequentially with staggered delays.
     * Each view fades from 0% to 100% over 1 second.
     *
     * @param views The list of views to animate
     * @param delayMs Delay between each fade-in (default: 200ms)
     */
    fun fadeInSequentially(views: List<View>, delayMs: Long = 200L) {
        if (views.isEmpty()) return
        
        views.forEachIndexed { index, view ->
            val animator = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f)
            animator.duration = 1000L // 1 second as requested
            animator.interpolator = DecelerateInterpolator()
            animator.startDelay = index.toLong() * delayMs
            animator.start()
        }
    }
    
    /**
     * Fade in a TextView from 0% to 100% opacity over 1 second.
     *
     * @param textView The TextView to animate
     */
    fun fadeInText(textView: android.widget.TextView) {
        fadeIn(textView)
    }
    
    /**
     * Fade in a TextView from 0% to 100% opacity over specified duration.
     *
     * @param textView The TextView to animate
     * @param durationMs Duration in milliseconds (default: 1000ms)
     */
    fun fadeInText(textView: android.widget.TextView, durationMs: Long = 1000L) {
        fadeIn(textView, durationMs)
    }
    
    /**
     * Fade in a View with custom interpolator.
     *
     * @param view The view to animate
     * @param interpolator Custom interpolator (default: DecelerateInterpolator)
     */
    fun fadeIn(view: View, interpolator: android.view.animation.Interpolator = DecelerateInterpolator()) {
        val animator = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f)
        animator.duration = 1000L
        animator.interpolator = interpolator
        animator.start()
    }
}

/**
 * DecelerateInterpolator for smooth fade-in effects.
 */
class DecelerateInterpolator : android.view.animation.Interpolator {
    override fun getInterpolation(input: Float): Float {
        return 1f - (1f - input) * (1f - input)
    }
}