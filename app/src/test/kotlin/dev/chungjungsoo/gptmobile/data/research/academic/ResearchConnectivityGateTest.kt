package dev.chungjungsoo.gptmobile.data.research.academic

import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchConnectivityGateTest {
    @Test fun distinctDnsFailuresOpenOneRecoveryWindow() {
        var now = 0L
        val gate = ResearchConnectivityGate { now }
        gate.failed("a", UnknownHostException())
        assertTrue(gate.available())
        gate.failed("a", UnknownHostException())
        assertTrue(gate.available())
        gate.failed("b", IllegalStateException("wrapped", UnknownHostException()))
        assertFalse(gate.available())
        now = 15_000
        assertTrue(gate.available())
    }

    @Test fun siteErrorsDoNotBlockOtherProvidersAndSuccessClearsOutage() {
        val gate = ResearchConnectivityGate { 0 }
        gate.failed("a", IllegalStateException("403"))
        gate.failed("b", IllegalStateException("timeout"))
        assertTrue(gate.available())
        gate.failed("a", UnknownHostException())
        gate.failed("b", UnknownHostException())
        assertFalse(gate.available())
        gate.success()
        assertTrue(gate.available())
    }
}
