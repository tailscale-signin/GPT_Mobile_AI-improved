package dev.chungjungsoo.gptmobile.data.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalReachabilityProbesTest {
    @Test fun sameServiceSharesAnInflightObservationAndOtherPortsRemainIndependent() = runTest {
        val probes = LocalReachabilityProbes { testScheduler.currentTime }
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val first = async {
            probes.probe("epoch1:host:8090") {
                calls++
                gate.await()
            }
        }
        runCurrent()
        val second = async { probes.probe("epoch1:host:8090") { calls++ } }
        val other = async { probes.probe("epoch1:host:8101") { calls++ } }
        runCurrent()
        assertTrue(other.await())
        assertEquals(2, calls)
        gate.complete(Unit)
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(2, calls)
    }

    @Test fun routeChangeRejectsOldObservationAndAllowsANewProbe() = runTest {
        val probes = LocalReachabilityProbes { testScheduler.currentTime }
        val gate = CompletableDeferred<Unit>()
        val old = async { probes.probe("epoch1:host:8090") { gate.await() } }
        runCurrent()
        probes.invalidate()
        assertTrue(probes.probe("epoch2:host:8090") {})
        gate.complete(Unit)
        assertFalse(old.await())
    }
}
