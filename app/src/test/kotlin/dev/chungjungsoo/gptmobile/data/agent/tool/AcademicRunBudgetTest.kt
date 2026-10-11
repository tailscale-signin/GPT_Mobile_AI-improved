package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicRunBudgetTest {
    @Test fun concurrentFanoutReservesPhysicalCallsAtomically() = runTest {
        val budget = AcademicRunBudget(limit = 6)
        val outcomes = (1..4).map { async { runCatching { budget.reserve("search_papers", 3) }.isSuccess } }.awaitAll()
        assertEquals(2, outcomes.count { it })
        assertEquals(0, budget.remaining())
    }

    @Test fun quickModeNeverReadsAndOutputLimitIsShared() = runTest {
        val budget = AcademicRunBudget(maxReads = 0)
        assertTrue(runCatching { budget.reserve("read_paper", 2) }.isFailure)
        budget.chargeBytes(250 * 1024)
        assertTrue(runCatching { budget.chargeBytes(7 * 1024) }.isFailure)
        assertTrue(runCatching { budget.reserve("search_papers") }.isFailure)
    }
}
