package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints
import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubStageBudgetTest {
    @Test fun helperPreviewIncludesTheFinalSerializedSafetyAndItsOwnOutputReservation() {
        val runtime = dev.chungjungsoo.gptmobile.data.context.RuntimeFingerprint("profile", "route", "credentials", "model", "model", "runtime", "template", "allocation")
        for (context in listOf(4096, 8192, 32768, 131072)) {
            for (role in listOf("github-review", "github-review-preflight", "github-review-preflight-output-retry", "github-review-repair", "github-review-output-retry", "github-claim-extraction")) {
                val allowance = GitHubStageBudget.resolve(context, null, role)
                val finalPrompt = allowance.input.toLong() + 1536
                val measurement = dev.chungjungsoo.gptmobile.data.context.RequestMeasurement(finalPrompt, dev.chungjungsoo.gptmobile.data.context.RequestAdmission.estimateSafety(finalPrompt), dev.chungjungsoo.gptmobile.data.context.TokenMeasurementKind.UTF8_ESTIMATE)
                val prepared = dev.chungjungsoo.gptmobile.data.context.RequestAdmission.prepare("final serialized request", role, dev.chungjungsoo.gptmobile.data.context.CapacitySnapshot(runtime, context.toLong()), measurement, allowance.output.toLong(), "policy")
                assertTrue(prepared.receipt.capacityChecked)
            }
        }
        // The captured 27,553 + 6,888 + 1,536 request must be split before dispatch.
        assertTrue(GitHubStageBudget.resolve(32768, null, "github-review").input < 27553)
        assertEquals(4096, GitHubStageBudget.resolve(32768, null, "github-review-output-retry").output)
        assertEquals(1536, GitHubStageBudget.resolve(32768, 1536, "github-review-output-retry").output)
    }

    @Test fun compactReviewDoesNotConsumeTheFinalClaimRenderingAllowance() {
        assertEquals(2048, GitHubStageBudget.resolve(32768, null, "github-review-preflight").output)
        assertEquals(4096, GitHubStageBudget.resolve(32768, null, "github-review-preflight-output-retry").output)
        assertEquals(2048, GitHubStageBudget.resolve(32768, 2048, "github-review-preflight-output-retry").output)
        assertEquals(1536, GitHubStageBudget.resolve(32768, null, "github-review").output)
        assertEquals(4096, GitHubStageBudget.resolve(32768, null, "github-review-repair").output)
        assertEquals(4096, GitHubStageBudget.resolve(32768, null, "github-claim-extraction").output)
    }

    @Test fun preflightReservationAndRequestAgreeWithoutChangingExplicitReasoningOrHardCaps() {
        for (role in listOf(GitHubReviewCompatibility.PREFLIGHT_ROLE, GitHubReviewCompatibility.OUTPUT_RETRY_ROLE)) {
            val request = RequestConstraints(allowReasoning = true, reasoningModeOverride = ReasoningMode.ON, maximizeReasoning = true).forRole(role)
            assertEquals(GitHubStageBudget.resolve(32768, null, role).output, request.maxOutputTokens)
            assertEquals(ReasoningMode.ON, request.reasoningModeOverride)
            assertTrue(request.allowReasoning)
            assertFalse(request.maximizeReasoning)
            val capped = RequestConstraints(maxOutputTokens = 256).forRole(role)
            assertEquals(256, capped.maxOutputTokens)
            assertEquals(256, GitHubStageBudget.resolve(32768, capped.maxOutputTokens, role).output)
        }
    }

    @Test fun everyStageHonorsSelectedProfileCapAndLeavesInputOutputAndSafetyWithinContext() {
        for (context in listOf(0, 512, 2048, 8192, 32768)) {
            for (role in listOf("github-review", "github-review-preflight", "github-review-preflight-output-retry", "github-review-repair", "github-claim-extraction")) {
                val budget = GitHubStageBudget.resolve(context, 256, role)
                assertTrue(budget.output <= 256)
                assertTrue(budget.input >= 0 && budget.output >= 0)
                assertTrue(budget.input + budget.output <= context)
                if (context >= 2048) assertTrue(budget.input + budget.output + 1536 <= context)
            }
        }
    }
}
