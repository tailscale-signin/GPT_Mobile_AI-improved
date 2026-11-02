package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendAttemptTraceTest {
    @Test fun acceptanceLinksAllRunsAndHasOneSubmissionOutcome() {
        val events = mutableListOf<String>()
        val trace = SendAttemptTrace("request-1", events::add)
        trace.accepted(listOf("run-1", "run-2"), 10, 20)
        trace.finish("LATE_FAILURE")
        assertEquals(1, events.count { it.startsWith("SEND_TAPPED") })
        assertEquals(2, events.count { it.startsWith("SEND_DURABLY_ACCEPTED") })
        assertEquals(1, events.count { it.startsWith("SEND_SUBMISSION_OUTCOME") })
        assertTrue(events.all { "requestId=request-1" in it })
        assertTrue(events.last().endsWith("DURABLY_ACCEPTED"))
    }

    @Test fun rejectionIsExplicitAndIdempotent() {
        val events = mutableListOf<String>()
        val trace = SendAttemptTrace("request-2", events::add)
        trace.finish("ATTACHMENT_PREPARING")
        trace.finish("PREPARATION_FAILED")
        assertEquals(2, events.size)
        assertTrue(events.last().endsWith("ATTACHMENT_PREPARING"))
    }
}
