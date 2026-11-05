package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.research.academic.ResearchConnectivityGate
import java.net.UnknownHostException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConnectivityPreflightTest {
    @org.junit.Before fun reset() = ResearchConnectivityGate.shared.reset()

    @org.junit.After fun clear() = ResearchConnectivityGate.shared.reset()

    @Test fun disconnectedDeviceRejectsPublicDispatchButDoesNotInferALocalServerIsOffline() = runTest {
        var calls = 0
        val failure = runCatching {
            ProviderConnectivityPreflight.withAvailability("https://api.example/v1", "DISCONNECTED") { calls++ }
        }.exceptionOrNull()
        assertTrue(failure is ProviderConnectivityPendingException)
        assertFalse(ResilientStreamingClient.isRetryable(requireNotNull(failure)))
        assertEquals(0, calls)
        ProviderConnectivityPreflight.withAvailability("http://127.0.0.1:8101", "DISCONNECTED") { calls++ }
        assertEquals(1, calls)
    }

    @Test fun correlatedDnsFailureWaitsForRecoveryWhileAHealthyProviderCanContinue() = runTest {
        ProviderConnectivityPreflight.responseReceived("https://healthy.example/v1")
        ProviderConnectivityPreflight.failed("https://first.example/v1", UnknownHostException())
        ProviderConnectivityPreflight.failed("https://second.example/v1", java.nio.channels.UnresolvedAddressException())
        var calls = 0
        val failure = runCatching {
            ProviderConnectivityPreflight.withAvailability("https://third.example/v1", "VALIDATED") { calls++ }
        }.exceptionOrNull()
        assertTrue(failure is ProviderConnectivityPendingException)
        assertTrue((failure as ProviderConnectivityPendingException).dnsRecovery)
        assertFalse(ResilientStreamingClient.isRetryable(failure))
        ProviderConnectivityPreflight.withAvailability("https://healthy.example/v1", "VALIDATED") { calls++ }
        assertEquals(1, calls)
        ResearchConnectivityGate.shared.reset()
        ProviderConnectivityPreflight.withAvailability("https://third.example/v1", "VALIDATED") { calls++ }
        assertEquals(2, calls)
    }
}
