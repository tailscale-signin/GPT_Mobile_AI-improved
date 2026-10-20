package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.ResearchUserLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchAcquisitionSchedulerTest {
    @Test fun searchFanoutCannotSpendProtectedReadsOrMetadata() {
        val ledger = ResearchRunLedger("task", ResearchUserLimits(maxRequests = 100))
        ledger.configureAcquisition(12)
        assertTrue(ledger.chargeAcquisition(ResearchRunLedger.AcquisitionLane.SEARCH, 64))
        assertFalse(ledger.chargeAcquisition(ResearchRunLedger.AcquisitionLane.SEARCH, 1))
        assertNull(ledger.snapshot().limitReason)
        assertTrue(ledger.chargeAcquisition(ResearchRunLedger.AcquisitionLane.READ, 24))
        assertFalse(ledger.chargeAcquisition(ResearchRunLedger.AcquisitionLane.READ, 1))
        assertTrue(ledger.chargeAcquisition(ResearchRunLedger.AcquisitionLane.METADATA, 12))
        assertEquals(100L, ledger.snapshot().physical)
    }

    @Test fun reservationsSurviveRestartAndSettleOnlyKnownUnusedWork() {
        var saved: ResearchRunLedger.State? = null
        val limits = ResearchUserLimits(maxRequests = 30)
        val ledger = ResearchRunLedger("task", limits) { saved = it }
        ledger.configureAcquisition(300)
        assertTrue(ledger.reserveAcquisition("gateway", ResearchRunLedger.AcquisitionLane.SEARCH, 6))
        ledger.settleAcquisition("gateway", null)
        val restored = ResearchRunLedger("task", limits)
        restored.restore(requireNotNull(saved))
        restored.configureAcquisition(1)
        assertEquals(18L, restored.snapshot().readingReservation)
        assertEquals(0L, restored.acquisitionAvailable(ResearchRunLedger.AcquisitionLane.SEARCH))
        restored.settleAcquisition("gateway", 2)
        assertEquals(2L, restored.snapshot().physical)
        assertEquals(4L, restored.acquisitionAvailable(ResearchRunLedger.AcquisitionLane.SEARCH))
        restored.settleAcquisition("gateway", 0)
        assertEquals(2L, restored.snapshot().physical)
    }

    @Test fun failedPersistenceCannotGrantOrConsumeAnAcquisition() {
        val ledger = ResearchRunLedger("task", ResearchUserLimits(maxRequests = 10)) { error("disk full") }
        val initial = ledger.snapshot()
        assertTrue(runCatching { ledger.configureAcquisition(10) }.isFailure)
        assertEquals(initial, ledger.snapshot())
        assertTrue(runCatching { ledger.chargeAcquisition(ResearchRunLedger.AcquisitionLane.READ, 1) }.isFailure)
        assertEquals(initial, ledger.snapshot())
    }

    @Test fun unknownLegacyChargesAreNotRefundedByLaneConfiguration() {
        val ledger = ResearchRunLedger("task", ResearchUserLimits(maxRequests = 20))
        assertTrue(ledger.charge(physical = 10))
        ledger.configureAcquisition(12)
        assertEquals(10L, ledger.snapshot().physical)
        assertEquals(2L, ledger.acquisitionAvailable(ResearchRunLedger.AcquisitionLane.SEARCH))
    }
}
