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
    val window = stableWindowHeight.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: return 0f
    val header = headerHeight.takeIf(Float::isFinite)?.coerceIn(0f, window) ?: 0f
    val collapsedExtra = window * .03f
    // Bound the extension in dp so tablets and a transient large header cannot blank the transcript.
    val expandedExtra = (window * profile.expandedExtra).coerceAtMost(128f * profile.expandedExtra / .44f)
    return (header + collapsedExtra + (expandedExtra - collapsedExtra) * eased).coerceAtMost(window * .45f)
}
