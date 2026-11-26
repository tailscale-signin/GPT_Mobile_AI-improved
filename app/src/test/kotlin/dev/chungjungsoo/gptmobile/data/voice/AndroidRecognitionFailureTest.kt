package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidRecognitionFailureTest {
    @Test fun rejectionBeforeDispatchKeepsTheAttemptAndDoesNotInventAProviderReset() {
        val failure = AndroidRecognitionFailure(10, AndroidRecognitionFailure.Stage.SUPPORT_CHECK, false, "owned-attempt", 15_000)
        val fields = failure.reportFields(retryScheduled = false)
        assertEquals(10, failure.platformError)
        assertEquals("precheck", fields["failed_stage"])
        assertEquals(false, fields["dispatched"])
        assertEquals("owned-attempt", fields["causal_id"])
        assertEquals("EXPLICIT_CAUSE", fields["causal_provenance"])
        assertEquals("LOCAL_BACKOFF", fields["retry_delay_source"])
        assertEquals(15_000L, fields["retry_delay_ms"])
        assertEquals("TERMINAL", fields["recovery_observation"])
        assertFalse(failure.message.orEmpty().contains("owned-attempt"))
    }

    @Test fun readyAndStartingAttemptsRetainDifferentPhasesAndTheirScheduledRetryState() {
        for (stage in listOf(AndroidRecognitionFailure.Stage.STARTING, AndroidRecognitionFailure.Stage.RECOGNITION)) {
            val fields = AndroidRecognitionFailure(2, stage, true, "owned-attempt", 500).reportFields(retryScheduled = true)
            assertEquals(if (stage == AndroidRecognitionFailure.Stage.STARTING) "capture_start" else "voice_recognition", fields["failed_stage"])
            assertEquals(true, fields["dispatched"])
            assertEquals("RETRY_SCHEDULED", fields["recovery_observation"])
        }
    }

    @Test fun absentLocalDelayDoesNotCreateAClaimedCooldown() {
        val failure = AndroidRecognitionFailure(null, AndroidRecognitionFailure.Stage.SUPPORT_CHECK, false, "attempt", 0)
        val fields = failure.reportFields(retryScheduled = false)
        assertEquals(null, failure.platformError)
        assertTrue(fields.keys.none { it.startsWith("retry_delay") })
    }
}
