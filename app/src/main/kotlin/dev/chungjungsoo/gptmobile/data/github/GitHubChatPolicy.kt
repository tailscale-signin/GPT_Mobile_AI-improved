package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.context.EngineeringMode
import dev.chungjungsoo.gptmobile.data.context.EngineeringProfileSettings
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.ProfileBehaviorSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class GitHubChatProfileOverride(
    val webSearch: Boolean = true,
    val githubTools: Boolean = true,
    val engineeringMode: EngineeringMode = EngineeringMode.ULTRA_MAX,
    val maximumContext: Boolean = true,
    val engineeringMemory: GitHubEngineeringMemoryMode = GitHubEngineeringMemoryMode.HYBRID,
    val contextVariant: GitHubContextVariant = GitHubContextVariant.TASK,
    val allowCloudEngineeringMemory: Boolean = false,
    val workflowPreset: String = "heavy_work",
    val quickWorkflows: List<GitHubQuickWorkflow> = GitHubQuickWorkflow.defaults,
    val engineeringGatewayProfileUid: String? = null,
    // Independent of creativity/effort and only accepted by a capable Windows host.
    val executionBatchMode: String = "standard",
    val sampling: GitHubSamplingSettings = GitHubSamplingSettings(),
    val copilotExecutionOwner: GitHubCopilotExecutionOwner = GitHubCopilotExecutionOwner.ANDROID_DIRECT,
    val graphSharingConsent: String? = null,
    val workspaceCheckConsent: String? = null,
    val engineeringGatewayDefaultApplied: Boolean = false
)

/** Selects the task controller. GitHub executes accepted cloud work in either mode. */
@Serializable
enum class GitHubCopilotExecutionOwner { ANDROID_DIRECT, GATEWAY }

/** Persisted conversation consent; an unrelated workspace cannot inherit it. */
@Serializable
data class GitHubCopilotConversationPreference(
    val conversationIdentity: String,
    val workspace: String,
    val enabled: Boolean
)

/** Copilot cloud work is a separate paid service, off for all conversations by default. */
internal object GitHubCopilotAgentMergePolicy {
    /** Canonical native actions only. Ordinary collaboration can also enroll a Copilot bot. */
    fun requiresConsent(action: String, arguments: JsonObject): Boolean = when (action) {
        "agent_merge_enable", "agent_merge_update", "copilot_create_task" -> true
        "pr_request_reviewers" -> hasLogin(arguments, "reviewers", "copilot-pull-request-reviewer[bot]")
        "issue_add_assignees", "pr_add_assignees", "create_issue", "update_issue" ->
            // GitHub exposes the suffixed REST login and the unsuffixed GraphQL Bot login.
            hasLogin(arguments, "assignees", "copilot-swe-agent[bot]", "copilot-swe-agent")
        else -> false
    }

    private fun hasLogin(arguments: JsonObject, field: String, vararg accepted: String): Boolean =
        (arguments[field] as? JsonArray).orEmpty().any { value ->
            val login = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            login != null && accepted.any { it.equals(login, ignoreCase = true) }
        }

    fun enabled(features: AppFeatureSettings, chatId: Int?, context: GitHubConversationContext?): Boolean {
        if (chatId == null || chatId <= 0 || context == null) return false
        if (features.pluginExecution[ToolPluginId.GITHUB]?.githubCopilotEnabled != true) return false
        val saved = features.githubCopilotConversations[chatId] ?: return false
        return saved.enabled &&
            saved.conversationIdentity.isNotBlank() &&
            saved.conversationIdentity == features.conversationIdentities[chatId] &&
            saved.workspace == GitHubChatDecisions.workspaceKey(context) &&
            features.conversationGitHub[chatId]?.sameWorkspace(context) == true
    }

    fun allowedActions(features: AppFeatureSettings): List<String> {
        val config = features.pluginExecution[ToolPluginId.GITHUB] ?: return emptyList()
        if (!config.githubCopilotEnabled) return emptyList()
        return buildList {
            if (config.githubCopilotFixCi) add("fix_ci")
            if (config.githubCopilotResolveConflicts) add("resolve_merge_conflicts")
        }
    }
}

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
        // GitHub work cannot override an explicit chat reasoning setting or force
        // an unsupported model into reasoning mode. Null delegates to capability negotiation.
        return request.copy(
            allowAllByDefault = false,
            enabledToolIds = (request.enabledToolIds - controlled) + enabled,
            disabledToolIds = (request.disabledToolIds - controlled) + (controlled - enabled),
            maxTools = maxOf(request.maxTools ?: 0, 64),
            maxToolCalls = minOf(request.maxToolCalls ?: Int.MAX_VALUE, callLimit(config.engineeringMode)),
            reasoning = request.reasoning,
            delegation = null,
            research = null,
            combined = null
        )
    }

    fun delegation(original: dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings, context: GitHubConversationContext?): dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings =
        if (context == null) original else original.copy(enabled = false, orchestrationEnabled = false, reviewerEnabled = false, automaticResearch = false, researchEnabled = false, fallbackToAnotherProfile = false, processingOwnership = 100, approvedWorkerUids = emptyList())
}
