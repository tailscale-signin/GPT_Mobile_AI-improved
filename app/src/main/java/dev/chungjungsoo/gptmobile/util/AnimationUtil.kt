package dev.chungjungsoo.gptmobile.util

import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.max

object AnimationUtil {
    
    fun fadeIn(view: View) {
        view.alpha = 0f
        animate(view).alpha(1f).setDuration(1000L).setInterpolator(DecelerateInterpolator()).start()
    }
    
    fun fadeInSequentially(views: List<View>, delayMs: Long = 200L) {
        views.forEachIndexed { index, view ->
            animate(view).alpha(1f)
                .setDuration(1000L)
                .setInterpolator(DecelerateInterpolator())
                .setStartDelay((index.toLong() * delayMs).coerceAtLeast(0L))
                .start()
        }
    }
    
    private fun animate(view: View) = view.animate()
}
