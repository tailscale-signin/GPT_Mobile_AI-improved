package dev.chungjungsoo.gptmobile.data.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapacityReconciliationTest {
    private val runtime = RuntimeFingerprint("connection", "route", "credential", "model", "model", "runtime", "template", "allocation")

    @Test fun verifiedDiscoveryDoesNotBypassTheSmallerProfileOrTransportOutputCeiling() {
        val discovered = CapacitySnapshot(runtime, 131_072, outputTokens = 32_768, provenance = CapacityProvenance.VERIFIED_ROUTE)
        val effective = discovered.withCeilings(65_536, 4_096)
        assertEquals(65_536L, effective.contextTokens)
        assertEquals(4_096L, effective.outputTokens)
        assertEquals(discovered.fingerprint, effective.fingerprint)
        assertEquals(discovered.provenance, effective.provenance)
    }

    @Test fun absentOrLargerPolicyLimitsNeverWidenDiscoveredLimits() {
        val discovered = CapacitySnapshot(runtime, 4_096, outputTokens = 512)
        assertEquals(discovered, discovered.withCeilings(32_768, 8_192))
        assertEquals(discovered, discovered.withCeilings(null, null))
        val unknown = CapacitySnapshot(runtime, null)
        assertNull(unknown.withCeilings(null, null).outputTokens)
        assertEquals(512L, unknown.withCeilings(null, 512).outputTokens)
    }
}
