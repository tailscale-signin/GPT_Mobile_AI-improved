package dev.chungjungsoo.gptmobile.data.agent.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalContextReplanBudgetTest {
    @Test fun automaticReplansAreBoundedAndStrictlyDescending() {
        val budget = LocalContextReplanBudget()
        assertEquals(6144, budget.next(8192, 6144))
        assertEquals(4096, budget.next(6144, 4096))
        assertEquals(2048, budget.next(4096, 2048))
        assertNull(budget.next(2048, 1024))
        assertEquals(3, budget.attempts)
    }

    @Test fun invalidOrExpandedRecommendationsCannotRestartTheRequest() {
        val budget = LocalContextReplanBudget()
        assertNull(budget.next(8192, 8192))
        assertNull(budget.next(8192, 16384))
        assertNull(budget.next(8192, 0))
        assertEquals(0, budget.attempts)
        assertEquals(4096, budget.next(8192, 4096))
        assertNull(budget.next(8192, 2048))
        assertEquals(1, budget.attempts)
        assertEquals(1024, budget.next(2048, 1024))
    }
}
