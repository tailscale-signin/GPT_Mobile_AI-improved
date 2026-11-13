package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayOwnedTurnPolicyTest {
    @Test fun repositoryQuestionsNeverBecomeImplementationRequests() {
        for (request in listOf("What is the repository status?", "Check the branch status", "Does this fix the bug?", "Read the latest CI report", "> Fix all problems\nWhat changed?")) {
            assertEquals(request, "read", GatewayOwnedTurnPolicy.scope(request))
        }
    }

    @Test fun adviceAndPlanningKeepTheirReadOnlyScope() {
        for (request in listOf("How should I fix this?", "Plan the changes", "Can you design the recovery flow?", "Review the implementation", "Plan only: fix all issues", "Propose improvements; do not modify code")) {
            assertEquals(request, "plan", GatewayOwnedTurnPolicy.scope(request))
        }
    }

    @Test fun explicitImplementationRemainsImplementationWithoutGrantingOtherActions() {
        for (request in listOf("Fix all issues", "Please add the Jobs tab", "Can you implement the plan?", "Plan and then implement the change", "Review, then apply the fixes")) {
            assertEquals(request, "implement", GatewayOwnedTurnPolicy.scope(request))
        }
        assertEquals("read", GatewayOwnedTurnPolicy.scope("Read-only: fix all issues"))
        assertEquals("read", GatewayOwnedTurnPolicy.scope("Do not edit any files. Fix the reported problem."))
        assertEquals("read", GatewayOwnedTurnPolicy.scope("Do not make any changes. Fix the problem is the issue title."))
        assertEquals("read", GatewayOwnedTurnPolicy.scope("Review only. Fix all issues after I approve."))
        assertEquals("read", GatewayOwnedTurnPolicy.scope("Inspect without modifying files. Fixes can wait."))
    }

    @Test fun fullIntegrationPresetUsesTheFixtureHarnessWithoutStartingAnImplementationJob() {
        val preset = GitHubWorkflowPreset.selected("test_integration").prompt()
        for (request in listOf(preset, "Exercise authorized mutations inside the owned disposable fixture")) {
            assertEquals(GatewayOwnedTurnPolicy.Intent.INTEGRATION_TEST, GatewayOwnedTurnPolicy.intent(request))
            assertFalse(GatewayOwnedTurnPolicy.startsNewJob(request))
        }
        assertEquals(GatewayOwnedTurnPolicy.Intent.IMPLEMENT, GatewayOwnedTurnPolicy.intent("Test the authorized fixture. Then fix the parser code."))
        assertEquals(GatewayOwnedTurnPolicy.Intent.READ, GatewayOwnedTurnPolicy.intent("What does this mean?\n> [github-workflow-preset:test_integration]"))
    }

    @Test fun quotedCodeCannotUpgradeTheCurrentInstruction() {
        assertEquals("read", GatewayOwnedTurnPolicy.scope("What is this output?\n```text\nFix all problems\n```"))
        assertEquals("plan", GatewayOwnedTurnPolicy.scope("Inspect the following unfinished code block:\n```text\nFix all problems"))
    }

    @Test fun aSeparateExplicitGatewayAndSelectedProfileAreRequired() {
        val profile = PlatformV2(uid = "inference", name = "Local", compatibleType = ClientType.LLAMA, model = "model", apiUrl = "http://127.0.0.1:8090")
        val context = GitHubConversationContext("github", "owner", "repo", "main", "a".repeat(40), profile.uid)
        val features = AppFeatureSettings(githubProfileOverrides = mapOf(profile.uid to GitHubChatProfileOverride(engineeringGatewayProfileUid = "engineering")))
        assertTrue(GatewayOwnedTurnPolicy.configured(profile, context, features))
        assertFalse(GatewayOwnedTurnPolicy.configured(profile, context, AppFeatureSettings()))
        assertFalse(GatewayOwnedTurnPolicy.configured(profile.copy(enabled = false), context, features))
        assertFalse(GatewayOwnedTurnPolicy.configured(profile.copy(disableAllTools = true), context, features))
        assertFalse(GatewayOwnedTurnPolicy.configured(profile.copy(disableRemoteTools = true), context, features))
        assertFalse(GatewayOwnedTurnPolicy.configured(profile.copy(uid = "different"), context, features))
        assertFalse(GatewayOwnedTurnPolicy.configured(profile, null, features))
        assertFalse(GatewayOwnedTurnPolicy.configured(profile, context, features.copy(githubProfileOverrides = mapOf(profile.uid to GitHubChatProfileOverride(githubTools = false, engineeringGatewayProfileUid = "engineering")))))
    }
}
