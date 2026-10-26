package dev.chungjungsoo.gptmobile.research.reliability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchRecoveryTest {
    private val pending = ResearchStageCheckpoint("run-1", "extract-1", ResearchStageStatus.PENDING)

    @Test fun rateLimitWaitsUntilRetryAfter() {
        val next = ResearchRecovery.onProviderResult(pending, ProviderOutcome.RATE_LIMITED, 1000, 5000)
        assertEquals(ResearchStageStatus.WAITING_PROVIDER_LIMIT, next.status)
        assertEquals(6000L, next.retryAtEpochMillis)
        assertFalse(next.canResume(5999))
        assertTrue(next.canResume(6000))
    }

    @Test fun invalidRequestIsTerminal() {
        val next = ResearchRecovery.onProviderResult(pending, ProviderOutcome.INVALID_REQUEST, 0)
        assertEquals(ResearchStageStatus.TERMINAL_FAILED, next.status)
        assertFalse(next.canResume(10000))
    }

    @Test fun emptyReasoningUsesBoundedRetry() {
        val one = ResearchRecovery.onProviderResult(pending, ProviderOutcome.REASONING_ONLY, 0, maxAttempts = 2)
        assertEquals(ResearchStageStatus.RETRYABLE_FAILED, one.status)
        val two = ResearchRecovery.onProviderResult(one, ProviderOutcome.REASONING_ONLY, 1, maxAttempts = 2)
        assertEquals(ResearchStageStatus.TERMINAL_FAILED, two.status)
    }

    @Test fun acceptedEvidenceSurvivesFailedExtraction() {
        val accepted = ResearchEvidence("source-1", "https://example.org", EvidenceStatus.ACCEPTED)
        val failed = ResearchEvidence(
            "source-1",
            "https://example.org",
            EvidenceStatus.EXCLUDED,
            exclusionReason = "provider failed"
        )
        assertEquals(accepted, ResearchEvidenceLedger.merge(listOf(accepted), listOf(failed)).single())
    }

    @Test fun evidenceDeduplicatesAndUpgrades() {
        val discovered = ResearchEvidence("source-1", "https://example.org", EvidenceStatus.DISCOVERED)
        val validated = discovered.copy(status = EvidenceStatus.VALIDATED)
        val merged = ResearchEvidenceLedger.merge(listOf(discovered), listOf(validated, discovered))
        assertEquals(1, merged.size)
        assertEquals(EvidenceStatus.VALIDATED, merged.single().status)
    }

    @Test fun providerSuccessCompletesStage() {
        assertEquals(
            ResearchStageStatus.COMPLETED,
            ResearchRecovery.onProviderResult(pending, ProviderOutcome.SUCCESS_VISIBLE, 0).status
        )
    }
}
