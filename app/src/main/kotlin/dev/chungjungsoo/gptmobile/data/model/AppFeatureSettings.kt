package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AppFeatureSettings(
    val smoothStreaming: Boolean = true,
    val centerUnread: Boolean = true,
    val responseAnimation: Boolean = true,
    val edgeFades: Boolean = true,
    val messageTimestamps: Boolean = true,
    val queuedFollowUps: Boolean = true,
    val parallelSearch: Boolean = true,
    val deduplicateSearch: Boolean = true,
    val githubConditionalReads: Boolean = true,
    val githubBlobCache: Boolean = true,
    val backgroundGeneration: Boolean = true,
    val responseNotifications: Boolean = true,
    val automaticConversationTitles: Boolean = true,
    val archiveOlderAssistantReplies: Boolean = true,
    val smartSuggestions: Boolean = true,
    val showReasoning: Boolean = true,
    val remoteMcpConnections: Boolean = true,
    val sharedReadOnlyToolCalls: Boolean = true,
    val deviceLocationTool: Boolean = true,
    val providerModelDiscovery: Boolean = true,
    val diagnosticsCollection: Boolean = false,
    val debugShowToolCalls: Boolean = true,
    val debugShowTotalTokens: Boolean = true,
    val debugShowTokenSpeed: Boolean = true,
    val debugShowTimeToFirstToken: Boolean = true,
    val debugShowRuntime: Boolean = true,
    val debugShowHardware: Boolean = true,
    val debugShowNetwork: Boolean = false,
    val openRouterBatchProcessing: Boolean = false,
    val qnnAutomaticFallback: Boolean = true,
    val localCpuThreads: Int = 0,
    val localModelCache: Boolean = true,
    val localSpeculativeDecoding: SpeculativeDecodingMode = SpeculativeDecodingMode.AUTO,
    val localNativeMetrics: Boolean = false,
    val localIdleMinutes: Int = 10,
    /** Global enable state for integrated, in-app tool plugins. Missing entries default to enabled. */
    val pluginExecution: Map<String, PluginExecutionSettings> = emptyMap(),
    val toolPluginStates: Map<String, Boolean> = emptyMap(),
    val delegation: ModelDelegationSettings = ModelDelegationSettings(),
    val profileBehavior: Map<String, ProfileBehaviorSettings> = emptyMap(),
    val conversationReasoning: Map<Int, Boolean> = emptyMap(),
    val conversationDelegation: Map<Int, ConversationDelegationSettings> = emptyMap(),
    val tokenBudget: dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings = dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings()
) {
    fun withFeature(feature: AppFeature, enabled: Boolean): AppFeatureSettings = when (feature) {
        AppFeature.SMOOTH_STREAMING -> copy(smoothStreaming = enabled)
        AppFeature.CENTER_UNREAD -> copy(centerUnread = enabled)
        AppFeature.RESPONSE_ANIMATION -> copy(responseAnimation = enabled)
        AppFeature.EDGE_FADES -> copy(edgeFades = enabled)
        AppFeature.MESSAGE_TIMESTAMPS -> copy(messageTimestamps = enabled)
        AppFeature.QUEUED_FOLLOW_UPS -> copy(queuedFollowUps = enabled)
        AppFeature.PARALLEL_SEARCH -> copy(parallelSearch = enabled)
        AppFeature.DEDUPLICATE_SEARCH -> copy(deduplicateSearch = enabled)
        AppFeature.GITHUB_CONDITIONAL_READS -> copy(githubConditionalReads = enabled)
        AppFeature.GITHUB_BLOB_CACHE -> copy(githubBlobCache = enabled)
        AppFeature.LOCAL_MODEL_CACHE -> copy(localModelCache = enabled)
        AppFeature.LOCAL_NATIVE_METRICS -> copy(localNativeMetrics = enabled)
        AppFeature.BACKGROUND_GENERATION -> copy(backgroundGeneration = enabled)
        AppFeature.RESPONSE_NOTIFICATIONS -> copy(responseNotifications = enabled)
        AppFeature.AUTOMATIC_TITLES -> copy(automaticConversationTitles = enabled)
        AppFeature.ARCHIVE_OLDER_REPLIES -> copy(archiveOlderAssistantReplies = enabled)
        AppFeature.SHOW_REASONING -> copy(showReasoning = enabled)
        AppFeature.SMART_SUGGESTIONS -> copy(smartSuggestions = enabled)
        AppFeature.REMOTE_MCP -> copy(remoteMcpConnections = enabled)
        AppFeature.SHARED_TOOL_CALLS -> copy(sharedReadOnlyToolCalls = enabled)
        AppFeature.DEVICE_LOCATION -> copy(deviceLocationTool = enabled)
        AppFeature.MODEL_DISCOVERY -> copy(providerModelDiscovery = enabled)
        AppFeature.DIAGNOSTICS -> copy(diagnosticsCollection = enabled)
        AppFeature.OPENROUTER_BATCH -> copy(openRouterBatchProcessing = enabled)
        AppFeature.QNN_AUTO_FALLBACK -> copy(qnnAutomaticFallback = enabled)
    }

    fun isToolPluginEnabled(pluginId: String): Boolean = toolPluginStates[pluginId] ?: true

    fun withToolPluginEnabled(pluginId: String, enabled: Boolean): AppFeatureSettings =
        copy(toolPluginStates = toolPluginStates + (pluginId to enabled))

    fun withDebugMetric(metric: DebugMetric, enabled: Boolean): AppFeatureSettings = when (metric) {
        DebugMetric.TOOL_CALLS -> copy(debugShowToolCalls = enabled)
        DebugMetric.TOTAL_TOKENS -> copy(debugShowTotalTokens = enabled)
        DebugMetric.TOKEN_SPEED -> copy(debugShowTokenSpeed = enabled)
        DebugMetric.TIME_TO_FIRST_TOKEN -> copy(debugShowTimeToFirstToken = enabled)
        DebugMetric.RUNTIME -> copy(debugShowRuntime = enabled)
        DebugMetric.HARDWARE -> copy(debugShowHardware = enabled)
        DebugMetric.NETWORK -> copy(debugShowNetwork = enabled)
    }
}

