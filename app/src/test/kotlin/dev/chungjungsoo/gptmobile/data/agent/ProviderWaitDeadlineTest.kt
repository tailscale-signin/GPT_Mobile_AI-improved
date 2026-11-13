package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ProviderWaitDeadlineTest {
    @Test fun aSharedExtensionChangesOnlyTheScheduleAndExpiryRestoresTheOriginalProjection() {
        val original = ProviderWaitDeadline("run", 7, 30_156)
        val extended = original.withSchedule(120_000)
        assertEquals(30_156L, extended.retryAt)
        assertEquals(120_000L, extended.scheduledRetryAt)
        assertEquals(120_000L, extended.effectiveRetryAt)
        assertSame(extended, extended.withSchedule(120_000))
        assertEquals(original, extended.withSchedule(null))
        assertEquals(original, extended.withSchedule(20_000))
    }

    @Test fun paidAndFreePreflightDeadlinesUseTheSameNonShorteningProjection() {
        assertEquals(120_000L, effectiveProviderRetryAt(30_156, sharedDeadline = 120_000))
        assertEquals(120_000L, effectiveProviderRetryAt(30_156, preflightDeadline = 120_000))
        assertEquals(180_000L, effectiveProviderRetryAt(30_156, 120_000, 180_000))
        assertEquals(30_156L, effectiveProviderRetryAt(30_156, 0, -1))
        val original = ProviderWaitDeadline("run", 7, 30_156)
        assertSame(original, original.withSchedule(20_000))
        assertNull(original.scheduledRetryAt)
    }

    @Test fun theProjectionCannotOverflowOrChangeTheSavedOwner() {
        val original = ProviderWaitDeadline("run", 7, 30_156)
        val extended = original.withSchedule(Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, extended.effectiveRetryAt)
        assertEquals(original.runId, extended.runId)
        assertEquals(original.fence, extended.fence)
        assertEquals(original.retryAt, extended.retryAt)
    }
}
