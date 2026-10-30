package dev.chungjungsoo.gptmobile.data.research.academic

import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchConnectivityGateTest {
    @Test fun publicDnsOutageDoesNotSuppressLiteralLocalOrTailscaleEndpoints() {
        val gate = ResearchConnectivityGate { 0L }
        gate.failed("first.example", java.net.UnknownHostException())
        gate.failed("second.example", java.net.UnknownHostException())
        for (host in listOf("192.168.1.20", "100.80.0.2", "127.0.0.1", "localhost", "[::1]", "fd7a:115c:a1e0::1")) {
            assertTrue(gate.available(host))
            assertEquals(0L, gate.acquire(host))
        }
        assertFalse(gate.available("third.example"))
        assertEquals(null, gate.acquire("third.example"))
    }

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

    @Test fun healthyHostBypassesCorrelatedDnsLossWithoutClearingOtherFailedHosts() {
        var time = 1000L
        val gate = ResearchConnectivityGate { time }
        gate.success("healthy.example")
        gate.failed("failed-a.example", UnknownHostException())
        gate.failed("failed-b.example", UnknownHostException())
        assertTrue(gate.available("healthy.example"))
        assertFalse(gate.available("new.example"))
        org.junit.Assert.assertEquals(0L, gate.acquire("healthy.example"))
        gate.success("healthy.example")
        assertFalse(gate.available("failed-a.example"))
        time += 15000
        val probe = requireNotNull(gate.acquire("new.example"))
        assertTrue(probe > 0)
        org.junit.Assert.assertNull(gate.acquire("failed-a.example"))
        assertTrue(gate.available("healthy.example"))
        gate.success("new.example")
        gate.release(probe)
        assertFalse(gate.available("failed-a.example"))
        assertTrue(gate.available("new.example"))
        gate.reset()
        assertTrue(gate.available("failed-a.example"))
    }

    @Test fun abandonedProbeCannotStrandRecoveryAndFailureBackoffIsBounded() {
        var time = 0L
        val gate = ResearchConnectivityGate { time }
        gate.failed("a", UnknownHostException())
        gate.failed("b", UnknownHostException())
        time = 15000
        val probe = requireNotNull(gate.acquire("a"))
        gate.release(probe)
        org.junit.Assert.assertNotNull(gate.acquire("a"))
        time += 120001
        org.junit.Assert.assertNotNull(gate.acquire("a"))
        repeat(20) { gate.failed("b", UnknownHostException()) }
        assertFalse(gate.available("unknown"))
        time += 120001
        assertTrue(gate.available("unknown"))
    }
}
