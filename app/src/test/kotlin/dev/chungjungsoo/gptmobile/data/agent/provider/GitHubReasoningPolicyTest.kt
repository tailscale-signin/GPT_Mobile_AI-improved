package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import dev.chungjungsoo.gptmobile.data.context.ReasoningPolicy
import dev.chungjungsoo.gptmobile.data.context.ReasoningTemplateControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReasoningPolicyTest {
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
