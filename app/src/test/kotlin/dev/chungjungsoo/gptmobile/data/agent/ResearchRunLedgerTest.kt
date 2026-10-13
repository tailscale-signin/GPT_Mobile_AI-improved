package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.ResearchUserLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchRunLedgerTest {
    @Test fun noLegacyRequestOrByteCeilingIsInherited() {
        val ledger = ResearchRunLedger("policy", ResearchUserLimits())
        assertTrue(ledger.charge(logical = 3001, physical = 4000, downloaded = 3L * 1024 * 1024 * 1024, activeMillis = 7_200_000))
        assertEquals(4000L, ledger.snapshot().physical)
        val restored = ResearchRunLedger("policy", ResearchUserLimits())
        restored.restore(ledger.snapshot())
        assertTrue(restored.charge(physical = 1))
        assertEquals(4001L, restored.snapshot().physical)
    }

    @Test fun enabledUserLimitStaysStoppedAcrossRestore() {
        val limits = ResearchUserLimits(maxRequests = 2)
        val ledger = ResearchRunLedger("policy", limits)
        assertTrue(ledger.charge(physical = 2))
        assertFalse(ledger.charge(physical = 1))
        val restored = ResearchRunLedger("policy", limits)
        restored.restore(ledger.snapshot())
        assertFalse(restored.charge())
        assertEquals("user_request_limit", restored.snapshot().limitReason)
    }

    @Test fun corruptOrOverflowingAccountingCannotCreateANewAllowance() {
        val ledger = ResearchRunLedger("policy", ResearchUserLimits())
        assertTrue(runCatching { ledger.restore(ledger.snapshot().copy(physical = -1)) }.isFailure)
        assertTrue(runCatching { ledger.restore(ledger.snapshot().copy(version = 99)) }.isFailure)
        assertTrue(runCatching { ledger.restore(ledger.snapshot().copy(fingerprint = "other")) }.isFailure)
        ledger.restore(ledger.snapshot().copy(downloaded = Long.MAX_VALUE))
        assertTrue(runCatching { ledger.charge(downloaded = 1) }.isFailure)
        assertEquals(Long.MAX_VALUE, ledger.snapshot().downloaded)
    }

    @Test fun failedReservationWritePreventsDispatch() {
        val ledger = ResearchRunLedger("policy", ResearchUserLimits()) { error("disk full") }
        assertTrue(runCatching { ledger.charge(physical = 1) }.isFailure)
        assertEquals(0L, ledger.snapshot().physical)
    }

    @Test fun byteChunksReserveAheadWithoutWritingEveryRead() {
        val writes = mutableListOf<ResearchRunLedger.State>()
        val ledger = ResearchRunLedger("p", ResearchUserLimits(), writes::add)
        repeat(100) { assertTrue(ledger.charge(downloaded = 1024)) }
        assertEquals(1, writes.size)
        assertEquals(102400L, ledger.snapshot().downloaded)
        assertEquals(262144L, writes.last().downloaded)
        val restored = ResearchRunLedger("p", ResearchUserLimits())
        restored.restore(writes.last())
        assertEquals(262144L, restored.snapshot().downloaded)
    }

    @Test fun overlappingWorkersChargeElapsedWallTimeOnce() {
        val ledger = ResearchRunLedger("p", ResearchUserLimits())
        ledger.beginActive(100)
        ledger.beginActive(100)
        ledger.tick(200)
        ledger.tick(200)
        ledger.tick(150)
        ledger.endActive(300)
        ledger.endActive(400)
        assertEquals(300L, ledger.snapshot().activeMillis)
        ledger.beginActive(1000)
        ledger.endActive(1100)
        assertEquals(400L, ledger.snapshot().activeMillis)
    }

    @Test fun reservationNeverExceedsUserLimitAndStopDoesNotRefundIt() {
        val writes = mutableListOf<ResearchRunLedger.State>()
        val ledger = ResearchRunLedger("p", ResearchUserLimits(maxDownloadedBytes = 10000), writes::add)
        assertTrue(ledger.charge(downloaded = 100))
        assertEquals(10000L, writes.last().downloaded)
        assertFalse(ledger.charge(downloaded = 10000))
        assertEquals(10000L, writes.last().downloaded)
        assertEquals("user_download_limit", writes.last().limitReason)
    }

    @Test fun delegationCountersAreDistinctIdempotentAndSurviveRestartWithoutMintingAllowance() {
        val writes = mutableListOf<ResearchRunLedger.State>()
        val ledger = ResearchRunLedger("p", ResearchUserLimits(), writes::add)
        repeat(16) { assertTrue(ledger.reserveDelegation("research_operations", "operation-$it", 16)) }
        repeat(24) { assertTrue(ledger.reserveDelegation("model_invocations", "model-$it", 48)) }
        assertTrue(ledger.reserveDelegation("model_invocations", "model-0", 48))
        assertEquals(24, ledger.snapshot().delegationReservations.getValue("model_invocations").size)
        val restarted = ResearchRunLedger("p", ResearchUserLimits())
        restarted.restore(writes.last())
        assertFalse(restarted.reserveDelegation("research_operations", "extra", 48))
        repeat(23) { assertTrue(restarted.reserveDelegation("model_invocations", "continued-$it", 48, reserveForReview = 1)) }
        assertFalse(restarted.reserveDelegation("model_invocations", "worker-overflow", 48, reserveForReview = 1))
        assertTrue(restarted.reserveDelegation("model_invocations", "independent-review", 48))
        assertFalse(restarted.reserveDelegation("model_invocations", "extra", 48))
        assertEquals(0L, restarted.snapshot().physical)
    }

    @Test fun delegationReservationCannotDispatchAfterPersistenceFailure() {
        val ledger = ResearchRunLedger("p", ResearchUserLimits()) { error("disk full") }
        assertTrue(runCatching { ledger.reserveDelegation("http_attempts", "attempt", 48) }.isFailure)
        assertTrue(ledger.snapshot().delegationReservations.isEmpty())
    }
}
