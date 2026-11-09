package dev.chungjungsoo.gptmobile.data.network

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProviderContinuationConnectivityTest {
    @Test fun availableButUnvalidatedNetworkWaitsForValidationOrTheSameService() = runTest {
        val gate = ProviderContinuationConnectivity { testScheduler.currentTime }
        gate.networkChanged("wifi-1", "UNVALIDATED")
        val recovery = async { gate.awaitUsable("provider.example", 3000) }
        runCurrent()
        gate.responseReceived("different.example")
        runCurrent()
        assertFalse(recovery.isCompleted)
        gate.responseReceived("provider.example")
        runCurrent()
        assertTrue(recovery.await())
    }

    @Test fun changingNetworksInvalidatesOldServiceSuccessAndOfflineWaitIsBounded() = runTest {
        val gate = ProviderContinuationConnectivity { testScheduler.currentTime }
        gate.networkChanged("wifi-1", "UNVALIDATED")
        gate.responseReceived("provider.example")
        assertTrue(gate.awaitUsable("provider.example", 0))
        gate.networkChanged("wifi-2", "UNVALIDATED")
        assertFalse(gate.awaitUsable("provider.example", 0))
        val recovery = async { gate.awaitUsable("provider.example", 3000) }
        runCurrent()
        advanceTimeBy(3000)
        runCurrent()
        assertFalse(recovery.await())
    }

    @Test fun validatedNetworkAllowsOnePolicyControlledContinuationAndStaleResponsesExpire() = runTest {
        val gate = ProviderContinuationConnectivity { testScheduler.currentTime }
        gate.networkChanged("wifi-1", "UNVALIDATED")
        gate.responseReceived("provider.example")
        advanceTimeBy(30_001)
        assertFalse(gate.awaitUsable("provider.example", 0))
        gate.networkChanged("wifi-1", "VALIDATED")
        assertTrue(gate.awaitUsable("provider.example", 0))
        gate.networkChanged("none", "DISCONNECTED")
        gate.responseReceived("provider.example")
        assertFalse(gate.awaitUsable("provider.example", 0))
    }
}
