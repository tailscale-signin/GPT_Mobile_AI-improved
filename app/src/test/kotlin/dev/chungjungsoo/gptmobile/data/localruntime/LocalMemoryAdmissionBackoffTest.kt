package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMemoryAdmissionBackoffTest {
    @Test fun repeatedDeficitsPauseButSufficientHeadroomResumesImmediately() {
        var now = 100L
        val gate = LocalMemoryAdmissionBackoff { now }
        gate.remember("cpu-model", LocalMemoryEstimate(1000, 100, 900, 512))
        now += 1_000
        val failure = gate.rejection("cpu-model", 1050)
        assertNotNull(failure)
        assertEquals(50L, failure?.receipt?.shortfallBytes)
        assertTrue(failure?.message.orEmpty().contains("paused for 29s"))
        assertNull(gate.rejection("cpu-model", 1100))
        assertNull(gate.rejection("cpu-model", 900))
    }

    @Test fun newBackendReserveOrContextAndExpiryAllowFreshInspection() {
        var now = 0L
        val gate = LocalMemoryAdmissionBackoff { now }
        val estimate = LocalMemoryEstimate(1000, 100, 900, 512)
        for (changed in listOf("gpu", "reserve-768", "context-1024")) {
            gate.remember("cpu", estimate)
            assertNull(gate.rejection(changed, 900))
        }
        gate.remember("cpu", estimate)
        assertNull(gate.rejection("cpu", null))
        assertNotNull(gate.rejection("cpu", 900))
        now = 30_000
        assertNull(gate.rejection("cpu", 900))
    }
}
