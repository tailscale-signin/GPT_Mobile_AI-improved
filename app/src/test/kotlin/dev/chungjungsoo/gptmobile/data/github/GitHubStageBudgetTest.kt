package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.provider.RequestConstraints
import dev.chungjungsoo.gptmobile.data.context.CapacityProvenance
import dev.chungjungsoo.gptmobile.data.context.CapacitySnapshot
import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import dev.chungjungsoo.gptmobile.data.context.RequestAdmission
import dev.chungjungsoo.gptmobile.data.context.RequestCapacityException
import dev.chungjungsoo.gptmobile.data.context.RequestMeasurement
import dev.chungjungsoo.gptmobile.data.context.RuntimeFingerprint
import dev.chungjungsoo.gptmobile.data.context.TokenMeasurementKind
import dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubStageBudgetTest {
    @Test fun inputCapacityRejectionNeverBecomesObservedContext() {
        for (provenance in listOf(CapacityProvenance.DECLARED, CapacityProvenance.VERIFIED_ROUTE)) {
            val capacity = CapacitySnapshot(runtime(), 32768, inputTokens = 512, provenance = provenance)
            val failure = mappedRejection(capacity, prompt = 1024, output = 256)
            assertEquals("INPUT_CAPACITY_EXCEEDED", failure.errorCode)
            assertEquals(JsonPrimitive(512L), failure.failureDetails?.get("admission_capacity_tokens"))
            assertNull(GitHubStageBudget.observedContext(null, failure))
        }
    }

    @Test fun outputCapacityRejectionNeverBecomesObservedContext() {
        for (provenance in listOf(CapacityProvenance.DECLARED, CapacityProvenance.VERIFIED_ROUTE)) {
            val capacity = CapacitySnapshot(runtime(), 32768, outputTokens = 512, provenance = provenance)
            val failure = mappedRejection(capacity, prompt = 1024, output = 1024)
            assertEquals("OUTPUT_CAPACITY_EXCEEDED", failure.errorCode)
            assertEquals(JsonPrimitive(512L), failure.failureDetails?.get("admission_capacity_tokens"))
            assertNull(GitHubStageBudget.observedContext(null, failure))
        }
    }

    @Test fun contextRejectionRequiresTrustedProvenanceAndPreservesAdmittedReceiptPriority() {
        val admitted = RequestAdmission.prepare(
            "admitted request",
            "primary",
            CapacitySnapshot(runtime(), 65536, provenance = CapacityProvenance.VERIFIED_ROUTE),
            RequestMeasurement(1024, 256, TokenMeasurementKind.UTF8_ESTIMATE),
            512,
            "policy"
        ).receipt.contextTokens
        assertEquals(65536L, admitted)
        assertEquals(65536, GitHubStageBudget.observedContext(admitted, null))
        for (provenance in CapacityProvenance.entries) {
            val failure = mappedRejection(CapacitySnapshot(runtime(), 32768, provenance = provenance), prompt = 32768, output = 512)
            assertEquals("CONTEXT_CAPACITY_EXCEEDED", failure.errorCode)
            assertEquals(JsonPrimitive(32768L), failure.failureDetails?.get("admission_capacity_tokens"))
            val expected = 32768.takeIf { provenance in setOf(CapacityProvenance.DECLARED, CapacityProvenance.VERIFIED_ROUTE) }
            assertEquals(expected, GitHubStageBudget.observedContext(null, failure))
            assertEquals(65536, GitHubStageBudget.observedContext(admitted, failure))
            for (invalidAdmitted in listOf(-1L, 0L, Int.MAX_VALUE.toLong() + 1)) {
                assertNull(GitHubStageBudget.observedContext(invalidAdmitted, failure))
            }
        }
        for (context in listOf(1L, Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong() + 1)) {
            val failure = mappedRejection(CapacitySnapshot(runtime(), context, provenance = CapacityProvenance.DECLARED), prompt = context, output = 512)
            assertEquals(context.takeIf { it <= Int.MAX_VALUE }?.toInt(), GitHubStageBudget.observedContext(null, failure))
        }
        assertNull(GitHubStageBudget.observedContext(null, null))
    }

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
        assertEquals(8192, GitHubStageBudget.resolve(32768, null, "github-claim-extraction").output)
        assertEquals(4096, GitHubStageBudget.resolve(32768, 4096, "github-claim-extraction").output)
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

    private fun runtime() = RuntimeFingerprint("profile", "route", "credentials", "model", "model", "runtime", "template", "allocation")

    private fun mappedRejection(capacity: CapacitySnapshot, prompt: Long, output: Long): ProviderEvent.Failed {
        val error = assertThrows(RequestCapacityException::class.java) {
            RequestAdmission.prepare("rejected request", "primary", capacity, RequestMeasurement(prompt, 256, TokenMeasurementKind.UTF8_ESTIMATE), output, "policy")
        }
        val detail = requireNotNull(providerExceptionDetails(error, partialOutput = false, nowMillis = 1000, monotonicMillis = 1000))
        assertFalse(detail.retryable ?: true)
        return ProviderEvent.Failed(detail.message.orEmpty(), errorCode = detail.code, failureDetails = detail.failureDetails)
    }
}
