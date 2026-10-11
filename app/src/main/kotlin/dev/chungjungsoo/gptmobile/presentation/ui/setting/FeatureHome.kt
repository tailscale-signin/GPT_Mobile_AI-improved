package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.model.AppFeature

/** Each persisted feature has one canonical settings home; legacy advanced links remain valid. */
internal enum class FeatureHome(val title: String, val breadcrumb: String) {
    APPEARANCE("Display & motion", "Appearance › Display"),
    CONVERSATIONS("Conversations", "Settings › Conversations"),
    RESEARCH("Research controls", "Tools › Research"),
    GITHUB("GitHub efficiency", "Tools › GitHub › Efficiency"),
    LOCAL("Local runtime controls", "AI › Local › Runtime"),
    TOOLS("Tool execution", "Tools › Execution"),
    CONNECTIONS("Provider discovery", "AI › Connections"),
    DIAGNOSTICS("Diagnostic collection", "Activity › Diagnostics"),
    OPENROUTER("OpenRouter batch", "AI › Connections › OpenRouter")
}

internal fun featureHome(feature: AppFeature): FeatureHome = when (feature) {
    AppFeature.COMPACT_CHAT_LIST, AppFeature.RESPONSE_ANIMATION, AppFeature.EDGE_FADES -> FeatureHome.APPEARANCE
    AppFeature.SMOOTH_STREAMING, AppFeature.CENTER_UNREAD, AppFeature.MESSAGE_TIMESTAMPS,
    AppFeature.QUEUED_FOLLOW_UPS, AppFeature.BACKGROUND_GENERATION, AppFeature.RESPONSE_NOTIFICATIONS,
    AppFeature.AUTOMATIC_TITLES, AppFeature.ARCHIVE_OLDER_REPLIES, AppFeature.SHOW_REASONING,
    AppFeature.SMART_SUGGESTIONS -> FeatureHome.CONVERSATIONS
    AppFeature.PARALLEL_SEARCH, AppFeature.DEDUPLICATE_SEARCH, AppFeature.REUSE_SEARCH,
    AppFeature.DEDUPLICATE_SEARCH_CONTENT -> FeatureHome.RESEARCH
    AppFeature.GITHUB_CONDITIONAL_READS, AppFeature.GITHUB_BLOB_CACHE -> FeatureHome.GITHUB
    AppFeature.LOCAL_MODEL_CACHE, AppFeature.LOCAL_ASSIST, AppFeature.LOCAL_NATIVE_METRICS,
    AppFeature.QNN_AUTO_FALLBACK -> FeatureHome.LOCAL
    AppFeature.REMOTE_MCP, AppFeature.SHARED_TOOL_CALLS, AppFeature.DEVICE_LOCATION -> FeatureHome.TOOLS
    AppFeature.MODEL_DISCOVERY -> FeatureHome.CONNECTIONS
    AppFeature.DIAGNOSTICS -> FeatureHome.DIAGNOSTICS
    AppFeature.OPENROUTER_BATCH -> FeatureHome.OPENROUTER
}
