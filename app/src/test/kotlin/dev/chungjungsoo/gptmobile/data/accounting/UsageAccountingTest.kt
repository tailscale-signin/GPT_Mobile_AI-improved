package dev.chungjungsoo.gptmobile.data.accounting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageAccountingTest {
    @Test fun repeatedCumulativeSnapshotsAreNotSummed() {
        assertEquals(120, mergeUsageCount(120, 120, true))
        assertEquals(120, mergeUsageCount(120, 40, true))
        assertEquals(150, mergeUsageCount(120, 150, true))
    }

    @Test fun deltasSaturateAndInvalidCountersDoNotOverwriteUsage() {
        assertEquals(150, mergeUsageCount(120, 30, false))
        assertEquals(Int.MAX_VALUE, mergeUsageCount(Int.MAX_VALUE - 2, 9, false))
        assertEquals(120, mergeUsageCount(120, -1, true))
        assertNull(mergeUsageCount(null, -1, false))
    }
}
