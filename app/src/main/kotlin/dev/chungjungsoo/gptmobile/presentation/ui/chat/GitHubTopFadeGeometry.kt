package dev.chungjungsoo.gptmobile.presentation.ui.chat

internal enum class GitHubFadeProfile(val label: String, val expandedExtra: Float) {
    SUBTLE("Subtle", .26f),
    ADAPTIVE("Adaptive", .44f),
    CINEMATIC("Cinematic", .46f)
}

internal fun gitHubTopFadeHeight(
    headerHeight: Float,
    stableWindowHeight: Float,
    enabled: Boolean,
    expansionProgress: Float = 0f,
    profile: GitHubFadeProfile = GitHubFadeProfile.ADAPTIVE
): Float {
    if (!enabled) return 0f
    val progress = expansionProgress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    val eased = progress * progress * (3f - 2f * progress)
    val window = stableWindowHeight.coerceAtLeast(0f)
    val header = headerHeight.coerceAtLeast(0f)
    val collapsedExtra = window * .03f
    // Keep at least half the available viewport readable on short/landscape displays.
    val maximumExtra = ((window - header).coerceAtLeast(0f) * .5f).coerceAtLeast(collapsedExtra)
    val expandedExtra = (window * profile.expandedExtra).coerceAtMost(maximumExtra)
    return header + collapsedExtra + (expandedExtra - collapsedExtra) * eased
}