@Serializable
enum class SpeculativeDecodingMode(val label: String, val enabled: Boolean?) {
    AUTO("Model default", null),
    OFF("Off", false),
    ON("On", true)
}

enum class DebugMetric(val title: String, val description: String) {
    TOOL_CALLS("Tool calls", "Show tool names, durations, status and failures."),
    TOTAL_TOKENS("Token totals", "Show prompt, completion and combined token counts."),
    TOKEN_SPEED("Token speed", "Show live and average tokens generated per second."),
    TIME_TO_FIRST_TOKEN("Time to first token", "Show latency before the first generated token."),
    RUNTIME("Runtime", "Show active provider, model, local backend and fallback state."),
    HARDWARE("Hardware", "Show memory, thermal and accelerator information."),
    NETWORK("Network", "Show provider latency and connection diagnostics.")
}

enum class AppFeature(
    val title: String,
    val description: String
) {
    SMOOTH_STREAMING("Follow streaming responses", "Keep the latest text visible until you scroll up."),
    CENTER_UNREAD("Center unread responses", "Open new replies at their beginning, in the middle of the screen."),
    RESPONSE_ANIMATION("Response fade-in", "Animate newly generated text."),
    EDGE_FADES("Conversation edge fades", "Blend conversation content beneath the header and composer."),
    MESSAGE_TIMESTAMPS("Message timestamps", "Display sent and received times."),
    QUEUED_FOLLOW_UPS("Live follow-up messages", "Add queued text to a delegated turn at its next safe model boundary."),
    PARALLEL_SEARCH("Parallel search engines", "Query selected engines concurrently."),
    DEDUPLICATE_SEARCH("Deduplicate search results", "Combine matching URLs across engines."),
    GITHUB_CONDITIONAL_READS("GitHub conditional requests", "Reuse unchanged responses with ETag validation."),
    GITHUB_BLOB_CACHE("GitHub immutable file cache", "Reuse source files by their content hash."),
    LOCAL_MODEL_CACHE("Local compiled-model cache", "Reuse compiled model resources between loads."),
    LOCAL_NATIVE_METRICS("Native runtime metrics", "Collect detailed LiteRT and QNN execution metrics."),
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
    SHOW_REASONING("Show reasoning", "Display the thinking text supplied by an AI, with an expandable panel in conversations."),
    SMART_SUGGESTIONS(
        "Smart response suggestions",
        "Generate contextual suggestion buttons below assistant responses."
    ),
    REMOTE_MCP(
        "Remote MCP connections",
        "Allow profiles to discover and call tools from remote MCP servers."
    ),
    SHARED_TOOL_CALLS(
        "Shared read-only tool calls",
        "Reuse identical safe tool results across AIs in the same conversation turn."
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

object ToolPluginId {
    const val MODEL_DELEGATION = "model_delegation"
    const val LOCAL_MEMORY = "local_memory"
    const val CURRENT_DATE = "current_date"
    const val CALCULATOR = "calculator"
    const val READ_FILES = "read_files"
    const val READ_URL = "read_url"
    const val GITHUB = "github"
    const val WEB_SEARCH = "web_search"
    const val DEVICE_LOCATION = "device_location"

    fun connection(connectionUid: String): String = "connection:$connectionUid"
}
