package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AppFeatureSettings(
    val backgroundGeneration: Boolean = true,
    val responseNotifications: Boolean = true,
    val automaticConversationTitles: Boolean = true,
    val archiveOlderAssistantReplies: Boolean = true,
    val smartSuggestions: Boolean = true,
    val remoteMcpConnections: Boolean = true,
    val deviceLocationTool: Boolean = true,
    val providerModelDiscovery: Boolean = true,
    val diagnosticsCollection: Boolean = false,
    val openRouterBatchProcessing: Boolean = false,
    val qnnAutomaticFallback: Boolean = true
) {
    fun withFeature(feature: AppFeature, enabled: Boolean): AppFeatureSettings = when (feature) {
        AppFeature.BACKGROUND_GENERATION -> copy(backgroundGeneration = enabled)
        AppFeature.RESPONSE_NOTIFICATIONS -> copy(responseNotifications = enabled)
        AppFeature.AUTOMATIC_TITLES -> copy(automaticConversationTitles = enabled)
        AppFeature.ARCHIVE_OLDER_REPLIES -> copy(archiveOlderAssistantReplies = enabled)
        AppFeature.SMART_SUGGESTIONS -> copy(smartSuggestions = enabled)
        AppFeature.REMOTE_MCP -> copy(remoteMcpConnections = enabled)
        AppFeature.DEVICE_LOCATION -> copy(deviceLocationTool = enabled)
        AppFeature.MODEL_DISCOVERY -> copy(providerModelDiscovery = enabled)
        AppFeature.DIAGNOSTICS -> copy(diagnosticsCollection = enabled)
        AppFeature.OPENROUTER_BATCH -> copy(openRouterBatchProcessing = enabled)
        AppFeature.QNN_AUTO_FALLBACK -> copy(qnnAutomaticFallback = enabled)
    }
}

enum class AppFeature(
    val title: String,
    val description: String
) {
    BACKGROUND_GENERATION(
        "Background generation",
        "Keep active AI responses running when the app leaves the foreground."
    ),
    RESPONSE_NOTIFICATIONS(
        "Response notifications",
        "Show a detailed notification when a background AI response finishes."
    ),
    AUTOMATIC_TITLES(
        "Automatic conversation titles",
        "Refresh generated titles as the conversation evolves."
    ),
    ARCHIVE_OLDER_REPLIES(
        "Archive older responses",
        "Collapse assistant replies older than the latest three into expandable history."
    ),
    SMART_SUGGESTIONS(
        "Smart response suggestions",
        "Generate contextual suggestion buttons below assistant responses."
    ),
    REMOTE_MCP(
        "Remote MCP connections",
        "Allow profiles to discover and call tools from remote MCP servers."
    ),
    DEVICE_LOCATION(
        "Device location tool",
        "Allow profiles with the location tool assigned to request phone location."
    ),
    MODEL_DISCOVERY(
        "Provider model discovery",
        "Index models from supported remote providers for searchable profile model pickers."
    ),
    DIAGNOSTICS(
        "Diagnostics collection",
        "Collect local performance, token, runtime and tool metrics for Debug Mode."
    ),
    OPENROUTER_BATCH(
        "OpenRouter batch processing",
        "Allow asynchronous OpenRouter Batch API jobs for supported workloads."
    ),
    QNN_AUTO_FALLBACK(
        "QNN automatic fallback",
        "Automatically switch to LiteRT when Qualcomm QNN cannot load the selected model."
    )
}
