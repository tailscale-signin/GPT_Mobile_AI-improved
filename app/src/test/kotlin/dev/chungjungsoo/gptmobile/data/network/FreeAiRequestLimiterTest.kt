package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class FreeAiRequestLimiterTest {
    @Test
    fun `OVH quota expires after a rolling minute without blocking other providers`() {
        var now = 100_000L
        val limiter = FreeAiRequestLimiter { now }
        repeat(2) { limiter.reserve(FreeAiProvider.OVHCLOUD) }
        assertThrows(FreeAiRateLimitException::class.java) { limiter.reserve(FreeAiProvider.OVHCLOUD) }
        limiter.reserve(FreeAiProvider.KILO)
        now += 60_000
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

    @Test
    fun `canceling a stream releases the shared provider permit`() = runBlocking {
        val limiter = FreeAiRequestLimiter()
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
    }
}
