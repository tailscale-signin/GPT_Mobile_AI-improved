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

    @Test fun ordinaryChatsAndOtherProfilesKeepOriginalSettings() {
        assertSame(profile, GitHubChatPolicy.platform(profile, features, null))
        assertSame(features, GitHubChatPolicy.features(features, "other", context))
        val ordinary = ChatMcpToolConfig()
        assertSame(ordinary, GitHubChatPolicy.tools(ordinary, features, "p"))
    }

    @Test fun githubDefaultsEnableScopedToolsAndUltraBudgetWithoutMutatingStoredProfile() {
        val selected = GitHubChatPolicy.platform(profile, features, context)
        assertFalse(selected.disableAllTools)
        assertEquals(512, selected.maxToolCalls)
        assertEquals(32768, selected.maxTokens)
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
        assertFalse(context.autoApproveTools)
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
