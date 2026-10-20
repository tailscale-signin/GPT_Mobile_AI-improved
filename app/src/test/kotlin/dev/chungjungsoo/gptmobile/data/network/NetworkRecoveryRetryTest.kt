package dev.chungjungsoo.gptmobile.data.network

import java.net.UnknownHostException
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkRecoveryRetryTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun dnsRetriesAllowNetworkRecoveryAndRemainBounded() = runTest {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val started = currentTime
        val result = runCatching {
            ResilientStreamingClient.executeWithRetry(
                config = ResilientStreamingClient.RetryConfig(maxAttempts = 3, initialDelayMs = 250, maxDelayMs = 1000, jitterRatio = 0.0),
                onRetry = { _, delay, _ -> delays += delay }
            ) {
                attempts++
                throw UnknownHostException("DNS temporarily unavailable")
            }
        }
        assertTrue(result.isFailure)
        assertEquals(3, attempts)
        assertEquals(listOf(1500L, 3000L), delays)
        assertEquals(4500L, currentTime - started)
    }

    @Test fun emittedPayloadPreventsRetryEvenForDnsFailure() = runTest {
        var attempts = 0
        runCatching {
            ResilientStreamingClient.executeWithRetry(shouldRetry = { false }) {
                attempts++
                throw UnknownHostException("DNS unavailable")
            }
        }
        assertEquals(1, attempts)
    }
}
