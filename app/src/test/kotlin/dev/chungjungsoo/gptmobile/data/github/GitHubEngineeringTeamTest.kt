package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.ProfileBehaviorSettings
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.model.delegationFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubEngineeringTeamTest {
    private val primary = PlatformV2(uid = "p", name = "Primary", model = "gpt-4.1", apiUrl = "https://api.openai.com")
    private val helper = PlatformV2(uid = "h", name = "Helper", model = "qwen/qwen3-8b", compatibleType = ClientType.OPENROUTER, apiUrl = "https://openrouter.ai/api/v1")
    private val reviewer = helper.copy(uid = "r", model = "anthropic/claude-sonnet-4")
    private val profiles = listOf(primary, helper, reviewer)
    private val workspace = GitHubConversationContext("c", "owner", "repo", "main", "a".repeat(40), primary.uid)
    private val options = GitHubChatProfileOverride(intelligenceLevel = GitHubIntelligenceLevel.MAXIMUM_INTELLIGENCE)
    private val features = AppFeatureSettings(
        delegation = ModelDelegationSettings(enabled = true, targetProfileUid = helper.uid, reviewerEnabled = true, reviewerProfileUid = reviewer.uid, allowRemoteWorkers = true, localPlatformsOnly = false),
        profileBehavior = mapOf(primary.uid to ProfileBehaviorSettings(delegationEnabled = true)),
        githubProfileOverrides = mapOf(primary.uid to options)
    ).withToolPluginEnabled(ToolPluginId.MODEL_DELEGATION, true).withProfileToolPluginEnabled(primary.uid, ToolPluginId.MODEL_DELEGATION, true)

    @Test fun patchReviewerUsesAnIndependentNamedProfileAndSeparateConsentAtBothEngineeringLevels() {
        for (level in GitHubPrimaryPatchReview.levels) {
            val deep = features.copy(githubProfileOverrides = mapOf(primary.uid to options.copy(intelligenceLevel = level, engineeringTeamConsent = "team-only")))
            val policy = deep.delegationFor(primary.uid)
            val choice = requireNotNull(GitHubPrimaryPatchReview.configured(workspace, deep, profiles, policy))
            assertEquals(reviewer, choice.profile)
            assertFalse(requireNotNull(GitHubChatPolicy.tools(ChatMcpToolConfig(github = workspace), deep, primary.uid)).githubPatchReviewAllowed)
            val allowed = deep.copy(githubProfileOverrides = mapOf(primary.uid to options.copy(intelligenceLevel = level, primaryPatchReviewConsent = choice.consent)))
            val scoped = requireNotNull(GitHubChatPolicy.tools(ChatMcpToolConfig(github = workspace), allowed, primary.uid))
            assertTrue(scoped.githubPatchReviewAllowed)
            assertFalse(scoped.isToolEnabled("delegate_to_model"))
            assertFalse(requireNotNull(GitHubChatPolicy.tools(ChatMcpToolConfig(github = workspace, disabledToolIds = setOf("delegate_to_model")), allowed, primary.uid)).githubPatchReviewAllowed)
            assertNull(GitHubPrimaryPatchReview.configured(workspace, deep, profiles, policy.copy(reviewerProfileUid = primary.uid)))
            assertNull(GitHubPrimaryPatchReview.configured(workspace, deep, profiles, policy.copy(reviewerEnabled = false)))
            assertFalse(choice.consent == GitHubPrimaryPatchReview.configured(workspace.copy(repo = "changed"), deep, profiles, policy)?.consent)
        }
    }

    @Test fun savedChatCanDisableAProfileTeam() {
        val local = features.copy(conversationDelegation = mapOf(7 to dev.chungjungsoo.gptmobile.data.model.ConversationDelegationSettings(enabled = false)))
        val policy = GitHubEngineeringTeam.policy(local, workspace, 7)
        assertFalse(policy.enabled)
        assertNull(GitHubEngineeringTeam.configured(workspace, profiles, local, policy))
    }

    @Test fun engineeringTeamUsesOnlyTheAcceptedRolesAndWorkspace() {
        val candidate = requireNotNull(GitHubEngineeringTeam.configured(workspace, profiles, features))
        val allowed = features.copy(githubProfileOverrides = mapOf(primary.uid to options.copy(engineeringTeamConsent = candidate.consent)))
        val policy = allowed.delegationFor(primary.uid)
        assertNull(GitHubEngineeringTeam.admitted(workspace, profiles, features, policy))
        val team = GitHubEngineeringTeam.admitted(workspace, profiles, allowed, policy)
        assertNotNull(team)
        val scoped = GitHubChatPolicy.delegation(policy, workspace, team)
        assertEquals(helper.uid, scoped.targetProfileUid)
        assertEquals(listOf(helper.uid), scoped.approvedWorkerUids)
        assertEquals(reviewer.uid, scoped.reviewerProfileUid)
        assertFalse(scoped.fallbackToAnotherProfile)
        assertTrue(requireNotNull(GitHubChatPolicy.tools(ChatMcpToolConfig(github = workspace), allowed, primary.uid)).isToolEnabled("delegate_to_model"))
        assertFalse(requireNotNull(GitHubChatPolicy.tools(ChatMcpToolConfig(github = workspace, disabledToolIds = setOf("delegate_to_model")), allowed, primary.uid)).isToolEnabled("delegate_to_model"))
        assertNull(GitHubEngineeringTeam.admitted(workspace.copy(repo = "other"), profiles, allowed, policy))
        assertNull(GitHubEngineeringTeam.admitted(workspace, listOf(primary, helper, reviewer.copy(apiUrl = "https://other.invalid")), allowed, policy))
        assertNull(GitHubEngineeringTeam.admitted(workspace, profiles, allowed, policy.copy(enabled = false)))
        assertNull(GitHubEngineeringTeam.admitted(workspace, profiles, allowed.withToolPluginEnabled(ToolPluginId.MODEL_DELEGATION, false), policy))
    }
}
