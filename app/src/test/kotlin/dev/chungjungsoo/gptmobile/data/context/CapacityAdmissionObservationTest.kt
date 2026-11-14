package dev.chungjungsoo.gptmobile.data.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CapacityAdmissionObservationTest {
    private val runtime = RuntimeFingerprint("profile", "route", "credential", "model", "model", "runtime", "template", "allocation")

    @Test fun reportedOverflowKeepsItsCapacitySourcesAndExplainsWhyOutputOnlyChangesCannotHelp() {
        val capacity = CapacitySnapshot(runtime, 5632, provenance = CapacityProvenance.VERIFIED_ROUTE, observedAtMillis = 1000)
        val observation = CapacityAdmissionObservation(capacity, routeContextTokens = 5632, configuredContextTokens = 32768, catalogContextTokens = 8192)
        val failure = assertThrows(RequestCapacityException::class.java) {
            RequestAdmission.prepare(
                "complete input retained",
                "primary",
                capacity,
                RequestMeasurement(10461, 2615, TokenMeasurementKind.UTF8_ESTIMATE),
                4096,
                "permission",
                capacityObservation = observation
            )
        }
        assertEquals("CONTEXT_CAPACITY_EXCEEDED", failure.code)
        assertEquals(10461L, failure.promptTokens)
        assertEquals(2615L, failure.safetyTokens)
        assertEquals(4096L, failure.outputTokens)
        assertEquals(5632L, failure.capacityTokens)
        assertSame(observation, failure.capacityObservation)
        assertTrue(failure.message.orEmpty().contains("Estimated input plus safety: 13076 tokens"))
        assertTrue(failure.message.orEmpty().contains("lowering the output limit alone cannot make this input fit"))
    }

    @Test fun outputOnlyOverflowReportsTheActualRemainingHeadroom() {
        val failure = assertThrows(RequestCapacityException::class.java) {
            RequestAdmission.prepare(
                "complete input retained",
                "primary",
                CapacitySnapshot(runtime, 5632),
                RequestMeasurement(4000, 1000, TokenMeasurementKind.UTF8_ESTIMATE),
                1000,
                "permission"
            )
        }
        assertTrue(failure.message.orEmpty().contains("remaining output headroom: 632 tokens"))
        assertEquals(CapacityProvenance.UNKNOWN, failure.capacityObservation?.capacity?.provenance)
        assertEquals(null, failure.capacityObservation?.routeContextTokens)
    }

    @Test fun observationsCannotReplaceTheCapacityActuallyCheckedOrChangeAdmittedInput() {
        val capacity = CapacitySnapshot(runtime, 8192, provenance = CapacityProvenance.DECLARED)
        val measurement = RequestMeasurement(100, 256, TokenMeasurementKind.UTF8_ESTIMATE)
        assertThrows(IllegalArgumentException::class.java) {
            RequestAdmission.prepare("payload", "primary", capacity, measurement, 512, "permission", capacityObservation = CapacityAdmissionObservation(capacity.copy(contextTokens = 16384)))
        }
        val admitted = RequestAdmission.prepare("complete input retained", "primary", capacity, measurement, 512, "permission", capacityObservation = CapacityAdmissionObservation(capacity, configuredContextTokens = 8192))
        assertEquals("complete input retained", admitted.dispatchPayload(runtime, "permission"))
        assertEquals(8192L, admitted.receipt.contextTokens)
        assertEquals(512L, admitted.receipt.outputTokens)
    }
}
