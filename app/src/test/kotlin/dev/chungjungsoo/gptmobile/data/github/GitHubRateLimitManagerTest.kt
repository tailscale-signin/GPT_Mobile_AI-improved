package dev.chungjungsoo.gptmobile.data.github

import io.ktor.http.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRateLimitManagerTest {
    @Test fun cooldownLongerThanOneDayCannotExpireEarlyOrOverflow() {
        val limits = GitHubRateLimitManager()
        limits.cooldown(172_800, 1000)
        assertEquals(86_400_000L, limits.retryDelayMillis(86_401))
        assertTrue(limits.shouldBackOff(86_401))
        assertThrows(IllegalStateException::class.java) { limits.requireAvailable(nowEpochSeconds = 86_401) }
        assertEquals(0L, limits.retryDelayMillis(172_801))
        limits.cooldown(Long.MAX_VALUE, 10_000)
        assertTrue(limits.retryDelayMillis(10) > 0)
        assertTrue(limits.shouldBackOff(10))
    }

    @Test fun datesUseTheSameClockForParsingAndObservation() {
        val limits = GitHubRateLimitManager()
        limits.record(headersOf("Retry-After", "Thu, 1 Jan 1970 00:00:10 GMT"), 429, 1000)
        assertEquals(9000L, limits.retryDelayMillis(1))
        assertEquals(1000L, limits.snapshot().observedAtMillis)
        assertEquals(0L, limits.retryDelayMillis(10))
        assertFalse(limits.shouldBackOff(10))
    }

    @Test fun quotaResetWithUntrustedLargeSecondsDoesNotBecomeAnImmediateRetry() {
        val limits = GitHubRateLimitManager()
        limits.record(headersOf("X-RateLimit-Remaining" to listOf("0"), "X-RateLimit-Reset" to listOf(Long.MAX_VALUE.toString())), nowMillis = 1000)
        assertTrue(limits.retryDelayMillis(1) > 0)
        assertTrue(limits.shouldBackOff(1))
    }

    @Test fun invalidRetryAfterUsesTheBounded429Default() {
        val limits = GitHubRateLimitManager()
        limits.record(headersOf("Retry-After", "-5"), 429, 1000)
        assertEquals(60_000L, limits.retryDelayMillis(1))
        assertEquals(0L, limits.retryDelayMillis(61))
    }
}
