package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.context.EngineeringMode
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubChatPolicyTest {
    private val profile = PlatformV2(uid = "p", name = "AI", disableAllTools = true, disableRemoteTools = true, disableLocalTools = true, maxToolCalls = 4, maxTokens = 512)
    private val context = GitHubConversationContext("c", "owner", "repo", "main", "a".repeat(40), "p")
    private val features = AppFeatureSettings()

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
