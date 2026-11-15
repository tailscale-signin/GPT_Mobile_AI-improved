package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import dev.chungjungsoo.gptmobile.data.context.ReasoningPolicy
import dev.chungjungsoo.gptmobile.data.context.ReasoningTemplateControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReasoningPolicyTest {
    @Test fun reviewCompatibilityPreflightHasABoundedVisibleOutputAllowance() {
        val primary = RequestConstraints(maxOutputTokens = 8192, allowReasoning = true, maximizeReasoning = true)
        val preflight = primary.forRole("github-review-preflight")
        assertEquals(2048, preflight.maxOutputTokens)
        assertTrue(preflight.allowReasoning)
        assertFalse(preflight.maximizeReasoning)
        assertEquals(128, primary.copy(maxOutputTokens = 128).forRole("github-review-preflight").maxOutputTokens)
        val retry = primary.forRole("github-review-preflight-output-retry")
        assertEquals(4096, retry.maxOutputTokens)
        assertTrue(retry.allowReasoning)
        assertFalse(retry.maximizeReasoning)
        assertEquals(128, primary.copy(maxOutputTokens = 128).forRole("github-review-preflight-output-retry").maxOutputTokens)
        assertEquals(1024, primary.copy(maxOutputTokens = 1024).forRole("github-review-preflight-output-retry").maxOutputTokens)
        assertEquals(8192, primary.forRole("primary").maxOutputTokens)
    }

    @Test fun explicitReasoningModesTakePriorityOverInternalMaximumPreference() {
        val off = ReasoningPolicy.forRequest(ReasoningMode.OFF, allowed = true, maximize = true, helper = false, control = ReasoningTemplateControl.ENABLE_THINKING)
        assertEquals(ReasoningMode.OFF, off.requested)
        assertEquals(ReasoningMode.OFF, off.effective)
        val auto = ReasoningPolicy.forRequest(ReasoningMode.AUTO, allowed = true, maximize = false, helper = true, control = null)
        assertEquals(ReasoningMode.AUTO, auto.effective)
        assertTrue(runCatching { ReasoningPolicy.forRequest(ReasoningMode.OFF, true, true, true, null) }.isFailure)
    }

    @Test fun optionalHelperPreferenceDoesNotRequireAnUnverifiedReasoningToggle() {
        val auto = ReasoningPolicy.boundedHelper(null)
        assertEquals(ReasoningMode.AUTO, auto.effective)
        assertNull(auto.transmittedControl)
        val off = ReasoningPolicy.boundedHelper(ReasoningTemplateControl.ENABLE_THINKING)
        assertEquals(ReasoningMode.OFF, off.effective)
        assertTrue(off.templateProbeVerified)
        // Explicit user controls keep their strict admission policy.
        assertTrue(runCatching { ReasoningPolicy.resolve(ReasoningMode.OFF, true, null) }.isFailure)
    }

    @org.junit.Test fun extractionPreservesSavedReasoningWithoutMaximumPreference() {
        val original = RequestConstraints(maxOutputTokens = 8192, maximizeReasoning = true, allowReasoning = true)
        val extraction = original.withoutTools().forRole("github-claim-extraction")
        org.junit.Assert.assertTrue(extraction.allowReasoning)
        org.junit.Assert.assertFalse(extraction.maximizeReasoning)
        org.junit.Assert.assertFalse(extraction.allowTools)
        org.junit.Assert.assertEquals(8192, extraction.maxOutputTokens)
        org.junit.Assert.assertEquals("github-claim-extraction", extraction.requestRole)
        org.junit.Assert.assertTrue(original.forRole("primary").maximizeReasoning)
        org.junit.Assert.assertFalse(original.forRole("github-review-verdict").maximizeReasoning)
        org.junit.Assert.assertTrue(original.forRole("github-review-verdict").allowReasoning)
        org.junit.Assert.assertTrue(original.forRole("github-review-repair").allowReasoning)
    }

    @Test fun helperRolesAndTextContinuationsRetainEachExplicitUserContract() {
        for (originalMode in ReasoningMode.entries) {
            val original = RequestConstraints(reasoningModeOverride = originalMode, maximizeReasoning = true, lockSelectedRoute = true, permissionRevision = "accepted", contextCeiling = 8192)
            for (role in listOf("answer_contract_rewrite", "github-claim-extraction", "github-progress-summary", "github-review-preflight", "github-review-preflight-output-retry", "github-review-verdict")) {
                val helper = original.forRole(role).withoutTools()
                assertTrue(helper.allowReasoning)
                assertEquals(originalMode, helper.reasoningModeOverride)
                assertFalse(helper.maximizeReasoning)
                assertEquals(originalMode, helper.forRole("primary").reasoningModeOverride)
                assertTrue(helper.forRole("primary").allowReasoning)
                assertEquals(original.permissionRevision, helper.permissionRevision)
                assertEquals(original.contextCeiling, helper.contextCeiling)
                assertTrue(helper.lockSelectedRoute)
                assertEquals(originalMode != ReasoningMode.AUTO, runCatching { ReasoningPolicy.forRequest(requireNotNull(helper.reasoningModeOverride), helper.allowReasoning, helper.maximizeReasoning, true, null) }.isFailure)
            }
            assertEquals(originalMode, original.forRole("primary").reasoningModeOverride)
        }
    }

    @Test fun primaryEngineeringControlsDoNotInheritLightweightDelegateDefaults() {
        val defaults = mapOf("X-Gateway-Performance-Profile" to "turbo", "X-Gateway-Reasoning-Effort" to "low", "X-Gateway-Intermediate-Max-Tokens" to "1024", "X-Gateway-Cache-Prompt" to "true")
        val primary = GitHubReasoningPolicy.gatewayHeaders(defaults, true, 4096)
        assertEquals("quality", primary["X-Gateway-Performance-Profile"])
        assertEquals("high", primary["X-Gateway-Reasoning-Effort"])
        assertEquals("4096", primary["X-Gateway-Intermediate-Max-Tokens"])
        assertEquals("true", primary["X-Gateway-Cache-Prompt"])
        assertEquals(defaults, GitHubReasoningPolicy.gatewayHeaders(defaults, false, 512))
    }

    @Test fun reasoningAllowanceFitsContextAndPreservesExplicitLimits() {
        assertEquals(16384, GitHubReasoningPolicy.outputBudget(Int.MAX_VALUE, 0, null))
        assertEquals(4096, GitHubReasoningPolicy.outputBudget(32768, 0, 4096))
        assertEquals(1024, GitHubReasoningPolicy.outputBudget(32768, 1024, null))
        assertEquals(2730, GitHubReasoningPolicy.outputBudget(8192, 0, null))
    }

    @Test fun effortMatchesKnownModelCapabilities() {
        assertEquals("xhigh", GitHubReasoningPolicy.openAiEffort("gpt-5.2"))
        assertEquals("high", GitHubReasoningPolicy.openAiEffort("o3"))
        assertEquals("high", GitHubReasoningPolicy.openAiEffort("gpt-oss-120b"))
        assertNull(GitHubReasoningPolicy.openAiEffort("gpt-4.1"))
        assertNull(GitHubReasoningPolicy.openAiEffort("gpt-5.2-chat-latest"))
        assertNull(GitHubReasoningPolicy.openAiEffort("unknown"))
        assertTrue(RequestConstraints(maximizeReasoning = true).withoutTools().maximizeReasoning)
    }

    @Test fun localMaximumUsesVerifiedTemplateAndUnsupportedRouteRemainsUsable() {
        val on = ReasoningPolicy.strongestSupported(true, ReasoningTemplateControl.ENABLE_THINKING)
        assertEquals(ReasoningMode.ON, on.effective)
        assertTrue(on.templateProbeVerified)
        val auto = ReasoningPolicy.strongestSupported(true, null)
        assertEquals(ReasoningMode.AUTO, auto.effective)
        assertNull(auto.transmittedControl)
        assertTrue(GitHubReasoningPolicy.manualThinkingBudget(4096) <= 4096 - 1024)
    }
}
