package dev.chungjungsoo.gptmobile.presentation.ui.chat

import kotlin.math.sqrt

/** Enlarge short prose only. Regular/accessibility font size is always the floor. */
internal fun adaptiveAnswerScale(characters: Int, widthDp: Float, heightDp: Float, baseFontSp: Float, fontScale: Float, explicitLines: Int = 1): Float {
    if (characters <= 0 || characters >= 1200 || !widthDp.isFinite() || !heightDp.isFinite() || !baseFontSp.isFinite() || !fontScale.isFinite() || widthDp <= 0 || heightDp <= 0) return 1f
    val bucket = ((characters + 31) / 32) * 32
    val lengthScale = 1f + 0.65f * (1f - ((bucket - 160).coerceAtLeast(0) / 1040f)).coerceIn(0f, 1f)
    val fontDp = baseFontSp.coerceAtLeast(1f) * fontScale.coerceAtLeast(0.1f)
    val height = ((heightDp - 160f).coerceAtLeast(40f) * 0.5f).coerceAtMost(280f)
    val areaScale = sqrt((widthDp * height / (bucket * fontDp * fontDp * 0.65f * 1.5f)).coerceAtLeast(0f))
    val lineScale = height / (explicitLines.coerceAtLeast(1) * fontDp * 1.5f)
    return minOf(lengthScale, areaScale, lineScale).coerceIn(1f, 1.65f)
}
