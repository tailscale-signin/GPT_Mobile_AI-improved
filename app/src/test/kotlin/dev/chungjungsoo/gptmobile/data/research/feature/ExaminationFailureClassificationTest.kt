package dev.chungjungsoo.gptmobile.data.research.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExaminationFailureClassificationTest {
    @Test fun forbiddenSourceIsBlockedNotNetworkRetry() {
        val failure = examinationFailure(IllegalStateException("HTTP 403 Forbidden from article reader"), 0, 1000L)
        assertEquals("SOURCE_ACCESS_BLOCKED", failure.code)
        assertEquals(ExaminationStage.BLOCKED_SOURCE, failure.stage)
        assertFalse(failure.retryable)
    }

    @Test fun unauthorizedExaminerRequiresReauthentication() {
        val failure = examinationFailure(IllegalStateException("HTTP 401 Unauthorized"), 0, 1000L)
        assertEquals("EXAMINER_AUTH_REQUIRED", failure.code)
        assertEquals(ExaminationStage.PAUSED, failure.stage)
        assertFalse(failure.retryable)
    }

    @Test fun rateLimitedReaderIsScheduledForRetry() {
        val failure = examinationFailure(IllegalStateException("HTTP 429 Too Many Requests"), 0, 1000L)
        assertEquals("RATE_LIMITED", failure.code)
        assertEquals(ExaminationStage.WAITING_RATE_LIMIT, failure.stage)
        assertEquals(true, failure.retryable)
    }

    @Test fun malformedJsonIsNotMisclassifiedAsTransportFailure() {
        val failure = examinationFailure(IllegalStateException("INVALID_JSON in extraction packet"), 0, 1000L)
        assertEquals("OUTPUT_INVALID", failure.code)
        assertEquals(ExaminationStage.FAILED, failure.stage)
    }
}
