package dev.chungjungsoo.gptmobile.data.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayJobsObservationTest {
    private fun saved(terminal: Boolean = false, session: String = GatewayObservationClock.session): GatewaySavedJob {
        val job = GatewayCatalogJob("job", "engineering", "conversation", 1, "CI", if (terminal) "completed" else "running", acceptedAtMs = 1000, updatedAtMs = 5000, finishedAtMs = if (terminal) 6000 else null, serverTimeMs = 5000)
        val scope = GatewayScopeRecord("scope", "profile", "endpoint", "gateway", "principal", "account", "ONLINE", 10000)
        val conversation = GatewayConversationRecord("conversation", "scope", "conversation", 7, "profile", "Original profile", "model", "owner/repo", "feature", "Task", 1, null, null, false, null, 5000)
        val row = GatewayJobRecord("row", "scope", "job", "conversation", "engineering", 1, job.schedulingState, "CI", 1000, job.finishedAtMs, 10000, 100, session, 5000, "{}")
        return GatewaySavedJob(row, job, conversation, scope)
    }

    @Test fun activeDurationUsesOneMonotonicAnchorAcrossRenderTicks() {
        val job = saved()
        assertEquals(4000L, job.elapsedMillis(100))
        assertEquals(6000L, job.elapsedMillis(2100))
        assertEquals(6500L, job.elapsedMillis(2600))
    }

    @Test fun processRestartDoesNotCompareUnrelatedMonotonicEpochs() {
        assertEquals(4000L, saved(session = "previous-process").elapsedMillis(900000))
    }

    @Test fun terminalDurationDoesNotContinueTicking() {
        assertEquals(5000L, saved(terminal = true).elapsedMillis(500000))
    }

    @Test fun clockRollbackAndExpiredObservationAreStale() {
        val job = saved()
        assertFalse(job.stale(10000))
        assertTrue(job.stale(9999))
        assertTrue(job.stale(40001))
    }
}
