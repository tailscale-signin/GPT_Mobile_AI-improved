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
}
