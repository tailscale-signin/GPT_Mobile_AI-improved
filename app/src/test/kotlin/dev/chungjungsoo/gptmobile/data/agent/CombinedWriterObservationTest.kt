package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedWriterObservationTest {
    @After fun clear() = CombinedWriterObservations.clearForTest()

    @Test fun reasoningIntentTransmissionSupportAndBehaviorRemainSeparateForEachResolvedRoute() {
        val key = "free/automatic"
        val ignored = CombinedWriterObservations.recordRoute(key, "reasoner", "route-a", "reasoning.enabled=false", true)
        CombinedWriterObservations.recordUsage(ignored, 2500)
        val first = requireNotNull(CombinedWriterObservations.get(ignored))
        assertTrue(first.disableRequested == true)
        assertTrue(first.transmitsDisable == true)
        assertEquals("UNVERIFIED", first.providerControlSupport)
        assertTrue(first.reasoningObserved == true)
        assertEquals("NOT_HONORED", first.reasoningDisabled)
        val effort = CombinedWriterObservations.recordRoute(key, "other-reasoner", "route-b", "reasoning_effort=low", true, transmitsDisable = false, providerControlSupport = "EFFORT_ONLY")
        val second = requireNotNull(CombinedWriterObservations.get(effort))
        assertFalse(second.transmitsDisable == true)
        assertEquals("DISABLE_UNVERIFIED", second.reasoningDisabled)
        assertEquals(0, second.reasoningTokensHighWater)
        assertEquals(2500, CombinedWriterObservations.get(ignored)?.reasoningTokensHighWater)
    }

    @Test fun routeIgnoringDisableRanksBehindEligibleAlternativeAndKeepsReasoningReserve() {
        val reasoner = PlatformV2(name = "Preferred", uid = "reasoner", compatibleType = ClientType.CUSTOM, apiUrl = "https://fixture.invalid", model = "auto", maxTokens = 16000)
        val alternative = reasoner.copy(name = "Alternative", uid = "answer", model = "writer")
        val key = CombinedWriterObservations.key(reasoner)
        CombinedWriterObservations.recordRoute(key, "resolved-reasoner", "backend", "reasoning.enabled=false", true)
        CombinedWriterObservations.recordUsage(key, 2500)
        val ranked = CombinedWriterPolicy.rank(listOf(reasoner, alternative), "Explain gravity in 500 words", reasoner.uid)
        assertEquals(alternative.uid, ranked.first().uid)
        assertTrue(CombinedWriterPolicy.requiredOutput("Explain gravity in 500 words", reasoner) > 500 * 2.2 + 512 + 2500)
    }
}
