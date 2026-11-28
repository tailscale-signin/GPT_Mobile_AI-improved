package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCopilotDefaultsTest {
    @Test fun legacyOffMigratesOnceWithoutChangingRepairAllowancesOrBoundOptOuts() {
        val legacy = Json.decodeFromString<AppFeatureSettings>(
            """{"pluginExecution":{"github":{"githubCopilotEnabled":false,"githubCopilotFixCi":false}},"githubCopilotConversations":{"42":{"conversationIdentity":"room","workspace":"exact-workspace","enabled":false}}}"""
        )
        assertEquals(0, legacy.githubCopilotDefaultsVersion)
        val migrated = GitHubCopilotDefaults.migrate(legacy)
        assertEquals(1, migrated.githubCopilotDefaultsVersion)
        assertTrue(migrated.pluginExecution.getValue(ToolPluginId.GITHUB).githubCopilotEnabled)
        assertFalse(migrated.pluginExecution.getValue(ToolPluginId.GITHUB).githubCopilotFixCi)
        assertEquals(legacy.githubCopilotConversations, migrated.githubCopilotConversations)
        assertEquals(legacy.githubProfileOverrides, migrated.githubProfileOverrides)
        assertEquals(legacy.toolPluginStates, migrated.toolPluginStates)
        val explicitOff = migrated.copy(pluginExecution = migrated.pluginExecution + (ToolPluginId.GITHUB to PluginExecutionSettings(githubCopilotEnabled = false)))
        assertSame(explicitOff, GitHubCopilotDefaults.migrate(explicitOff))
        val restarted = Json.decodeFromString<AppFeatureSettings>(Json.encodeToString(explicitOff))
        assertFalse(GitHubCopilotDefaults.migrate(restarted).pluginExecution.getValue(ToolPluginId.GITHUB).githubCopilotEnabled)
    }

    @Test fun cleanDefaultsEnableAvailabilityWithoutCreatingTasksConnectionsOrConversationAuthority() {
        val fresh = GitHubCopilotDefaults.migrate(AppFeatureSettings())
        assertTrue(GitHubCopilotAgentMergePolicy.pluginEnabled(fresh))
        assertTrue(fresh.githubCopilotConversations.isEmpty())
        assertTrue(fresh.conversationGitHub.isEmpty())
        assertTrue(fresh.conversationIdentities.isEmpty())
        assertTrue(fresh.githubProfileOverrides.isEmpty())
        assertFalse(GitHubCopilotAgentMergePolicy.enabled(fresh, 42, null))
    }

    @Test fun sharedPreferenceSetterPersistsOffAndRejectsAChangedConversationBinding() {
        val context = GitHubConversationContext("connection", "owner", "repo", "main", "a".repeat(40), "profile")
        val current = AppFeatureSettings(conversationGitHub = mapOf(42 to context), conversationIdentities = mapOf(42 to "room"))
        val disabled = GitHubCopilotAgentMergePolicy.withPreference(current, 42, context, "room", false)
        assertEquals(false, disabled.githubCopilotConversations.getValue(42).enabled)
        assertFalse(GitHubCopilotAgentMergePolicy.enabled(disabled, 42, context))
        assertSame(disabled, GitHubCopilotAgentMergePolicy.withPreference(disabled, 42, context, "previous-room", true))
        assertSame(disabled, GitHubCopilotAgentMergePolicy.withPreference(disabled, 42, context.copy(repo = "other"), "room", true))
        val restored = Json.decodeFromString<AppFeatureSettings>(Json.encodeToString(disabled))
        assertFalse(GitHubCopilotAgentMergePolicy.enabled(restored, 42, context))
        val enabled = GitHubCopilotAgentMergePolicy.withPreference(restored, 42, context, "room", true)
        assertTrue(GitHubCopilotAgentMergePolicy.enabled(enabled, 42, context))
    }
}
