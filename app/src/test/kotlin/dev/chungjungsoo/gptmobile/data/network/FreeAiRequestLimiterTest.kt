package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class FreeAiRequestLimiterTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun exhaustedOvhAllowanceRemainsAnExplicitWaitDuringItsFinalSeconds() = kotlinx.coroutines.test.runTest {
        val limiter = FreeAiRequestLimiter { testScheduler.currentTime }
        repeat(2) { limiter.reserve(FreeAiProvider.OVHCLOUD, "model") }
        testScheduler.advanceTimeBy(55_000)
        var calls = 0
        val blocked = runCatching { limiter.withRequest(FreeAiProvider.OVHCLOUD, "model") { calls++ } }.exceptionOrNull()
        assertEquals(60_000L, (blocked as FreeAiRateLimitException).retryAt)
        assertEquals(55_000L, testScheduler.currentTime)
        assertEquals(0, calls)
        testScheduler.advanceTimeBy(5000)
        limiter.withRequest(FreeAiProvider.OVHCLOUD, "model") { calls++ }
        assertEquals(60_000L, testScheduler.currentTime)
        assertEquals(1, calls)
        limiter.defer(FreeAiProvider.OVHCLOUD, "5", "model")
        assertEquals(true, runCatching { limiter.withRequest(FreeAiProvider.OVHCLOUD, "model") { calls++ } }.exceptionOrNull() is FreeAiRateLimitException)
        assertEquals(1, calls)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `LLM7 rapid requests are spaced before reaching the provider`() = kotlinx.coroutines.test.runTest {
        val limiter = FreeAiRequestLimiter { testScheduler.currentTime }
        val starts = mutableListOf<Long>()
        repeat(3) { limiter.withRequest(FreeAiProvider.LLM7) { starts += testScheduler.currentTime } }
        assertEquals(listOf(0L, 1000L, 2000L), starts)
        limiter.defer(FreeAiProvider.LLM7, "60")
        var executed = false
        val failure = runCatching { limiter.withRequest(FreeAiProvider.LLM7) { executed = true } }.exceptionOrNull()
        org.junit.Assert.assertTrue(failure is FreeAiRateLimitException)
        assertFalse(executed)
    }

    @Test
    fun `OVH quota expires after a rolling minute without blocking other providers`() {
        var now = 100_000L
        val limiter = FreeAiRequestLimiter { now }
        repeat(2) { limiter.reserve(FreeAiProvider.OVHCLOUD) }
        assertEquals(now + 60_000L, limiter.cooldownUntilMillis(FreeAiProvider.OVHCLOUD))
        assertThrows(FreeAiRateLimitException::class.java) { limiter.reserve(FreeAiProvider.OVHCLOUD) }
        limiter.reserve(FreeAiProvider.KILO)
        now += 60_000
        assertEquals(null, limiter.cooldownUntilMillis(FreeAiProvider.OVHCLOUD))
        limiter.reserve(FreeAiProvider.OVHCLOUD)
    }

    @Test
    fun `Kilo hourly allowance is shared across requests`() {
        var now = 100_000L
        val limiter = FreeAiRequestLimiter { now }
        repeat(200) { limiter.reserve(FreeAiProvider.KILO) }
        assertThrows(FreeAiRateLimitException::class.java) { limiter.reserve(FreeAiProvider.KILO) }
        now += 3_600_000
        limiter.reserve(FreeAiProvider.KILO)
    }

    @Test
    fun `retry after is honored even when the local quota is available`() {
        var now = 100_000L
        val limiter = FreeAiRequestLimiter { now }
        assertEquals(90L, limiter.defer(FreeAiProvider.KILO, "90"))
        now += 89_000
        assertThrows(FreeAiRateLimitException::class.java) { limiter.reserve(FreeAiProvider.KILO) }
        now += 1_000
        limiter.reserve(FreeAiProvider.KILO)
        assertEquals(60L, limiter.defer(FreeAiProvider.POLLINATIONS, "invalid"))
    }

    @Test
    fun `LLM7 enforces its per second limit`() {
        var now = 100_000L
        val limiter = FreeAiRequestLimiter { now }
        limiter.reserve(FreeAiProvider.LLM7)
        assertThrows(FreeAiRateLimitException::class.java) { limiter.reserve(FreeAiProvider.LLM7) }
        now += 1_000
        limiter.reserve(FreeAiProvider.LLM7)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `canceling a Classic stream releases its permit and preserves completion spacing`() = kotlinx.coroutines.test.runTest {
        val limiter = FreeAiRequestLimiter { testScheduler.currentTime }
        val started = CompletableDeferred<Unit>()
        val first = async {
            limiter.withRequest(FreeAiProvider.POLLINATIONS) {
                started.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        started.await()
        val second = async { limiter.withRequest(FreeAiProvider.POLLINATIONS) { "next response" } }
        yield()
        assertFalse(second.isCompleted)
        first.cancelAndJoin()
        assertEquals("next response", second.await())
        assertEquals(30_000L, testScheduler.currentTime)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun classicSpacingStartsAfterTheResponseFinishes() = kotlinx.coroutines.test.runTest {
        val limiter = FreeAiRequestLimiter { testScheduler.currentTime }
        limiter.withRequest(FreeAiProvider.POLLINATIONS) { kotlinx.coroutines.delay(20_000) }
        limiter.withRequest(FreeAiProvider.POLLINATIONS) { assertEquals(50_000L, testScheduler.currentTime) }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun cancellingOrdinarySpacingDoesNotConsumeTheNextRequest() = kotlinx.coroutines.test.runTest {
        val limiter = FreeAiRequestLimiter { testScheduler.currentTime }
        limiter.withRequest(FreeAiProvider.LLM7) { }
        var calls = 0
        val waiting = async { limiter.withRequest(FreeAiProvider.LLM7) { calls++ } }
        testScheduler.runCurrent()
        assertFalse(waiting.isCompleted)
        waiting.cancelAndJoin()
        assertEquals(0, calls)
        limiter.withRequest(FreeAiProvider.LLM7) { calls++ }
        assertEquals(1000L, testScheduler.currentTime)
        assertEquals(1, calls)
    }
}
