package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchBudgetTest {
    @Test fun discoveryCannotConsumeRecoveryOrFinalizationReservations() {
        val budget = ResearchBudget(totalLogicalCalls = 100)

        repeat(65) { assertTrue(budget.tryCharge(ResearchBudget.Phase.DISCOVERY, physicalRequest = true)) }
        assertFalse(budget.tryCharge(ResearchBudget.Phase.DISCOVERY, physicalRequest = true))

        val state = budget.snapshot()
        assertEquals(35, state.remaining)
        assertEquals(65, state.physicalRequests)
        assertEquals(10, state.remainingByPhase.getValue(ResearchBudget.Phase.RECOVERY))
        assertEquals(10, state.remainingByPhase.getValue(ResearchBudget.Phase.FINALIZATION))
    }

    @Test fun cacheHitsChargeLogicalCallsButNotPhysicalRequests() {
        val budget = ResearchBudget(totalLogicalCalls = 20)

        assertTrue(budget.tryCharge(ResearchBudget.Phase.DISCOVERY, physicalRequest = false))

        assertEquals(1, budget.snapshot().usedLogical)
        assertEquals(0, budget.snapshot().physicalRequests)
    }

    @Test fun smallBudgetPreservesEveryPhaseWithoutIncreasingTotal() {
        val budget = ResearchBudget(4)
        ResearchBudget.Phase.entries.forEach { phase ->
            assertEquals(1, budget.snapshot().remainingByPhase.getValue(phase))
            assertTrue(budget.tryCharge(phase, physicalRequest = true))
            assertFalse(budget.tryCharge(phase, physicalRequest = true))
        }
        assertEquals(0, budget.snapshot().remaining)
    }

    @Test fun discoveryExhaustionDoesNotPreventReview() {
        val budget = ResearchBudget(24)
        while (budget.tryCharge(ResearchBudget.Phase.DISCOVERY, physicalRequest = true)) { }
        assertTrue(budget.tryCharge(ResearchBudget.Phase.VERIFICATION, physicalRequest = true))
        assertTrue(budget.tryCharge(ResearchBudget.Phase.RECOVERY, physicalRequest = true))
        assertTrue(budget.tryCharge(ResearchBudget.Phase.FINALIZATION, physicalRequest = true))
    }

    @Test fun oversizedBatchCannotOverflowOrMutateCounters() {
        val budget = ResearchBudget(100)
        assertTrue(budget.tryCharge(ResearchBudget.Phase.DISCOVERY, physicalRequest = true))
        val before = budget.snapshot()
        assertFalse(budget.tryCharge(ResearchBudget.Phase.DISCOVERY, physicalRequest = true, cost = Int.MAX_VALUE))
        assertEquals(before, budget.snapshot())
    }

    @Test fun validCheckpointRestoresEveryCounterAndReservation() {
        val original = ResearchBudget(100)
        assertTrue(original.tryCharge(ResearchBudget.Phase.DISCOVERY, physicalRequest = true, cost = 12))
        assertTrue(original.tryCharge(ResearchBudget.Phase.VERIFICATION, physicalRequest = false, cost = 3))
        val restored = ResearchBudget(100)
        restored.restore(original.snapshot())
        assertEquals(original.snapshot(), restored.snapshot())
        assertTrue(restored.tryCharge(ResearchBudget.Phase.DISCOVERY, physicalRequest = true, cost = 53))
        assertFalse(restored.tryCharge(ResearchBudget.Phase.DISCOVERY, physicalRequest = true))
    }

    @Test fun inconsistentCheckpointCannotRefundSpentCalls() {
        val budget = ResearchBudget(100)
        assertTrue(budget.tryCharge(ResearchBudget.Phase.DISCOVERY, physicalRequest = true, cost = 12))
        val before = budget.snapshot()
        val corrupt = before.copy(remainingByPhase = ResearchBudget(100).snapshot().remainingByPhase)
        assertTrue(runCatching { budget.restore(corrupt) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(before, budget.snapshot())
    }

    @Test fun invalidCheckpointFieldsLeaveLiveBudgetUnchanged() {
        val budget = ResearchBudget(100)
        assertTrue(budget.tryCharge(ResearchBudget.Phase.DISCOVERY, physicalRequest = true))
        val before = budget.snapshot()
        val invalid = listOf(
            before.copy(total = 101),
            before.copy(usedLogical = -1),
            before.copy(remaining = before.remaining + 1),
            before.copy(physicalRequests = -1),
            before.copy(physicalRequests = before.usedLogical + 1),
            before.copy(remainingByPhase = before.remainingByPhase - ResearchBudget.Phase.RECOVERY),
            before.copy(remainingByPhase = before.remainingByPhase + (ResearchBudget.Phase.RECOVERY to -1)),
            before.copy(remainingByPhase = before.remainingByPhase + (ResearchBudget.Phase.FINALIZATION to Int.MAX_VALUE))
        )
        invalid.forEach { snapshot ->
            assertTrue(runCatching { budget.restore(snapshot) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(before, budget.snapshot())
        }
    }

    @Test fun failedCheckpointWriteRollsBackWholeBatch() {
        var reject = true
        val budget = ResearchBudget(100, persist = { if (reject) error("disk full") })
        val before = budget.snapshot()
        assertTrue(runCatching { budget.tryCharge(ResearchBudget.Phase.DISCOVERY, physicalRequest = true, cost = 5) }.isFailure)
        assertEquals(before, budget.snapshot())
        reject = false
        assertTrue(budget.tryCharge(ResearchBudget.Phase.DISCOVERY, physicalRequest = true, cost = 5))
        assertEquals(5, budget.snapshot().usedLogical)
        assertEquals(5, budget.snapshot().physicalRequests)
    }
}
