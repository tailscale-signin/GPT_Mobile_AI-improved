package dev.chungjungsoo.gptmobile.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEnrichmentOutcomeTest {
    @Test fun localFallbackAcknowledgementRetainsSemanticWorkAndModelWakeup() {
        val receipt = MemoryEnrichmentOutcome("WAITING_FOR_MODEL", "LOCAL_FALLBACK_COMPLETED")
        assertFalse(receipt.enrichmentComplete)
        assertTrue(receipt.deferred)
        assertTrue(receipt.pendingSourceRequired)
        assertEquals("MODEL_AVAILABLE_OR_PROCESS_RESTART", receipt.wakeTrigger)
    }

    @Test fun idleDeferralAndRetryPreserveTheirDifferentWakeups() {
        val waiting = MemoryEnrichmentOutcome("WAITING_FOR_IDLE", "RUNTIME_BUSY")
        val retry = MemoryEnrichmentOutcome("RETRY", "EXCEPTION")
        assertTrue(waiting.deferred)
        assertTrue(waiting.pendingSourceRequired)
        assertEquals("RUNTIME_IDLE_OR_PROCESS_RESTART", waiting.wakeTrigger)
        assertFalse(retry.deferred)
        assertFalse(retry.enrichmentComplete)
        assertTrue(retry.pendingSourceRequired)
        assertEquals("WORKMANAGER_BACKOFF", retry.wakeTrigger)
    }

    @Test fun completedZeroWriteWorkIsDifferentFromUnprocessedOrFailedWork() {
        for ((status, reason) in listOf("SUCCESS" to "ENRICHED_WITH_WRITES", "PROCESSED_NO_NEW_FACTS" to "PROCESSED_NO_NEW_FACTS", "SKIPPED" to "NO_ELIGIBLE_FACTS")) {
            val receipt = MemoryEnrichmentOutcome(status, reason)
            assertTrue(receipt.enrichmentComplete)
            assertFalse(receipt.pendingSourceRequired)
        }
        assertFalse(MemoryEnrichmentOutcome("SKIPPED", "SOURCE_MISSING").enrichmentComplete)
        val failure = MemoryEnrichmentOutcome("FAILED", "INVALID_OUTPUT:RETRIES_EXHAUSTED")
        assertFalse(failure.enrichmentComplete)
        assertTrue(failure.pendingSourceRequired)
        assertEquals("RUNTIME_CHANGE_OR_EXPLICIT_RETRY", failure.wakeTrigger)
    }
}
