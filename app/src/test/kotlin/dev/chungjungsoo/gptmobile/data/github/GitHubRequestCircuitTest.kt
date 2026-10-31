package dev.chungjungsoo.gptmobile.data.github

import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRequestCircuitTest {
    @Test fun transientFailuresOpenTheCircuitAndOneProbeRecoversIt() {
        var time = 1000L
        val circuit = GitHubRequestCircuit(threshold = 2, cooldownMillis = 5000, now = { time })
        repeat(2) { circuit.failed(circuit.admit(), IOException("upstream unavailable")) }
        val blocked = runCatching { circuit.admit() }.exceptionOrNull()
        assertTrue(blocked is GitHubRequestCircuit.Open)
        assertEquals(5000L, (blocked as GitHubRequestCircuit.Open).retryAfterMs)
        time += 5000
        val probe = circuit.admit()
        assertTrue(probe.probe)
        assertTrue(runCatching { circuit.admit() }.exceptionOrNull() is GitHubRequestCircuit.Open)
        circuit.succeeded(probe)
        assertFalse(circuit.admit().probe)
        assertTrue(circuit.metrics().toString().contains("CLOSED"))
    }

    @Test fun staleConcurrentSuccessCannotReopenAnAlreadyTrippedCircuit() {
        val circuit = GitHubRequestCircuit(threshold = 1)
        val earlier = circuit.admit()
        val failing = circuit.admit()
        circuit.failed(failing, GitHubReadRetry.HttpFailure(503, null))
        circuit.succeeded(earlier)
        assertTrue(runCatching { circuit.admit() }.exceptionOrNull() is GitHubRequestCircuit.Open)
    }

    @Test fun authenticationTlsAndCancellationDoNotCountAsTransientFailures() {
        val circuit = GitHubRequestCircuit(threshold = 1)
        for (failure in listOf(IllegalStateException("HTTP 403"), SSLException("Untrusted certificate"), CancellationException())) {
            circuit.failed(circuit.admit(), failure)
            assertFalse(circuit.admit().probe)
        }
    }

    @Test fun cancelledProbeReleasesItsSlotAndFailedProbeRestartsCooldown() {
        var time = 10L
        val circuit = GitHubRequestCircuit(1, 5000, now = { time })
        circuit.failed(circuit.admit(), IOException())
        time += 5000
        circuit.failed(circuit.admit(), CancellationException())
        val replacement = circuit.admit()
        assertTrue(replacement.probe)
        circuit.failed(replacement, IOException())
        assertEquals(5000L, (runCatching { circuit.admit() }.exceptionOrNull() as GitHubRequestCircuit.Open).retryAfterMs)
    }
}
