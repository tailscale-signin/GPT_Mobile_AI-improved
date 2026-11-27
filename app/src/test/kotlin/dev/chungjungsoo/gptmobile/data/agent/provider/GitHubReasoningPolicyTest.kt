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
    @Test fun automaticIntelligentRepairChangesEffortWithoutChangingPolicyOrOutputCeiling() {
        val role = dev.chungjungsoo.gptmobile.data.github.GitHubStageBudget.VISIBLE_OUTPUT_REPAIR_ROLE
        val original = RequestConstraints(
            maxOutputTokens = 4096,
            reasoningModeOverride = ReasoningMode.AUTO,
            maximizeReasoning = true,
            gitHubIntelligenceLevel = dev.chungjungsoo.gptmobile.data.github.GitHubIntelligenceLevel.INTELLIGENT,
            gitHubEffort = dev.chungjungsoo.gptmobile.data.github.GitHubReasoningEffort.HIGH
        )
        val repaired = original.forRole(role)
        assertEquals(dev.chungjungsoo.gptmobile.data.github.GitHubReasoningEffort.LOW, repaired.gitHubEffort)
        assertFalse(repaired.maximizeReasoning)
        assertEquals(ReasoningMode.AUTO, repaired.reasoningModeOverride)
        assertEquals(4096, repaired.maxOutputTokens)
        for (mode in listOf(ReasoningMode.ON, ReasoningMode.OFF)) {
            assertEquals(original.gitHubEffort, original.copy(reasoningModeOverride = mode).forRole(role).gitHubEffort)
        }
        assertEquals(original.gitHubEffort, original.copy(gitHubIntelligenceLevel = dev.chungjungsoo.gptmobile.data.github.GitHubIntelligenceLevel.DEEP_ENGINEERING).forRole(role).gitHubEffort)
        assertEquals(dev.chungjungsoo.gptmobile.data.github.GitHubReasoningEffort.MAXIMUM, original.copy(gitHubEffort = dev.chungjungsoo.gptmobile.data.github.GitHubReasoningEffort.MAXIMUM).forRole(role).gitHubEffort)
    }

    @Test fun visibleOutputRecoveryKeepsExplicitHighEffortAndTheAcceptedOutputLimit() {
        val role = dev.chungjungsoo.gptmobile.data.github.GitHubStageBudget.VISIBLE_OUTPUT_REPAIR_ROLE
        for (mode in ReasoningMode.entries) {
            val original = RequestConstraints(maxOutputTokens = 6144, reasoningModeOverride = mode, maximizeReasoning = true, lockSelectedRoute = true, permissionRevision = "accepted")
            val repair = original.forRole(role)
            assertEquals(mode, repair.reasoningModeOverride)
            assertTrue(repair.maximizeReasoning)
            assertTrue(repair.allowReasoning)
            assertEquals(6144, repair.outputLimit(16384))
            assertEquals(6144, repair.maxOutputTokens)
            assertEquals("accepted", repair.permissionRevision)
            assertTrue(repair.lockSelectedRoute)
        }
        val off = RequestConstraints(maxOutputTokens = 6144, allowReasoning = false, reasoningModeOverride = ReasoningMode.OFF).forRole(role)
        assertFalse(off.allowReasoning)
        assertFalse(off.maximizeReasoning)
        assertEquals(ReasoningMode.OFF, off.reasoningModeOverride)
    }

    @Test fun automaticVisibleDeliveryOnlySelectsAnAdvertisedEffort() {
        val advertised = dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCatalogLimits.Limits(
            context = 1_048_576,
            output = 131_072,
            reasoningControlAdvertised = true,
            reasoningEfforts = setOf("max", "high", "low", "none")
        )
        val repair = RequestConstraints(maxOutputTokens = 4096, maximizeReasoning = true).forRole(dev.chungjungsoo.gptmobile.data.github.GitHubStageBudget.VISIBLE_OUTPUT_REPAIR_ROLE)
        assertTrue(GitHubReasoningPolicy.automaticVisibleDelivery(repair))
        assertFalse(GitHubReasoningPolicy.automaticVisibleDelivery(repair.copy(reasoningModeOverride = ReasoningMode.ON)))
        assertEquals("none", GitHubReasoningPolicy.visibleOutputEffort(repair, advertised.copy(reasoningMandatory = false)))
        assertEquals("minimal", GitHubReasoningPolicy.visibleOutputEffort(repair, advertised.copy(reasoningMandatory = true, reasoningEfforts = setOf("none", "minimal", "low"))))
        assertEquals("low", GitHubReasoningPolicy.visibleOutputEffort(repair, advertised))
        assertEquals("low", GitHubReasoningPolicy.visibleOutputEffort(repair.copy(reasoningModeOverride = ReasoningMode.AUTO), advertised))
        assertNull(GitHubReasoningPolicy.visibleOutputEffort(repair.copy(reasoningModeOverride = ReasoningMode.ON), advertised))
        assertNull(GitHubReasoningPolicy.visibleOutputEffort(repair.copy(reasoningModeOverride = ReasoningMode.OFF), advertised))
        assertNull(GitHubReasoningPolicy.visibleOutputEffort(repair.copy(allowReasoning = false), advertised))
        assertNull(GitHubReasoningPolicy.visibleOutputEffort(repair, null))
        assertNull(GitHubReasoningPolicy.visibleOutputEffort(repair, advertised.copy(reasoningEfforts = emptySet())))
        assertNull(GitHubReasoningPolicy.visibleOutputEffort(repair, advertised.copy(reasoningControlAdvertised = false)))
        assertNull(GitHubReasoningPolicy.visibleOutputEffort(repair.forRole("primary"), advertised))
        for (level in listOf(dev.chungjungsoo.gptmobile.data.github.GitHubIntelligenceLevel.DEEP_ENGINEERING, dev.chungjungsoo.gptmobile.data.github.GitHubIntelligenceLevel.MAXIMUM_INTELLIGENCE)) {
            val explicit = repair.copy(gitHubIntelligenceLevel = level)
            assertFalse(GitHubReasoningPolicy.automaticVisibleDelivery(explicit))
            assertNull(GitHubReasoningPolicy.visibleOutputEffort(explicit, advertised))
            assertNull(GitHubReasoningPolicy.visibleOutputEffort(explicit.forRole("github-final-summary"), advertised))
        }
        assertNull(GitHubReasoningPolicy.visibleOutputEffort(repair.copy(gitHubEffort = dev.chungjungsoo.gptmobile.data.github.GitHubReasoningEffort.MAXIMUM), advertised))
        val claims = repair.forRole("github-claim-extraction")
        assertEquals("none", GitHubReasoningPolicy.visibleOutputEffort(claims, advertised.copy(reasoningMandatory = false)))
        assertEquals("low", GitHubReasoningPolicy.visibleOutputEffort(claims, advertised))
        assertNull(GitHubReasoningPolicy.visibleOutputEffort(claims.copy(reasoningModeOverride = ReasoningMode.ON), advertised))
        assertEquals(4096, repair.maxOutputTokens)
    }

    @Test fun manualThinkingRecoveryLeavesVisibleRoomWithoutIncreasingTotalOutput() {
        val role = dev.chungjungsoo.gptmobile.data.github.GitHubStageBudget.VISIBLE_OUTPUT_REPAIR_ROLE
        for (output in listOf(2048, 4096, 6144, 8192, 16384)) {
            val reserve = dev.chungjungsoo.gptmobile.data.github.GitHubStageBudget.visibleOutputReserve(output, role)
            val reasoning = GitHubReasoningPolicy.manualThinkingBudget(output, role)
            assertTrue(reasoning >= 1024)
            assertTrue(reasoning + reserve <= output)
        }
        assertEquals(3072, GitHubReasoningPolicy.manualThinkingBudget(6144, role))
        assertEquals(4096, GitHubReasoningPolicy.manualThinkingBudget(8192, role))
        assertEquals(6144, GitHubReasoningPolicy.manualThinkingBudget(8192))
    }

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
