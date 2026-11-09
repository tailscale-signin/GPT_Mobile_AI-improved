package dev.chungjungsoo.gptmobile.data.network

import java.io.EOFException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalEndpointConnectionPolicyTest {
    @Test fun socketAbortAndItsSingleRetryShareOneReadIdentityWhileLaterPollsStayDistinct() = runTest {
        val attempts = mutableListOf<LocalMetadataReadAttempt>()
        val result = LocalEndpointConnectionPolicy.probe("http://127.0.0.1:29780/ready") {
            attempts += requireNotNull(kotlinx.coroutines.currentCoroutineContext()[LocalMetadataReadAttempt])
            if (attempts.size == 1) throw java.net.SocketException("Software caused connection abort")
            "ready"
        }
        assertEquals("ready", result)
        assertEquals(listOf(0, 1), attempts.map { it.ordinal })
        assertEquals(attempts[0].readId, attempts[1].readId)
        assertTrue(attempts[0].readId.matches(Regex("[a-f0-9]{32}")))
        val next = LocalEndpointConnectionPolicy.probe("http://127.0.0.1:29780/ready") {
            requireNotNull(kotlinx.coroutines.currentCoroutineContext()[LocalMetadataReadAttempt]).readId
        }
        assertFalse(attempts[0].readId == next)
    }

    @Test fun callerThreadNeverClosesStaleSocketsAndRetryReturnsToItsOriginalContext() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "fixture-ui-main") }
        executor.asCoroutineDispatcher().use { main ->
            runBlocking(main) {
                val caller = Thread.currentThread()
                var cleanup: Thread? = null
                var attempts = 0
                val result = LocalEndpointConnectionPolicy.probe("http://127.0.0.1:29819/metadata", { cleanup = Thread.currentThread() }) {
                    assertEquals(caller, Thread.currentThread())
                    if (++attempts == 1) throw EOFException()
                    "observed"
                }
                assertEquals("observed", result)
                assertEquals(2, attempts)
                assertTrue(cleanup != null && cleanup !== caller)
            }
        }
    }

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
            assertFailurePreserved(failure, result.exceptionOrNull())
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
            assertFailurePreserved(failure, observed)
            assertEquals(if (failure is EOFException) 2 else 1, attempts)
        }
    }

    private fun assertFailurePreserved(expected: Throwable, observed: Throwable?) {
        assertEquals(expected.javaClass, observed?.javaClass)
        assertEquals(expected.message, observed?.message)
        // Coroutine stacktrace recovery may copy a failure across withContext,
        // preserving the original as its cause instead of its object identity.
        assertTrue(
            "The propagated failure must retain its original cause",
            generateSequence(observed) { it.cause?.takeUnless { cause -> cause === it } }.take(8).any { it === expected }
        )
    }
}
