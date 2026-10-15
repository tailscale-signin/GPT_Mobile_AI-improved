package dev.chungjungsoo.gptmobile.data.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveOutputAllowanceTest {
    private val measurement = RequestMeasurement(100, 256, TokenMeasurementKind.UTF8_ESTIMATE)

    @Test fun absentAndUnlimitedCapsGetPositiveAdaptiveDefaults() {
        for (requested in listOf(null, 0L, -1L)) {
            val decision = EffectiveOutputAllowance.resolve(requested, 131072, null, measurement)
            assertEquals(4096L, decision.tokens)
            assertEquals("adaptive_default", decision.source)
        }
        assertEquals(4096L, EffectiveOutputAllowance.resolve(null, null, null, measurement).tokens)
    }

    @Test fun routeCeilingAndContextHeadroomBoundExplicitWriting() {
        assertEquals(1000L, EffectiveOutputAllowance.resolve(16000, 1000, null, measurement).tokens)
        assertEquals(644L, EffectiveOutputAllowance.resolve(16000, null, 1000, measurement).tokens)
        assertEquals(16000L, EffectiveOutputAllowance.resolve(16000, null, 20000, measurement).tokens)
        assertTrue(runCatching { EffectiveOutputAllowance.resolve(null, null, 356, measurement) }.isFailure)
    }

    @Test fun finiteCapacityAcceptsResolvedUnlimitedWithoutDroppingInput() {
        val runtime = RuntimeFingerprint("profile", "route", "credential", "model", "model", "runtime", "template", "allocation")
        val capacity = CapacitySnapshot(runtime, 10000, outputTokens = 2048)
        val allowance = EffectiveOutputAllowance.resolve(0, capacity.outputTokens, capacity.contextTokens, measurement)
        val request = RequestAdmission.prepare("complete input", "primary", capacity, measurement, allowance.tokens, "consent")
        assertEquals("complete input", request.dispatchPayload(runtime, "consent"))
        assertEquals(2048L, request.receipt.outputTokens)
    }
}
