package dev.chungjungsoo.gptmobile.util

import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.ObjectAnimator

object AnimationUtil {
    
    fun fadeIn(view: View) {
        val animator = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f)
        animator.duration = 1000L // 1 second as requested
        animator.interpolator = DecelerateInterpolator()
        animator.start()
    }
    
    fun fadeInSequentially(views: List<View>, delayMs: Long = 200L) {
        views.forEachIndexed { index, view ->
            val animator = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f)
            animator.duration = 1000L
            animator.interpolator = DecelerateInterpolator()
            animator.startDelay = index.toLong() * delayMs
            animator.start()
        }
    }
}