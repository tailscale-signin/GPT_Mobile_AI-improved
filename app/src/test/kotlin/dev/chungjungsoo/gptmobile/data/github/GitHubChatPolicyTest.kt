package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.context.EngineeringMode
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubChatPolicyTest {
    private val profile = PlatformV2(uid = "p", name = "AI", disableAllTools = true, disableRemoteTools = true, disableLocalTools = true, maxToolCalls = 4, maxTokens = 512)
    private val context = GitHubConversationContext("c", "owner", "repo", "main", "a".repeat(40), "p")
    private val features = AppFeatureSettings()

    @Test fun legacyProfileDecodesIntelligenceDefaultsAndSettingsRoundTripIndependently() {
        val defaults = Json.decodeFromString<GitHubChatProfileOverride>("{}")
        assertEquals(GitHubNextActionMode.ACTIVE, defaults.nextActionPolicy)
        assertTrue(defaults.evidenceElaboration)
        GitHubNextActionMode.entries.forEach { mode ->
            for (elaboration in listOf(true, false)) {
                val saved = defaults.copy(nextActionPolicy = mode, evidenceElaboration = elaboration)
                val restored = Json.decodeFromString<GitHubChatProfileOverride>(Json.encodeToString(saved))
                val configured = features.copy(githubProfileOverrides = mapOf("p" to restored))
                assertEquals(saved, GitHubChatPolicy.settings(configured, "p", context))
                assertEquals(null, GitHubChatPolicy.settings(configured, "p", null))
            }
        }
    }

    @Test fun savedProfilesDefaultToDirectCopilotAndKeepGatewaySeparateFromInference() {
        val decoded = kotlinx.serialization.json.Json.decodeFromString<GitHubChatProfileOverride>("{}")
        assertEquals(GitHubCopilotExecutionOwner.ANDROID_DIRECT, decoded.copilotExecutionOwner)
        assertEquals(null, decoded.engineeringGatewayProfileUid)
        val selected = decoded.copy(copilotExecutionOwner = GitHubCopilotExecutionOwner.GATEWAY, engineeringGatewayProfileUid = "host")
        val configured = features.copy(githubProfileOverrides = mapOf("p" to selected))
        val unchanged = GitHubChatPolicy.platform(profile, configured, context)
        assertEquals(profile.uid, unchanged.uid)
        assertEquals(profile.model, unchanged.model)
        assertEquals(profile.apiUrl, unchanged.apiUrl)
        assertFalse(GitHubCopilotAgentMergePolicy.enabled(configured, 42, context))
    }

    @Test fun newlyPairedProfileUsesUltraNativeCeilingWhileSavedExplicitCapsRemainAuthoritative() {
        val fresh = dev.chungjungsoo.gptmobile.data.pairing.PairedServer(1, "Computer", "LLAMA", "http://192.168.1.4:8080/v1", "selected", 1200).profile(1000).copy(uid = "p")
        assertEquals(Int.MAX_VALUE, fresh.maxToolCalls)
        assertEquals(512, GitHubChatPolicy.platform(fresh, features, context).maxToolCalls)
        assertFalse(GitHubChatPolicy.platform(fresh, features, context).enabled)
        assertEquals(100, GitHubChatPolicy.platform(fresh.copy(maxToolCalls = 100), features, context).maxToolCalls)
        assertEquals(50, GitHubChatPolicy.platform(fresh.copy(maxToolCalls = 50), features, context).maxToolCalls)
        val quick = features.copy(githubProfileOverrides = mapOf("p" to GitHubChatProfileOverride(engineeringMode = EngineeringMode.QUICK)))
        assertEquals(32, GitHubChatPolicy.platform(fresh, quick, context).maxToolCalls)
    }

    @Test fun githubCannotInheritAnotherProfileThroughHelpersResearchOrCombinedMode() {
        val configured = features.copy(localAssist = true)
        assertFalse(GitHubChatPolicy.features(configured, "p", context).localAssist)
        assertTrue(configured.localAssist)
        val workers = dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings(enabled = true, reviewerEnabled = true, fallbackToAnotherProfile = true, approvedWorkerUids = listOf("different-profile"))
        val policy = GitHubChatPolicy.delegation(workers, context)
        assertFalse(policy.enabled)
        assertFalse(policy.reviewerEnabled)
        assertFalse(policy.fallbackToAnotherProfile)
        assertEquals(emptyList<String>(), policy.approvedWorkerUids)
        assertSame(workers, GitHubChatPolicy.delegation(workers, null))
        val tools = requireNotNull(GitHubChatPolicy.tools(ChatMcpToolConfig(github = context, enabledToolIds = setOf("delegate_to_model", "local_assist_plan"), research = dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection(researchToggle = true)), configured, "p"))
        assertFalse(tools.isToolEnabled("delegate_to_model"))
        assertFalse(tools.isToolEnabled("local_assist_plan"))
        assertEquals(null, tools.research)
        assertEquals(profile.uid, GitHubChatPolicy.platform(profile, configured, context).uid)
        assertEquals(profile.model, GitHubChatPolicy.platform(profile, configured, context).model)
    }

    @Test fun githubReasoningPreservesCallerSettingForProviderCapabilityNegotiation() {
        // A model that only supports reasoning OFF must not receive a forced ON
        // setting merely because GitHub tools were selected.
        val disabled = ChatMcpToolConfig(github = context, reasoning = false)
        assertEquals(false, GitHubChatPolicy.tools(disabled, features, "p")?.reasoning)

        // A null preference is resolved by the existing selected-provider
        // capability policy rather than being converted to ON here.
        val automatic = ChatMcpToolConfig(github = context, reasoning = null)
        assertEquals(null, GitHubChatPolicy.tools(automatic, features, "p")?.reasoning)
        val explicit = ChatMcpToolConfig(github = context, reasoning = true)
        assertEquals(true, GitHubChatPolicy.tools(explicit, features, "p")?.reasoning)
    }

    @Test fun copilotRequiresPluginAndExactSavedConversationConsent() {
        val savedChat = features.copy(
            conversationGitHub = mapOf(42 to context),
            conversationIdentities = mapOf(42 to "room-42")
        )
        assertFalse(GitHubCopilotAgentMergePolicy.enabled(savedChat, 42, context))
        assertFalse(GitHubCopilotAgentMergePolicy.enabled(savedChat, null, context))
        assertEquals(emptyList<String>(), GitHubCopilotAgentMergePolicy.allowedActions(savedChat))

        val plugin = dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings(githubCopilotEnabled = true)
        val permitted = savedChat.copy(pluginExecution = mapOf(ToolPluginId.GITHUB to plugin))
        assertFalse(GitHubCopilotAgentMergePolicy.enabled(permitted, 42, context))
        assertEquals(listOf("fix_ci", "resolve_merge_conflicts"), GitHubCopilotAgentMergePolicy.allowedActions(permitted))
        val opted = permitted.copy(
            githubCopilotConversations = mapOf(
                42 to GitHubCopilotConversationPreference(
                    "room-42",
                    GitHubChatDecisions.workspaceKey(context),
                    true
                )
            )
        )
        assertTrue(GitHubCopilotAgentMergePolicy.enabled(opted, 42, context))
        assertFalse(GitHubCopilotAgentMergePolicy.enabled(opted, 43, context))
        assertFalse(GitHubCopilotAgentMergePolicy.enabled(opted.copy(conversationIdentities = mapOf(42 to "new-room")), 42, context))
        assertFalse(
            GitHubCopilotAgentMergePolicy.enabled(
                opted,
                42,
                context.copy(owner = "other-owner")
            )
        )
        assertFalse(
            GitHubCopilotAgentMergePolicy.enabled(
                opted.copy(pluginExecution = mapOf(ToolPluginId.GITHUB to plugin.copy(githubCopilotEnabled = false))),
                42,
                context
            )
        )
        assertEquals(
            listOf("resolve_merge_conflicts"),
            GitHubCopilotAgentMergePolicy.allowedActions(
                permitted.copy(pluginExecution = mapOf(ToolPluginId.GITHUB to plugin.copy(githubCopilotFixCi = false)))
            )
        )
    }

    @Test fun copilotCreationAndEnrollmentRequireConsentButReadAndDisableDoNot() {
        val arguments = JsonObject(emptyMap())
        listOf("agent_merge_enable", "agent_merge_update", "copilot_create_task").forEach { action ->
            assertTrue(action, GitHubCopilotAgentMergePolicy.requiresConsent(action, arguments))
        }
        listOf("agent_merge_disable", "agent_merge_status", "copilot_task_status", "copilot_tasks").forEach { action ->
            assertFalse(action, GitHubCopilotAgentMergePolicy.requiresConsent(action, arguments))
        }
    }

    @Test fun genericCollaborationCannotBypassConsentByNamingTheOfficialCopilotBots() {
        val review = buildJsonObject {
            put("reviewers", JsonArray(listOf("human", "COPILOT-PULL-REQUEST-REVIEWER[bot]").map(::JsonPrimitive)))
        }
        assertTrue(GitHubCopilotAgentMergePolicy.requiresConsent("pr_request_reviewers", review))
        for (login in listOf("copilot-swe-agent[bot]", "Copilot-Swe-Agent")) {
            val assigned = buildJsonObject { put("assignees", JsonArray(listOf("human", login).map(::JsonPrimitive))) }
            for (action in listOf("issue_add_assignees", "pr_add_assignees", "create_issue", "update_issue")) {
                assertTrue("$action: $login", GitHubCopilotAgentMergePolicy.requiresConsent(action, assigned))
            }
        }
    }

    @Test fun removingBotsAndOrdinaryCollaborationRemainAvailableWithoutCopilotConsent() {
        val bots = buildJsonObject {
            put("reviewers", JsonArray(listOf(JsonPrimitive("copilot-pull-request-reviewer[bot]"))))
            put("assignees", JsonArray(listOf(JsonPrimitive("copilot-swe-agent[bot]"))))
        }
        listOf("pr_remove_reviewers", "issue_remove_assignees", "pr_remove_assignees", "pr_threads", "issue_context").forEach { action ->
            assertFalse(action, GitHubCopilotAgentMergePolicy.requiresConsent(action, bots))
        }
        listOf("copilot", "my-copilot", "copilot-swe-agent-helper", "copilot-pull-request-reviewer-helper").forEach { login ->
            val humans = buildJsonObject {
                put("reviewers", JsonArray(listOf(JsonPrimitive(login))))
                put("assignees", JsonArray(listOf(JsonPrimitive(login))))
                put("body", "Discuss Copilot settings")
            }
            assertFalse(GitHubCopilotAgentMergePolicy.requiresConsent("pr_request_reviewers", humans))
            assertFalse(GitHubCopilotAgentMergePolicy.requiresConsent("issue_add_assignees", humans))
        }
        val team = buildJsonObject { put("team_reviewers", bots.getValue("reviewers")) }
        assertFalse(GitHubCopilotAgentMergePolicy.requiresConsent("pr_request_reviewers", team))
        assertFalse(GitHubCopilotAgentMergePolicy.requiresConsent("update_issue", Json.parseToJsonElement("""{"assignees":[]} """).jsonObject))
        assertFalse(GitHubCopilotAgentMergePolicy.requiresConsent("issue_add_assignees", Json.parseToJsonElement("""{"assignees":[null,123,{"login":"copilot-swe-agent[bot]"}]}""").jsonObject))
    }

    @Test fun ordinaryChatsAndOtherProfilesKeepOriginalSettings() {
        assertSame(profile, GitHubChatPolicy.platform(profile, features, null))
        assertSame(features, GitHubChatPolicy.features(features, "other", context))
        val ordinary = ChatMcpToolConfig()
        assertSame(ordinary, GitHubChatPolicy.tools(ordinary, features, "p"))
    }

    @Test fun githubDefaultsEnableScopedToolsAndUltraBudgetWithoutMutatingStoredProfile() {
        val selected = GitHubChatPolicy.platform(profile, features, context)
        assertFalse(selected.disableAllTools)
        assertEquals(4, selected.maxToolCalls)
        assertEquals(512, selected.maxTokens)
        assertTrue(profile.disableAllTools)
        val effective = GitHubChatPolicy.features(features, "p", context)
        assertEquals(EngineeringMode.ULTRA_MAX, effective.engineeringProfiles.getValue("p").mode)
        assertEquals(1_048_576, effective.tokenBudget.contextTokens)
        assertEquals(1440, effective.tokenBudget.generationTimeoutMinutes)
        assertTrue(effective.isToolPluginEnabledForProfile("p", ToolPluginId.GITHUB))
        val tools = requireNotNull(GitHubChatPolicy.tools(ChatMcpToolConfig(github = context), features, "p"))
        assertTrue(tools.isToolEnabled("c"))
        assertTrue(tools.isToolEnabled("web_search"))
        assertTrue(tools.isToolEnabled("github_decide"))
        assertFalse(tools.isToolEnabled("unrelated-tool"))
        val withPersonalMemory = requireNotNull(GitHubChatPolicy.tools(ChatMcpToolConfig(github = context, enabledToolIds = setOf("memory", "memory_recall", "memory_read_document")), features, "p"))
        assertFalse(withPersonalMemory.isToolEnabled("memory"))
        assertFalse(withPersonalMemory.isToolEnabled("memory_recall"))
        assertFalse(withPersonalMemory.isToolEnabled("memory_read_document"))
        assertFalse(context.autoApproveTools)
    }

    @Test fun maximumContextLeavesAutomaticOutputAdmissibleOnLlamaFallback() {
        val automatic = profile.copy(maxTokens = null)
        val selected = GitHubChatPolicy.platform(automatic, features, context)
        val effective = GitHubChatPolicy.features(features, automatic.uid, context)
        assertEquals(null, selected.maxTokens)
        assertEquals(0, effective.tokenBudget.outputTokens)
        val capacity = 32_768
        val output = dev.chungjungsoo.gptmobile.data.agent.provider.GitHubReasoningPolicy.outputBudget(capacity, effective.tokenBudget.outputTokens, selected.maxTokens)
        val plan = dev.chungjungsoo.gptmobile.data.context.ContextBudgetService.plan(
            emptyList(),
            "Inspect repository failures",
            emptyList(),
            effective.tokenBudget.copy(contextTokens = capacity, outputTokens = output)
        )
        assertTrue(plan.promptTokens + output < capacity)
    }

    @Test fun maximumContextPreservesExplicitOutputCaps() {
        val configured = features.copy(tokenBudget = features.tokenBudget.copy(outputTokens = 1024))
        val effective = GitHubChatPolicy.features(configured, "p", context)
        assertEquals(1024, effective.tokenBudget.outputTokens)
        assertEquals(512, GitHubChatPolicy.platform(profile, configured, context).maxTokens)
    }

    @Test fun githubControlsCanDisableSearchAndRepositoryAccessAndSelectSmallerBudget() {
        val settings = features.copy(githubProfileOverrides = mapOf("p" to GitHubChatProfileOverride(webSearch = false, githubTools = false, engineeringMode = EngineeringMode.QUICK, maximumContext = false)))
        val tools = requireNotNull(GitHubChatPolicy.tools(ChatMcpToolConfig(github = context), settings, "p"))
        assertFalse(tools.isToolEnabled("web_search"))
        assertFalse(tools.isToolEnabled("c"))
        assertTrue(tools.isToolEnabled("github_decide"))
        assertEquals(32, tools.maxToolCalls)
        assertEquals(512, GitHubChatPolicy.platform(profile, settings, context).maxTokens)
    }

    @Test fun explicitConversationStopStillDisablesAllTools() {
        val tools = requireNotNull(GitHubChatPolicy.tools(ChatMcpToolConfig(github = context, allToolsDisabled = true), features, "p"))
        assertFalse(tools.isToolEnabled("c"))
        assertFalse(tools.isToolEnabled("github_decide"))
    }

    @Test fun elapsedClockSurvivesHoursAndClampsClockRollback() {
        assertEquals("01:02:03", githubElapsedTime(1000, 3_724_000))
        assertEquals("25:00:00", githubElapsedTime(0, 90_000_000))
        assertEquals("00:00:00", githubElapsedTime(1000, 500))
    }
}
