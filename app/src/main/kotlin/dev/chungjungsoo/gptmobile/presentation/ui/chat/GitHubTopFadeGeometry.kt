package dev.chungjungsoo.gptmobile.presentation.ui.chat

internal enum class GitHubFadeProfile(val label: String, val expandedExtra: Float) {
    SUBTLE("Subtle", .05f),
    ADAPTIVE("Adaptive", .10f),
    CINEMATIC("Cinematic", .15f)
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
    return headerHeight.coerceAtLeast(0f) + stableWindowHeight.coerceAtLeast(0f) * (.03f + (profile.expandedExtra - .03f) * eased)
}
