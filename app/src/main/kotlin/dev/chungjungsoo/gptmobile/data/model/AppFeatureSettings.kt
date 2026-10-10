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
    val reuseSearchRequests: Boolean = true,
    val deduplicateSearchContent: Boolean = false,
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
    val debugShowDelegationTrace: Boolean = true,
    val debugShowReviewerTrace: Boolean = true,
    val debugShowMemoryRecall: Boolean = true,
    val debugShowTokenComparison: Boolean = true,
    val openRouterBatchProcessing: Boolean = false,
    val qnnAutomaticFallback: Boolean = true,
    val localCpuThreads: Int = 0,
    val localModelCache: Boolean = true,
    val localSpeculativeDecoding: SpeculativeDecodingMode = SpeculativeDecodingMode.AUTO,
    val localNativeMetrics: Boolean = false,
    val localAssist: Boolean = false,
    val localIdleMinutes: Int = 10,
    /** Global service state. Amazon is opt-in; other built-in services preserve their defaults. */
    val pluginExecution: Map<String, PluginExecutionSettings> = emptyMap(),
    val toolPluginStates: Map<String, Boolean> = emptyMap(),
    val delegation: ModelDelegationSettings = ModelDelegationSettings(),
    val profileBehavior: Map<String, ProfileBehaviorSettings> = emptyMap(),
    val conversationReasoning: Map<Int, Boolean> = emptyMap(),
    val conversationDelegation: Map<Int, ConversationDelegationSettings> = emptyMap(),
    val spendBudget: dev.chungjungsoo.gptmobile.data.accounting.SpendBudgetSettings = dev.chungjungsoo.gptmobile.data.accounting.SpendBudgetSettings(),
    val tokenBudget: dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings = dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings()
) {
    fun withFeature(feature: AppFeature, enabled: Boolean): AppFeatureSettings = when (feature) {
        AppFeature.SMOOTH_STREAMING -> copy(smoothStreaming = enabled)
        AppFeature.CENTER_UNREAD -> copy(centerUnread = enabled)
        AppFeature.RESPONSE_ANIMATION -> copy(responseAnimation = enabled)
        AppFeature.EDGE_FADES -> copy(edgeFades = enabled)
        AppFeature.MESSAGE_TIMESTAMPS -> copy(messageTimestamps = enabled)
        AppFeature.QUEUED_FOLLOW_UPS -> copy(queuedFollowUps = enabled)
        AppFeature.PARALLEL_SEARCH -> copy(parallelSearch = true)
        AppFeature.DEDUPLICATE_SEARCH -> copy(deduplicateSearch = enabled)
        AppFeature.REUSE_SEARCH -> copy(reuseSearchRequests = enabled)
        AppFeature.DEDUPLICATE_SEARCH_CONTENT -> copy(deduplicateSearchContent = enabled)
        AppFeature.GITHUB_CONDITIONAL_READS -> copy(githubConditionalReads = enabled)
        AppFeature.GITHUB_BLOB_CACHE -> copy(githubBlobCache = enabled)
        AppFeature.LOCAL_MODEL_CACHE -> copy(localModelCache = enabled)
        AppFeature.LOCAL_NATIVE_METRICS -> copy(localNativeMetrics = enabled)
        AppFeature.LOCAL_ASSIST -> copy(localAssist = enabled)
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

    fun isToolPluginEnabled(pluginId: String): Boolean = toolPluginStates[pluginId] ?: (pluginId !in ToolPluginId.optInServices)

    fun isToolPluginSelected(profileUid: String, pluginId: String): Boolean =
        profileBehavior[profileUid]?.toolPluginStates?.get(pluginId) ?: (pluginId !in ToolPluginId.optInServices)

    /** A profile can restrict an active service, but cannot bypass a global disable. */
    fun isToolPluginEnabledForProfile(profileUid: String, pluginId: String): Boolean =
        isToolPluginEnabled(pluginId) && isToolPluginSelected(profileUid, pluginId)

    fun withProfileToolPluginEnabled(profileUid: String, pluginId: String, enabled: Boolean): AppFeatureSettings {
        val behavior = profileBehavior[profileUid] ?: ProfileBehaviorSettings()
        return copy(profileBehavior = profileBehavior + (profileUid to behavior.copy(toolPluginStates = behavior.toolPluginStates + (pluginId to enabled))))
    }

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
        DebugMetric.DELEGATION_TRACE -> copy(debugShowDelegationTrace = enabled)
        DebugMetric.REVIEWER_TRACE -> copy(debugShowReviewerTrace = enabled)
        DebugMetric.MEMORY_RECALL -> copy(debugShowMemoryRecall = enabled)
        DebugMetric.TOKEN_COMPARISON -> copy(debugShowTokenComparison = enabled)
    }
}

@Serializable
enum class SpeculativeDecodingMode(val label: String, val enabled: Boolean?) {
    AUTO("Model default", null),
    OFF("Off", false),
    ON("On", true)
}

