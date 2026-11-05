package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubIncidentIntentTest {
    @Test fun trivialResponseSmokeTestDoesNotBecomeRepositoryTesting() {
        assertTrue(CurrentRequestContract.from("random test meaning nothing to see response").conversational)
        assertFalse(CurrentRequestContract.from("Just test the GitHub integration").conversational)
        assertFalse(CurrentRequestContract.from("Test all repository workflows").conversational)
    }

    @Test fun legacyCircuitPreservesDeadlineWithoutInventingDispatch() {
        val failure = ProfileFailureRecord.classify("CIRCUIT_BREAKER_OPEN: retryAfterMs=23703; dispatched=false", nowMillis = 1000)
        assertEquals("CIRCUIT_BREAKER_OPEN", failure.category)
        assertEquals(24703L, failure.retryAfterMillis)
        assertEquals(null, failure.dispatched)
        assertEquals(RemoteRequestAcceptance.UNKNOWN, failure.remoteAcceptance)
        assertEquals("stage_not_recorded", failure.phase)
        val dns = ProfileFailureRecord.classify("DNS resolution failed")
        assertEquals("DNS_RESOLUTION_FAILED", dns.category)
        assertEquals(null, dns.dispatched)
    }
}
