package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import dev.chungjungsoo.gptmobile.data.context.ReasoningPolicy
import dev.chungjungsoo.gptmobile.data.context.ReasoningTemplateControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReasoningPolicyTest {
    @org.junit.Test fun extractionDisablesSavedAndMaximumReasoningWithoutChangingPrimaryEngineering() {
        val original = RequestConstraints(maxOutputTokens = 8192, maximizeReasoning = true, allowReasoning = true)
        val extraction = original.withoutTools().forRole("github-claim-extraction")
        org.junit.Assert.assertFalse(extraction.allowReasoning)
        org.junit.Assert.assertFalse(extraction.maximizeReasoning)
        org.junit.Assert.assertFalse(extraction.allowTools)
        org.junit.Assert.assertEquals(8192, extraction.maxOutputTokens)
        org.junit.Assert.assertEquals("github-claim-extraction", extraction.requestRole)
        org.junit.Assert.assertTrue(original.forRole("primary").maximizeReasoning)
        org.junit.Assert.assertFalse(original.forRole("github-review-verdict").maximizeReasoning)
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
