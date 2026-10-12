package dev.chungjungsoo.gptmobile.data.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ContextCostBreakdownTest {
    @Test fun categoriesSumWithoutOverflow() {
        assertEquals(15, ContextCostBreakdown(1, 2, 3, 4, 5).total)
        assertEquals(Int.MAX_VALUE, ContextCostBreakdown(Int.MAX_VALUE, 2, 3, 4, 5).total)
        assertFalse(ContextCostBreakdown(1, 2, 3, 4, 5).diagnostic().contains("message text"))
    }
}
