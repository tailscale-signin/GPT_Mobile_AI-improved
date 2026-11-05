package dev.chungjungsoo.gptmobile.presentation.ui.chat

import kotlin.math.exp

/** Follow measured growth without reversing for layout corrections or jumping after a stalled frame. */
internal fun gitHubBottomFollowStep(remainingPixels: Float, elapsedNanos: Long): Float {
    if (!remainingPixels.isFinite() || remainingPixels <= 0f || elapsedNanos <= 0) return 0f
    if (remainingPixels <= .5f) return remainingPixels
    val elapsedMillis = (elapsedNanos / 1_000_000.0).coerceAtMost(32.0)
    val fraction = (1.0 - exp(-elapsedMillis / 80.0)).toFloat()
    return (remainingPixels * fraction).coerceIn(.5f, remainingPixels)
}
