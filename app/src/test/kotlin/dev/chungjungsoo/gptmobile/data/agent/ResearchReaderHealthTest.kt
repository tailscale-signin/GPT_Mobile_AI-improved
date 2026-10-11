package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchReaderHealthTest {
    @Test fun blockedHostDoesNotDisableFallbackAndExpires() {
        var time = 0L
        val health = ResearchReaderHealth { time }
        health.record("reader-a", "https://example.org/a", ToolOutcome.BLOCKED)
        assertFalse(health.available("reader-a", "https://example.org/b"))
        assertTrue(health.available("reader-b", "https://example.org/a"))
        assertTrue(health.available("reader-a", "https://other.org/a"))
        time = 300_000L
        assertTrue(health.available("reader-a", "https://example.org/a"))
    }

    @Test fun authAndBillingFailuresOpenReaderCircuitForTurn() {
        val health = ResearchReaderHealth()
        health.record("auth", "https://one.org", ToolOutcome.AUTH_FAILED)
        health.record("billing", "https://one.org", ToolOutcome.FAILED, billingFailure = true)
        assertFalse(health.available("auth", "https://two.org"))
        assertFalse(health.available("billing", "https://two.org"))
        assertTrue(health.available("fallback", "https://two.org"))
    }

    @Test fun repeatedMalformedResponsesOpenCircuitButSuccessResetsCount() {
        val health = ResearchReaderHealth()
        health.record("reader", "https://one.org", ToolOutcome.INVALID_RESPONSE)
        assertTrue(health.available("reader", "https://two.org"))
        health.record("reader", "https://two.org", ToolOutcome.SUCCESS)
        health.record("reader", "https://three.org", ToolOutcome.INVALID_RESPONSE)
        assertTrue(health.available("reader", "https://four.org"))
        health.record("reader", "https://four.org", ToolOutcome.INVALID_RESPONSE)
        assertFalse(health.available("reader", "https://five.org"))
    }
}
