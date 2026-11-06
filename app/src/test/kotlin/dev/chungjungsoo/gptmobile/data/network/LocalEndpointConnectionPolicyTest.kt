package dev.chungjungsoo.gptmobile.data.network

import java.io.EOFException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalEndpointConnectionPolicyTest {
    @Test fun connectionFailuresRetryOnceWithBoundedJitterWithoutEvictingHealthySockets() = runTest {
        for (failure in listOf(ConnectException("refused"), SocketTimeoutException("Connect timed out"))) {
            var attempts = 0
            var evictions = 0
            val start = testScheduler.currentTime
            val result = LocalEndpointConnectionPolicy.probe("http://127.0.0.1:29775/props", { evictions++ }) {
                if (++attempts == 1) throw failure
                "ready"
            }
            assertEquals("ready", result)
            assertEquals(2, attempts)
            assertEquals(0, evictions)
            assertTrue(testScheduler.currentTime - start in 150L..450L)
        }
    }

    @Test fun onlyProvenPeerClosureEvictsTheIdlePoolAndDeterministicFailuresNeverRetry() = runTest {
        var evictions = 0
        var attempts = 0
        runCatching {
            LocalEndpointConnectionPolicy.probe("http://127.0.0.1:29776/props", { evictions++ }) {
                attempts++
                throw EOFException()
            }
        }
        assertEquals(2, attempts)
        assertEquals(1, evictions)
        for (failure in listOf(ProviderHttpFailure(404, null), ProviderHttpFailure(422, null), IllegalArgumentException("invalid request: broken pipe"), SSLException("unexpected end of stream"))) {
            attempts = 0
            val result = runCatching {
                LocalEndpointConnectionPolicy.probe("http://127.0.0.1:29776/props", { evictions++ }) {
                    attempts++
                    throw failure
                }
            }
            assertEquals(failure, result.exceptionOrNull())
            assertEquals(1, attempts)
            assertEquals(1, evictions)
        }
    }

    @Test fun metadataAndGenerationShareOneRouteRecoveryAfterEof() = runTest {
        var attempts = 0
        val value = LocalEndpointConnectionPolicy.probe("http://127.0.0.1:29761/props") {
            attempts++
            if (attempts == 1) throw EOFException("unexpected end of stream")
            assertTrue(LocalEndpointConnectionPolicy.freshConnection("http://127.0.0.1:29761/v1/chat/completions"))
            "observed"
        }
        assertEquals("observed", value)
        assertEquals(2, attempts)
        assertFalse(LocalEndpointConnectionPolicy.freshConnection("http://127.0.0.1:29762/apply-template"))
    }

    @Test fun probeRetriesAreBoundedAndTlsFailuresAreNotReplayed() = runTest {
        for (failure in listOf(EOFException("unexpected end of stream"), SSLException("Certificate failed"))) {
            var attempts = 0
            val observed = runCatching {
                LocalEndpointConnectionPolicy.probe("http://127.0.0.1:29763/props") {
                    attempts++
                    throw failure
                }
            }.exceptionOrNull()
            assertEquals(failure, observed)
            assertEquals(if (failure is EOFException) 2 else 1, attempts)
        }
    }
}
