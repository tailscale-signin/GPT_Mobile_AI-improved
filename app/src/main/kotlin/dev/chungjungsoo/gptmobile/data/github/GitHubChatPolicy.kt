package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.context.EngineeringMode
import dev.chungjungsoo.gptmobile.data.context.EngineeringProfileSettings
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import dev.chungjungsoo.gptmobile.data.model.ProfileBehaviorSettings
import dev.chungjungsoo.gptmobile.data.model.ResearchContentScope
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.model.delegationFor
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
    val engineeringGatewayDefaultApplied: Boolean = false,
    val intelligenceLevel: GitHubIntelligenceLevel = GitHubIntelligenceLevel.INTELLIGENT,
    val recoveryConsultConsent: String? = null,
    val engineeringTeamConsent: String? = null,
    val recoveryExecutionConsent: String? = null,
    val primaryPatchReviewConsent: String? = null,
    val nextActionPolicy: GitHubNextActionMode = GitHubNextActionMode.ACTIVE,
    val evidenceElaboration: Boolean = true
) {
    companion object {
        // Persisted overrides may omit fields equal to the original defaults. Use
        // this policy only when the selected profile has no saved override.
        val newProfile = GitHubChatProfileOverride(
            engineeringMode = EngineeringMode.ADAPTIVE,
            maximumContext = false,
            quickWorkflows = GitHubQuickWorkflow.defaults.filter { it.id in setOf("review", "ci", "status") }
        )
    }
}

/** Rollout controls only the selection of a next action within an admitted GitHub request. */
@Serializable
enum class GitHubNextActionMode(val title: String, val description: String) {
    LEGACY("Original selection", "Use the original requirement order."),
    SHADOW("Compare decisions", "Record the proposed next action while the original selection runs."),
    ACTIVE("Evidence-guided selection", "Choose the next permitted action from the evidence each open requirement needs.")
}

/** Selects the task controller. GitHub executes accepted cloud work in either mode. */
@Serializable
enum class GitHubCopilotExecutionOwner { ANDROID_DIRECT, GATEWAY }

/** Persisted conversation preference; an unrelated workspace cannot inherit it. */
@Serializable
data class GitHubCopilotConversationPreference(
    val conversationIdentity: String,
    val workspace: String,
    val enabled: Boolean
)

