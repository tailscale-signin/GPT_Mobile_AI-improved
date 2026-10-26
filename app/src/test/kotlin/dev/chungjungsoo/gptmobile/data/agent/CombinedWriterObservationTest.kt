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

    @Test fun disabledReasoningThatExceedsReportedAllowanceMakesRouteIneligible() {
        val preferred = PlatformV2(name = "Preferred", uid = "preferred", compatibleType = ClientType.CUSTOM, apiUrl = "https://preferred.example/v1", model = "reasoner", enabled = true, maxTokens = 16000)
        val alternative = preferred.copy(name = "Alternative", uid = "alternative", apiUrl = "https://alternative.example/v1", model = "writer")
        val routeKey = CombinedWriterObservations.key(preferred)
        val resolved = CombinedWriterObservations.recordRoute(
            routeKey,
            model = "reasoner",
            backend = "hosted",
            control = "reasoning.enabled=false",
            reasoning = false,
            disableRequested = true,
            transmitsDisable = true
        )

        // Reproduces the device trace: reasoning is disabled, but the provider reports
        // 1,444 reasoning tokens against a 723-token output allowance.
        CombinedWriterObservations.recordUsage(resolved, reasoningTokens = 1444, finishReason = "length", outputAllowance = 723)

        assertTrue(CombinedWriterPolicy.unavailableReason(preferred, "Explain gravity in 500 words")!!.contains("ignored the request to disable reasoning"))
        assertEquals(alternative.uid, CombinedWriterPolicy.rank(listOf(preferred, alternative), "Explain gravity in 500 words", preferred.uid).first().uid)
    }
}
