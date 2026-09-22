package dev.chungjungsoo.gptmobile.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.view.View
import androidx.core.view.isVisible

/**
 * Utility class for fade-in animations on text and chat bubbles.
 */
object AnimationUtil {

    companion object {
        private const val FADE_DURATION_MS = 1000L // 1 second as requested
        private const val STAGGER_DELAY_MS = 200L // Delay between multiple elements
    }

    /**
     * Fade in a single view from 0% to 100% opacity over 1 second.
     */
    fun fadeIn(view: View) {
        if (!view.isVisible) return

        val animator = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f)
            .apply {
                duration = FADE_DURATION_MS
                interpolator = android.animation.DecelerateInterpolator()
            }

        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                view.alpha = 1f // Ensure fully opaque after animation
            }
        })

        animator.start()
    }

    /**
     * Fade in a single TextView from 0% to 100% opacity over 1 second.
     */
    fun fadeInText(textView: android.widget.TextView) {
        if (!textView.isVisible) return

        val animator = ObjectAnimator.ofFloat(textView, "alpha", 0f, 1f)
            .apply {
                duration = FADE_DURATION_MS
                interpolator = android.animation.DecelerateInterpolator()
            }

        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                textView.alpha = 1f // Ensure fully opaque after animation
            }
        })

        animator.start()
    }

    /**
     * Fade in multiple views with staggered delay for sequential appearance.
     */
    fun fadeInSequentially(views: List<View>, delayMs: Long = STAGGER_DELAY_MS) {
        if (views.isEmpty()) return

        views.forEachIndexed { index, view ->
            val startDelay = index.toLong() * delayMs
            val animator = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f)
                .apply {
                    duration = FADE_DURATION_MS
                    interpolator = android.animation.DecelerateInterpolator()
                    startDelay = startDelay
                }

            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.alpha = 1f // Ensure fully opaque after animation
                }
            })

            animator.start()
        }
    }

    /**
     * Fade in multiple TextViews with staggered delay for sequential appearance.
     */
    fun fadeInTextsSequentially(textViews: List<android.widget.TextView>, delayMs: Long = STAGGER_DELAY_MS) {
        if (textViews.isEmpty()) return

        textViews.forEachIndexed { index, textView ->
            val startDelay = index.toLong() * delayMs
            val animator = ObjectAnimator.ofFloat(textView, "alpha", 0f, 1f)
                .apply {
                    duration = FADE_DURATION_MS
                    interpolator = android.animation.DecelerateInterpolator()
                    startDelay = startDelay
                }

            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    textView.alpha = 1f // Ensure fully opaque after animation
                }
            })

            animator.start()
        }
    }

    /**
     * Fade in a chat bubble view from 0% to 100% opacity over 1 second.
     */
    fun fadeInChatBubble(bubbleView: View) {
        fadeIn(bubbleView)
    }

    /**
     * Fade in AI response text with smooth deceleration effect.
     */
    fun fadeInAIResponse(textView: android.widget.TextView) {
        fadeInText(textView)
    }

    /**
     * Fade in tool call result text with smooth deceleration effect.
     */
    fun fadeInToolCallResult(textView: android.widget.TextView) {
        fadeInText(textView)
    }
}
