package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.agent.CombinedBudgetExceeded
import dev.chungjungsoo.gptmobile.data.agent.tool.DelegateFailureKind
import dev.chungjungsoo.gptmobile.data.agent.tool.DelegateGenerationException
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExaminationFailureTest {
    @Test fun malformedBudgetFieldsDoNotBecomeCapacityWaits() {
        val failure = examinationFailure(IllegalStateException("EXAMINATION_OUTPUT_INVALID: budget field must be numeric"), 0, 1000)
        assertEquals("OUTPUT_INVALID", failure.code)
        assertEquals(ExaminationStage.FAILED, failure.stage)
        assertFalse(failure.retryable)
    }

    @Test fun knownCapacityFailuresRemainActionableAfterWrapping() {
        listOf(
            IllegalStateException("${ExaminationOutputBudget.CONTEXT_INSUFFICIENT}: packet too large"),
            IllegalStateException("REVIEW_PACKET_TOO_LARGE: evidence retained"),
            CombinedBudgetExceeded("output reservation denied")
        ).forEach { cause ->
            val failure = examinationFailure(IllegalStateException("Stage failed", cause), 0, 1000)
            assertEquals("ADMITTED_CAPACITY_EXHAUSTED", failure.code)
            assertEquals(ExaminationStage.WAITING_CAPACITY, failure.stage)
            assertFalse(failure.retryable)
        }
    }

    @Test fun wrappedRetrievalRetainsProviderDeadlineAndSourceIdentity() {
        val evidence = AcademicEvidence("paper", "v1", "https://example.org/paper", 0, "hash", "", "blocked", failureCode = "RATE_LIMITED", retryAtMs = 90_000, redirectChain = listOf("https://example.org/original"))
        val failure = examinationFailure(IllegalStateException("Stage failed", ExaminationRetrievalException(evidence)), 1, 1000)
        assertEquals("RATE_LIMITED", failure.code)
        assertEquals(90_000L, failure.retryAtMs)
        assertEquals(evidence.sourceUrl, failure.sourceUrl)
        assertEquals(evidence.redirectChain, failure.redirectChain)
        assertTrue(failure.retryable)
    }

    @Test fun reasoningOnlyAndEmptyProviderResponsesHaveDistinctVisibleFailures() {
        fun failure(kind: DelegateFailureKind) = examinationFailure(DelegateGenerationException(kind, "No visible answer", 0, 0, 0, true), 0, 1000)
        assertEquals("REASONING_ONLY", failure(DelegateFailureKind.REASONING_ONLY).code)
        assertEquals("OUTPUT_EMPTY", failure(DelegateFailureKind.EMPTY_OUTPUT).code)
        assertEquals(ExaminationStage.FAILED, failure(DelegateFailureKind.REASONING_ONLY).stage)
    }

    @Test fun sourceAndGroundingFailuresDoNotBecomeTransportRetries() {
        mapOf("SOURCE_TEXT_EMPTY" to "SOURCE_TEXT_EMPTY", "EXTRACTION_EMPTY" to "NO_GROUNDED_OUTCOME", "GROUNDING_INVALID" to "GROUNDING_INVALID").forEach { (message, code) ->
            val failure = examinationFailure(IllegalStateException("Stage failed", IllegalStateException("$message: retained source")), 0, 1000)
            assertEquals(code, failure.code)
            assertFalse(failure.retryable)
        }
    }
}
