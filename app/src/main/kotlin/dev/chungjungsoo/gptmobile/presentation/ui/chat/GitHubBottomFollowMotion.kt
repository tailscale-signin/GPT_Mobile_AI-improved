package dev.chungjungsoo.gptmobile.presentation.ui.chat

internal const val GITHUB_BOTTOM_FOLLOW_MILLIS = 1500

/** Recalculate a relative step against the measured moving target; never reverse for shrinkage. */
internal fun gitHubBottomFollowStep(remainingPixels: Float, previousFraction: Float, fraction: Float): Float {
    if (!remainingPixels.isFinite() || remainingPixels <= 0f || !previousFraction.isFinite() || !fraction.isFinite()) return 0f
    val share = ((fraction - previousFraction) / (1f - previousFraction).coerceAtLeast(0.000001f)).coerceIn(0f, 1f)
    return remainingPixels * share
}
