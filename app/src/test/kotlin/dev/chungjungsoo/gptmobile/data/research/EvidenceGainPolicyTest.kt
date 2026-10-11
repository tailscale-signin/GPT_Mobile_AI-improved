package dev.chungjungsoo.gptmobile.data.research

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceGainPolicyTest {
    @Test fun repeatedIdentitiesDoNotCountAsNewEvidence() {
        val policy = EvidenceGainPolicy(initialSources = setOf("doi:one"))
        assertFalse(policy.shouldStop(setOf("doi:one"), emptySet()))
        assertTrue(policy.shouldStop(setOf("doi:one"), emptySet()))
    }

    @Test fun supportedClaimGainResetsStagnationEvenWithoutANewSource() {
        val policy = EvidenceGainPolicy(initialSources = setOf("doi:one"))
        assertFalse(policy.shouldStop(setOf("doi:one"), emptySet()))
        assertFalse(policy.shouldStop(setOf("doi:one"), setOf("supported:one")))
        assertFalse(policy.shouldStop(setOf("doi:one"), setOf("supported:one")))
        assertTrue(policy.shouldStop(setOf("doi:one"), setOf("supported:one")))
    }

    @Test fun disappearingThenReappearingSourceCannotResetStagnation() {
        val policy = EvidenceGainPolicy(initialSources = setOf("doi:one"))
        assertFalse(policy.shouldStop(emptySet(), emptySet()))
        assertTrue(policy.shouldStop(setOf("doi:one"), emptySet()))
    }
}
