package dev.chungjungsoo.gptmobile.presentation.ui.chat

internal fun gitHubTopFadeHeight(headerHeight: Float, stableWindowHeight: Float, enabled: Boolean): Float =
    if (enabled) headerHeight.coerceAtLeast(0f) + stableWindowHeight.coerceAtLeast(0f) * 0.03f else 0f
