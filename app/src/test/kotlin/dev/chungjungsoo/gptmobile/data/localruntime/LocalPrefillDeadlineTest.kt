package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPrefillDeadlineTest {
    @Test fun shortPromptsStopWaitingAfter75Seconds() {
        assertEquals(75_000L, LocalPrefillDeadline.budgetMs(2))
        assertEquals(1L, LocalPrefillDeadline.remainingMs(0L, 74_999L, 2))
        assertEquals(0L, LocalPrefillDeadline.remainingMs(0L, 75_000L, 2))
    }

    @Test fun longPromptsHaveMoreTimeWithoutAnUnboundedWait() {
        assertEquals(83_000L, LocalPrefillDeadline.budgetMs(512))
        assertEquals(300_000L, LocalPrefillDeadline.budgetMs(Int.MAX_VALUE))
        assertEquals(75_000L, LocalPrefillDeadline.budgetMs(-10))
    }

    @Test fun monotonicClockRollbackNeverExtendsBudget() {
        assertEquals(75_000L, LocalPrefillDeadline.remainingMs(12_000L, 11_000L, 2))
    }
}