enum class DebugMetric(val title: String, val description: String) {
    TOOL_CALLS("Tool Calls", "Show tool names, durations, status and failures."),
    TOTAL_TOKENS("Token Totals", "Show prompt, completion and combined token counts."),
    TOKEN_SPEED("Token Speed", "Show live and average tokens generated per second."),
    TIME_TO_FIRST_TOKEN("Time To First Token", "Show latency before the first generated token."),
    RUNTIME("Runtime", "Show active provider, model, local backend and fallback state."),
    HARDWARE("Hardware", "Show memory, thermal and accelerator information."),
    NETWORK("Network", "Show provider latency and connection diagnostics."),
    DELEGATION_TRACE("Delegation Trace", "Show delegated model output in green inside expanded response details."),
    REVIEWER_TRACE("Reviewer Trace", "Show reviewer output in bright yellow inside expanded response details."),
    MEMORY_RECALL("Memory Recall", "Show recalled memory references in pink inside expanded response details."),
    TOKEN_COMPARISON("Token Comparison", "Compare input, output, total tokens and share across every model request in a response.")
}

enum class AppFeature(
    val title: String,
    val description: String
) {
    SMOOTH_STREAMING("Follow Streaming Responses", "Keep the latest text visible until you scroll up."),
    CENTER_UNREAD("Center Unread Responses", "Open new replies at their beginning, in the middle of the screen."),
    RESPONSE_ANIMATION("Response Fade-In", "Animate newly generated text."),
    EDGE_FADES("Conversation Edge Fades", "Blend conversation content beneath the header and composer."),
    MESSAGE_TIMESTAMPS("Message Timestamps", "Display sent and received times."),
    QUEUED_FOLLOW_UPS("Live Follow-Up Messages", "Add queued text to a delegated turn at its next safe model boundary."),
    PARALLEL_SEARCH("Parallel Search Engines", "Query selected engines concurrently."),
    DEDUPLICATE_SEARCH("Deduplicate Search Results", "Combine matching URLs across engines."),
    REUSE_SEARCH("Reuse Identical Searches", "Reuse authorized successful evidence for up to 30 seconds."),
    DEDUPLICATE_SEARCH_CONTENT("Group Similar Coverage (Trial)", "Group long matching excerpts with corroborating titles. Expand sources to see alternate links."),
    GITHUB_CONDITIONAL_READS("GitHub Conditional Requests", "Reuse unchanged responses with ETag validation."),
    GITHUB_BLOB_CACHE("GitHub Immutable File Cache", "Reuse source files by their content hash."),
    LOCAL_MODEL_CACHE("Local Compiled-Model Cache", "Reuse compiled model resources between loads."),
    LOCAL_ASSIST("Local Assist (Trial)", "Use a warm local worker to select complete research records before remote synthesis. Read-only, bounded, and off by default."),
    LOCAL_NATIVE_METRICS("Native Runtime Metrics", "Collect detailed LiteRT and QNN execution metrics."),
    BACKGROUND_GENERATION(
        "Background Generation",
        "Keep active AI responses running when the app leaves the foreground."
    ),
    RESPONSE_NOTIFICATIONS(
        "Response Notifications",
        "Show a detailed notification when a background AI response finishes."
    ),
    AUTOMATIC_TITLES(
        "Automatic Conversation Titles",
        "Generate one accurate 4–8 word subject for a conversation, then keep it stable."
    ),
    ARCHIVE_OLDER_REPLIES(
        "Archive Older Responses",
        "Collapse assistant replies older than the latest three into expandable history."
    ),
    SHOW_REASONING("Show Reasoning", "Display the thinking text supplied by an AI, with an expandable panel in conversations."),
    SMART_SUGGESTIONS(
        "Smart Response Suggestions",
        "Generate contextual suggestion buttons below assistant responses."
    ),
    REMOTE_MCP(
        "Remote MCP Connections",
        "Allow profiles to discover and call tools from remote MCP servers."
    ),
    SHARED_TOOL_CALLS(
        "Shared Read-Only Tool Calls",
        "Reuse identical safe tool results across AIs in the same conversation turn."
    ),
    DEVICE_LOCATION(
        "Device Location Tool",
        "Allow profiles with the location tool assigned to request phone location."
    ),
    MODEL_DISCOVERY(
        "Provider Model Discovery",
        "Index models from supported remote providers for searchable profile model pickers."
    ),
    DIAGNOSTICS(
        "Diagnostics Collection",
        "Collect local performance, token, runtime and tool metrics for Debug Mode."
    ),
    OPENROUTER_BATCH(
        "OpenRouter Batch Processing",
        "Allow asynchronous OpenRouter Batch API jobs for supported workloads."
    ),
    QNN_AUTO_FALLBACK(
        "QNN Automatic Fallback",
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
    const val AMAZON_SEARCH = "amazon_search"
    const val AMAZON_FREE = "amazon_free"
    const val NEWS = "news"
    const val AIRBNB = "airbnb"
    const val GOOGLE_PLACES = "service:google"
    const val WEB_SEARCH = "web_search"
    const val DEVICE_LOCATION = "device_location"

    val optInServices = setOf(AMAZON_SEARCH, AMAZON_FREE, NEWS, AIRBNB, GOOGLE_PLACES)

    fun nativeOperation(packageId: String, operation: String): String = "native:$packageId:$operation"

    fun connection(connectionUid: String): String = "connection:$connectionUid"
}