/** Default availability never grants workspace access or starts separately billed cloud work. */
internal object GitHubCopilotAgentMergePolicy {
    fun pluginEnabled(features: AppFeatureSettings): Boolean =
        (features.pluginExecution[ToolPluginId.GITHUB] ?: PluginExecutionSettings()).githubCopilotEnabled

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
        if (!pluginEnabled(features)) return false
        val identity = features.conversationIdentities[chatId]?.takeIf(String::isNotBlank) ?: return false
        if (features.conversationGitHub[chatId]?.sameWorkspace(context) != true) return false
        val saved = features.githubCopilotConversations[chatId] ?: return true
        return saved.enabled &&
            saved.conversationIdentity == identity &&
            saved.workspace == GitHubChatDecisions.workspaceKey(context)
    }

    fun withPreference(features: AppFeatureSettings, chatId: Int, context: GitHubConversationContext, identity: String, enabled: Boolean): AppFeatureSettings {
        if (chatId <= 0 ||
            identity.isBlank() ||
            features.conversationIdentities[chatId] != identity ||
            features.conversationGitHub[chatId]?.sameWorkspace(context) != true ||
            enabled &&
            !pluginEnabled(features)
        ) {
            return features
        }
        return features.copy(
            githubCopilotConversations = features.githubCopilotConversations +
                (chatId to GitHubCopilotConversationPreference(identity, GitHubChatDecisions.workspaceKey(context), enabled))
        )
    }

    fun allowedActions(features: AppFeatureSettings): List<String> {
        val config = features.pluginExecution[ToolPluginId.GITHUB] ?: PluginExecutionSettings()
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
        if (context?.mainProfileUid == uid) features.githubProfileOverrides[uid] ?: GitHubChatProfileOverride.newProfile else null

    fun platform(profile: PlatformV2, features: AppFeatureSettings, context: GitHubConversationContext?): PlatformV2 {
        val config = settings(features, profile.uid, context) ?: return profile
        return profile.copy(
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
        val preferences = mapOf(ToolPluginId.GITHUB to config.githubTools, ToolPluginId.connection(workspace.connectionUid) to config.githubTools, ToolPluginId.WEB_SEARCH to config.webSearch, ToolPluginId.READ_URL to config.webSearch)
        // A workspace preference can restrict a capability; it cannot undo a
        // plugin or profile opt-out. General chat capabilities keep their grants.
        val states = preferences.mapValues { (id, enabled) -> enabled && original.isToolPluginEnabled(id) }
        val behavior = original.profileBehavior[uid] ?: ProfileBehaviorSettings()
        val selected = preferences.mapValues { (id, enabled) -> enabled && original.isToolPluginSelected(uid, id) }
        val budget = original.tokenBudget.copy(
            contextTokens = if (config.maximumContext) 1_048_576 else original.tokenBudget.contextTokens,
            profileContextCeilings = if (config.maximumContext) original.tokenBudget.profileContextCeilings - uid else original.tokenBudget.profileContextCeilings,
            // Context capacity is not an output request. Keep explicit caps and let
            // request admission reserve output alongside the prompt and tool receipts.
            outputTokens = original.tokenBudget.outputTokens,
            totalRunTokens = if (config.engineeringMode == EngineeringMode.ULTRA_MAX) 2_097_152 else original.tokenBudget.totalRunTokens,
            generationTimeoutMinutes = when (config.engineeringMode) {
                EngineeringMode.ADAPTIVE -> original.tokenBudget.generationTimeoutMinutes
                EngineeringMode.ULTRA_MAX -> 1440
                else -> 240
            },
            responseCharacterLimit = if (config.engineeringMode == EngineeringMode.ADAPTIVE) original.tokenBudget.responseCharacterLimit else maxOf(original.tokenBudget.responseCharacterLimit, 1_048_576),
            repetitionGuardEnabled = true,
            adaptiveToolCatalog = true
        )
        return original.copy(
            localAssist = false,
            tokenBudget = budget,
            toolPluginStates = original.toolPluginStates + states,
            profileBehavior = original.profileBehavior + (uid to behavior.copy(toolPluginStates = behavior.toolPluginStates + selected)),
            engineeringProfiles = original.engineeringProfiles + (uid to EngineeringProfileSettings(config.engineeringMode, budget.contextTokens, budget.outputTokens.takeIf { it > 0 } ?: 32_768, 32_768, githubOnly = false))
        )
    }

    fun tools(original: ChatMcpToolConfig?, features: AppFeatureSettings, uid: String, requestText: String = ""): ChatMcpToolConfig? {
        val config = settings(features, uid, original?.github) ?: return original
        val request = requireNotNull(original)
        val connection = requireNotNull(request.github).connectionUid
        val delegationAllowed = config.githubTools &&
            !request.allToolsDisabled &&
            request.delegation?.enabled != false &&
            connection !in request.disabledToolIds &&
            "delegate_to_model" !in request.disabledToolIds
        val recovery = delegationAllowed &&
            config.intelligenceLevel == GitHubIntelligenceLevel.MAXIMUM_INTELLIGENCE &&
            config.recoveryExecutionConsent != null
        val patchReview = delegationAllowed &&
            config.intelligenceLevel in GitHubPrimaryPatchReview.levels &&
            config.primaryPatchReviewConsent != null
        val available = buildSet {
            add("github_decide")
            if (delegationAllowed && config.intelligenceLevel == GitHubIntelligenceLevel.MAXIMUM_INTELLIGENCE && config.engineeringTeamConsent != null) {
                add("delegate_to_model")
            }
            if (config.githubTools && features.isToolPluginEnabledForProfile(uid, ToolPluginId.GITHUB) && features.isToolPluginEnabledForProfile(uid, ToolPluginId.connection(connection))) add(connection)
            if (config.webSearch && features.isToolPluginEnabledForProfile(uid, ToolPluginId.WEB_SEARCH)) add("web_search")
            if (config.webSearch && features.isToolPluginEnabledForProfile(uid, ToolPluginId.READ_URL)) add("read_url")
        }
        val enabled = available.filterTo(mutableSetOf()) { request.isToolEnabled(listOf(it)) }
        val controlled = setOf(connection, "github_decide", "web_search", "read_url", "delegate_to_model", "local_assist_plan")
        // GitHub work cannot override an explicit chat reasoning setting or force
        // an unsupported model into reasoning mode. Null delegates to capability negotiation.
        return request.copy(
            enabledToolIds = (request.enabledToolIds - controlled) + enabled,
            disabledToolIds = (request.disabledToolIds - controlled) + (controlled - enabled),
            maxTools = request.maxTools ?: 64,
            maxToolCalls = minOf(request.maxToolCalls ?: Int.MAX_VALUE, callLimit(config.engineeringMode)),
            reasoning = request.reasoning,
            delegation = request.delegation.takeIf { recovery || patchReview || "delegate_to_model" in enabled },
            githubPatchReviewAllowed = patchReview,
            githubRecoveryExecutionAllowed = recovery,
            research = researchSelection(request, features, uid, config, requestText),
            combined = null
        )
    }

    private val deepResearchRequest = Regex("(?i)^(?:please\\s+)?(?:(?:can|could|would) you\\s+)?(?:(?:do|perform|conduct|run|start)\\s+(?:a\\s+)?)?(?:deep|in[- ]depth|comprehensive)\\s+(?:search|research)\\b")

    /** A sent research request uses already enabled web research, never academic consent or another profile. */
    private fun researchSelection(request: ChatMcpToolConfig, features: AppFeatureSettings, uid: String, config: GitHubChatProfileOverride, text: String): MultiSearchResearchSelection? {
        if (!config.webSearch) return request.research?.copy(researchToggle = false)
        request.research?.let { return it } // Includes explicit OFF and the user's chosen depth/scope.
        val configured = features.delegationFor(uid).deepResearch
        if (!deepResearchRequest.containsMatchIn(text.trim()) ||
            !configured.enabled ||
            configured.effort <= 0 ||
            !features.researchFeature.enabled ||
            !features.isToolPluginEnabledForProfile(uid, ToolPluginId.DEEP_RESEARCH) ||
            !features.isToolPluginEnabledForProfile(uid, ToolPluginId.WEB_SEARCH) ||
            !features.isToolPluginEnabledForProfile(uid, ToolPluginId.READ_URL) ||
            "deep_research" in request.disabledToolIds ||
            !request.isToolEnabled(listOf("web_search")) ||
            !request.isToolEnabled(listOf("read_url"))
        ) {
            return null
        }
        return MultiSearchResearchSelection(researchToggle = true, depth = configured.effort.coerceIn(1, 100), scope = ResearchContentScope.WEB_ONLY)
    }

    /** GitHub's bound main profile owns research as well as implementation. */
    fun researchProfiles(primary: PlatformV2, profiles: List<PlatformV2>, context: GitHubConversationContext?): List<PlatformV2> =
        if (context == null) profiles else profiles.filter { it.uid == context.mainProfileUid && it.uid == primary.uid }

    fun delegation(original: dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings, context: GitHubConversationContext?, team: GitHubEngineeringTeam? = null): dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings =
        if (context == null) original else team?.restrict(original) ?: original.copy(enabled = false, orchestrationEnabled = false, reviewerEnabled = false, automaticResearch = false, researchEnabled = false, fallbackToAnotherProfile = false, processingOwnership = 100, approvedWorkerUids = emptyList())
}
