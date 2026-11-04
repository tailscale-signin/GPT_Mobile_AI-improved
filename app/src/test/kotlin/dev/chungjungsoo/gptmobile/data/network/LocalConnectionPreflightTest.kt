package dev.chungjungsoo.gptmobile.data.network

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalConnectionPreflightTest {
    private fun unavailable(wait: Long = 2_000) = LocalServiceUnavailableException(ConnectionHealth.CONNECT_TIMEOUT, "100.64.0.2:8080", wait)

    @Test fun cooldownIsHonoredAndTransientFailureRecoversBeforeOneInferenceDispatch() = runTest {
        var probes = 0
        var dispatches = 0
        val waits = mutableListOf<Long>()
        LocalConnectionPreflight.recover(
            probe = { if (++probes < 3) throw unavailable() },
            await = { waits += it }
        )
        dispatches++
        assertEquals(3, probes)
        assertEquals(listOf(2_000L, 2_000L), waits)
        assertEquals(1, dispatches)
    }

    @Test fun persistentFailureStopsAfterBoundedChecksWithoutInference() = runTest {
        var probes = 0
        var dispatches = 0
        val failure = try {
            LocalConnectionPreflight.recover(probe = {
                probes++
                throw unavailable()
            }, await = {})
            dispatches++
            null
        } catch (failure: LocalServiceUnavailableException) {
            failure
        }
        assertEquals(3, probes)
        assertEquals(0, dispatches)
        assertEquals(3, failure?.probeAttempts)
        assertTrue(failure?.message.orEmpty().contains("HTTP/model health unverified"))
        assertEquals(ConnectionHealth.CONNECT_TIMEOUT, LocalServiceHealth.classify(requireNotNull(failure)))
    }

    @Test fun longCooldownAndWorkerDeadlineDoNotTriggerUnboundedWaits() = runTest {
        for (remaining in listOf(1_000L, Long.MAX_VALUE)) {
            var probes = 0
            var waits = 0
            try {
                LocalConnectionPreflight.recover(
                    probe = {
                        probes++
                        throw unavailable(32_000)
                    },
                    await = { waits++ },
                    remainingMillis = { remaining }
                )
            } catch (_: LocalServiceUnavailableException) {
                // The recoverable failure retains its retry-after instead of clearing the circuit breaker.
            }
            assertEquals(1, probes)
            assertEquals(0, waits)
        }
    }

    @Test fun classificationRetainsLocalDiagnosticsAndDoesNotScheduleAnotherAutomaticModelRetry() {
        val failure = unavailable(4_000).afterAttempts(3)
        val classified = dev.chungjungsoo.gptmobile.data.network.error.ErrorClassification.classify(IOException("wrapped", failure))
        assertEquals(ConnectionHealth.CONNECT_TIMEOUT, classified.connectionHealth)
        assertEquals(3, classified.probeAttempts)
        assertEquals("LOCAL_SERVICE_UNREACHABLE", classified.errorCode)
        assertEquals(4_000L, classified.suggestedBackoffMs)
        assertTrue(!classified.isRetryable)
        val unknown = dev.chungjungsoo.gptmobile.data.network.error.ErrorClassification.classify(IOException("GATEWAY_OUTCOME_UNKNOWN: connection timed out"))
        assertEquals(dev.chungjungsoo.gptmobile.data.network.error.ErrorCategory.REMOTE_OUTCOME_UNKNOWN, unknown.category)
        assertTrue(!unknown.isRetryable)
        assertEquals("GATEWAY_OUTCOME_UNKNOWN", unknown.errorCode)
    }

    @Test fun cancellationAndNonConnectivityFailuresAreNotRetried() = runTest {
        for (failure in listOf(CancellationException("cancelled"), IOException("HTTP 401 unauthorized"))) {
            var probes = 0
            var waits = 0
            val caught = try {
                LocalConnectionPreflight.recover(probe = {
                    probes++
                    throw failure
                }, await = { waits++ })
                null
            } catch (caught: Exception) {
                caught
            }
            assertTrue(caught === failure)
            assertEquals(1, probes)
            assertEquals(0, waits)
        }
    }

    @Test fun serializedLocalFailureIsNotReclassifiedAsAnAutomaticTimeoutRetry() {
        val failure = unavailable().afterAttempts(3)
        val restored = dev.chungjungsoo.gptmobile.data.network.error.ErrorClassification.classify(IOException(failure.message))
        assertEquals(dev.chungjungsoo.gptmobile.data.network.error.ErrorCategory.NETWORK_DISCONNECTED, restored.category)
        assertEquals(false, restored.isRetryable)
        assertEquals("LOCAL_SERVICE_UNREACHABLE", restored.errorCode)
        assertEquals(ConnectionHealth.CONNECT_TIMEOUT, LocalServiceHealth.classify(IOException("wrapper", failure)))
    }
}
