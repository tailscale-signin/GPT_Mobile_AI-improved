package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.context.EngineeringMode
import dev.chungjungsoo.gptmobile.data.context.EngineeringProfileSettings
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.ProfileBehaviorSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import kotlinx.serialization.Serializable

@Serializable
data class GitHubChatProfileOverride(
    val webSearch: Boolean = true,
    val githubTools: Boolean = true,
    val engineeringMode: EngineeringMode = EngineeringMode.ULTRA_MAX,
    val maximumContext: Boolean = true,
    val engineeringMemory: GitHubEngineeringMemoryMode = GitHubEngineeringMemoryMode.HYBRID,
    val allowCloudEngineeringMemory: Boolean = false,
    val workflowPreset: String = "heavy_work",
    val quickWorkflows: List<GitHubQuickWorkflow> = GitHubQuickWorkflow.defaults,
    val engineeringGatewayProfileUid: String? = null,
    // Independent of creativity/effort and only accepted by a capable Windows host.
    val executionBatchMode: String = "standard",
    val sampling: GitHubSamplingSettings = GitHubSamplingSettings()
)

/** Request-local copies: GitHub preferences never rewrite ordinary chat profiles or provider grants. */
internal object GitHubChatPolicy {
    fun settings(features: AppFeatureSettings, uid: String, context: GitHubConversationContext?): GitHubChatProfileOverride? =
        if (context?.mainProfileUid == uid) features.githubProfileOverrides[uid] ?: GitHubChatProfileOverride() else null

    fun platform(profile: PlatformV2, features: AppFeatureSettings, context: GitHubConversationContext?): PlatformV2 {
        val config = settings(features, profile.uid, context) ?: return profile
        return profile.copy(
            disableAllTools = false,
            disableRemoteTools = false,
            disableLocalTools = false,
            maxToolCalls = minOf(profile.maxToolCalls, callLimit(config.engineeringMode))
        )
    }

    fun callLimit(mode: EngineeringMode): Int = when (mode) {
        EngineeringMode.QUICK -> 32
        EngineeringMode.STANDARD, EngineeringMode.OFF -> 64
        EngineeringMode.DEEP -> 128
        EngineeringMode.ENGINEERING_MAX, EngineeringMode.ADAPTIVE -> 256
        EngineeringMode.ULTRA_MAX -> 512
    }

    fun features(original: AppFeatureSettings, uid: String, context: GitHubConversationContext?): AppFeatureSettings {
        val config = settings(original, uid, context) ?: return original
        val workspace = requireNotNull(context)
        val states = mapOf(ToolPluginId.GITHUB to config.githubTools, ToolPluginId.connection(workspace.connectionUid) to config.githubTools, ToolPluginId.WEB_SEARCH to config.webSearch, ToolPluginId.READ_URL to config.webSearch, ToolPluginId.LOCAL_MEMORY to false)
        val behavior = original.profileBehavior[uid] ?: ProfileBehaviorSettings()
        val budget = original.tokenBudget.copy(
            contextTokens = if (config.maximumContext) 1_048_576 else original.tokenBudget.contextTokens,
            profileContextCeilings = if (config.maximumContext) original.tokenBudget.profileContextCeilings - uid else original.tokenBudget.profileContextCeilings,
            // Context capacity is not an output request. Keep explicit caps and let
            // request admission reserve output alongside the prompt and tool receipts.
            outputTokens = original.tokenBudget.outputTokens,
            totalRunTokens = if (config.engineeringMode == EngineeringMode.ULTRA_MAX) 2_097_152 else original.tokenBudget.totalRunTokens,
            generationTimeoutMinutes = if (config.engineeringMode == EngineeringMode.ULTRA_MAX) 1440 else 240,
            responseCharacterLimit = maxOf(original.tokenBudget.responseCharacterLimit, 1_048_576),
            repetitionGuardEnabled = true,
            adaptiveToolCatalog = true
        )
        return original.copy(
            localAssist = false,
            tokenBudget = budget,
            toolPluginStates = original.toolPluginStates + states,
            profileBehavior = original.profileBehavior + (uid to behavior.copy(toolPluginStates = behavior.toolPluginStates + states)),
            engineeringProfiles = original.engineeringProfiles + (uid to EngineeringProfileSettings(config.engineeringMode, budget.contextTokens, budget.outputTokens.takeIf { it > 0 } ?: 32_768, 32_768, githubOnly = false))
        )
    }

    fun tools(original: ChatMcpToolConfig?, features: AppFeatureSettings, uid: String): ChatMcpToolConfig? {
        val config = settings(features, uid, original?.github) ?: return original
        val request = requireNotNull(original)
        val connection = requireNotNull(request.github).connectionUid
        val enabled = buildSet {
            add("github_decide")
            if (config.githubTools) add(connection)
            if (config.webSearch) addAll(setOf("web_search", "read_url"))
        }
        val controlled = setOf(connection, "github_decide", "web_search", "read_url", "memory", "memory_recall", "memory_capture", "memory_search", "memory_read_document", "delegate_to_model", "local_assist_plan")
        return request.copy(allowAllByDefault = false, enabledToolIds = (request.enabledToolIds - controlled) + enabled, disabledToolIds = (request.disabledToolIds - controlled) + (controlled - enabled), maxTools = maxOf(request.maxTools ?: 0, 64), maxToolCalls = minOf(request.maxToolCalls ?: Int.MAX_VALUE, callLimit(config.engineeringMode)), reasoning = true, delegation = null, research = null, combined = null)
    }

    fun delegation(original: dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings, context: GitHubConversationContext?): dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings =
        if (context == null) original else original.copy(enabled = false, orchestrationEnabled = false, reviewerEnabled = false, automaticResearch = false, researchEnabled = false, fallbackToAnotherProfile = false, processingOwnership = 100, approvedWorkerUids = emptyList())
}
