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
}
