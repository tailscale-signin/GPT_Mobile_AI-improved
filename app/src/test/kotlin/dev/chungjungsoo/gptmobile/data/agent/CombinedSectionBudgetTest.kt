package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedSectionBudgetTest {
    @Test fun uniqueClaimDensityChangesAllocationWithoutChangingTheUserTotal() {
        val result = CombinedSectionBudget.allocate(200, listOf(1, 8, 1))
        assertEquals(200, result.sum())
        assertTrue(result[1] > result[0] * 2)
        assertTrue(result.all { it >= 12 })
    }

    @Test fun smallAndExtremeBudgetsNeverOverflowOrLoseWords() {
        for (total in listOf(0, 1, 2, 17, 200, Int.MAX_VALUE)) {
            val result = CombinedSectionBudget.allocate(total, listOf(Int.MAX_VALUE, 0, 1, 7))
            assertEquals(total.toLong(), result.sumOf { it.toLong() })
            assertTrue(result.all { it >= 0 })
        }
    }

    @Test fun equivalentDensityIsFairAndDeterministic() {
        assertEquals(listOf(34, 34, 33), CombinedSectionBudget.allocate(101, listOf(0, 0, 0)))
        assertTrue(CombinedSectionBudget.allocate(200, emptyList()).isEmpty())
    }
}
