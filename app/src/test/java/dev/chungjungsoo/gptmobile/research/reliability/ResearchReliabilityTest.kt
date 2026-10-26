package dev.chungjungsoo.gptmobile.research.reliability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchReliabilityTest {
    @Test fun reasoningOnlyHttp200IsNotSuccess() {
        assertEquals(
            ProviderOutcome.REASONING_ONLY,
            ProviderOutcomeClassifier.classify(
                ProviderObservation(200, "", reasoningTokens = 512, outputTokens = 512)
            )
        )
    }

    @Test fun visibleOutputIsSuccess() {
        assertEquals(
            ProviderOutcome.SUCCESS_VISIBLE,
            ProviderOutcomeClassifier.classify(
                ProviderObservation(200, "Useful report", outputTokens = 20)
            )
        )
    }

    @Test fun invalidPayloadAndRequestAreDistinct() {
        assertEquals(
            ProviderOutcome.INVALID_PAYLOAD,
            ProviderOutcomeClassifier.classify(
                ProviderObservation(200, null, payloadValid = false)
            )
        )
        assertEquals(
            ProviderOutcome.INVALID_REQUEST,
            ProviderOutcomeClassifier.classify(
                ProviderObservation(422, null)
            )
        )
    }

    @Test fun rateLimitAndAllowanceAreDistinct() {
        assertEquals(
            ProviderOutcome.RATE_LIMITED,
            ProviderOutcomeClassifier.classify(
                ProviderObservation(429, null, retryAfterMillis = 60_000)
            )
        )
        assertEquals(
            ProviderOutcome.ALLOWANCE_EXHAUSTED,
            ProviderOutcomeClassifier.classify(
                ProviderObservation(402, null)
            )
        )
    }

    @Test fun waitingStageResumesOnlyAfterCooldown() {
        val stage = ResearchStageCheckpoint(
            "run",
            "extract",
            ResearchStageStatus.WAITING_PROVIDER_LIMIT,
            retryAtEpochMillis = 1_000
        )
        assertFalse(stage.canResume(999))
        assertTrue(stage.canResume(1_000))
        assertFalse(stage.copy(status = ResearchStageStatus.COMPLETED, retryAtEpochMillis = null).canResume(2_000))
    }

    @Test fun reportCannotPublishWithUnacceptedCitation() {
        val evidence = listOf(ResearchEvidence("source1", "https://example.org", EvidenceStatus.ACCEPTED))
        assertTrue(ResearchReportGate.canPublish("Report [1]", setOf("source1"), evidence))
        assertFalse(ResearchReportGate.canPublish("Report [2]", setOf("source2"), evidence))
        assertFalse(ResearchReportGate.canPublish("Report", emptySet(), evidence))
        assertTrue(ResearchReportGate.shouldOfferPartialReport(evidence))
    }
}
