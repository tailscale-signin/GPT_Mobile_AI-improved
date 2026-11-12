package dev.chungjungsoo.gptmobile.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEnrichmentSchedulingTest {
    @Test fun initialConstraintUsesTheWorkManagerThresholdAndReportsMissingEvidence() {
        assertEquals(false, initialEnrichmentBatteryNotLow(3, 15, 100))
        assertEquals(true, initialEnrichmentBatteryNotLow(3, 16, 100))
        assertEquals(true, initialEnrichmentBatteryNotLow(1, -1, -1))
        assertEquals(null, initialEnrichmentBatteryNotLow(-1, -1, -1))
        assertEquals(null, initialEnrichmentBatteryNotLow(3, 20, 0))
    }

    @Test fun queuedBatteryConstrainedWorkReportsItsObservedWakeCondition() {
        val state = memoryEnrichmentScheduling("ENQUEUED", true, false)
        assertEquals("WAITING_FOR_BATTERY", state.outcome)
        assertTrue(state.deferred)
        assertEquals("BATTERY_OKAY", state.wakeTrigger)
        assertEquals("SYSTEM_BATTERY_BROADCAST", state.constraintEvidence)
    }

    @Test fun unknownBatteryDoesNotInventAConstraintOrCompletion() {
        val state = memoryEnrichmentScheduling("ENQUEUED", true, null)
        assertEquals("PENDING", state.outcome)
        assertFalse(state.deferred)
        assertEquals("NOT_OBSERVED", state.constraintEvidence)
        assertEquals("WORKMANAGER_SCHEDULE_OR_CONSTRAINT", state.wakeTrigger)
    }

    @Test fun batteryRecoveryAndBackoffDoNotLeaveStaleDeferralTelemetry() {
        val state = memoryEnrichmentScheduling("ENQUEUED", true, true, attempt = 2)
        assertEquals("PENDING", state.outcome)
        assertFalse(state.deferred)
        assertEquals("WORKMANAGER_BACKOFF_OR_CONSTRAINT", state.wakeTrigger)
    }

    @Test fun completedAndRunningWorkerOutcomesAreNotRewrittenByBatteryChanges() {
        val complete = memoryEnrichmentScheduling("SUCCEEDED", true, false, "ENRICHED", "COMPLETE")
        assertEquals("ENRICHED", complete.outcome)
        assertEquals("COMPLETE", complete.wakeTrigger)
        assertFalse(complete.deferred)
        val running = memoryEnrichmentScheduling("RUNNING", true, false)
        assertEquals("PENDING", running.outcome)
        assertEquals("IN_PROGRESS", running.wakeTrigger)
        assertFalse(running.deferred)
    }

    @Test fun workerReportedModelWaitAndUnconstrainedWorkKeepTheirOwnEvidence() {
        val model = memoryEnrichmentScheduling("SUCCEEDED", true, false, "WAITING_FOR_MODEL", "MODEL_AVAILABLE")
        assertEquals("WAITING_FOR_MODEL", model.outcome)
        assertTrue(model.deferred)
        assertEquals("MODEL_AVAILABLE", model.wakeTrigger)
        assertFalse(memoryEnrichmentScheduling("ENQUEUED", false, false).deferred)
    }
}
