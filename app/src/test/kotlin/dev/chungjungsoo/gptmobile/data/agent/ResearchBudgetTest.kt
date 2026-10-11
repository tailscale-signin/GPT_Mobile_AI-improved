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

}
