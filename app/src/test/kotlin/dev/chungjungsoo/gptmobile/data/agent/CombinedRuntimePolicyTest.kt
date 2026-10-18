package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedRuntimePolicyTest {
    @Test
    fun axesRemainIndependentAndAllTwentyFiveStopsResolve() {
        for (collection in 0..100 step 25) {
            var previousOutput = 0L
            for (effort in 0..100 step 25) {
                val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(collectionBias = collection, effort = effort), listOf("a", "b"), mapOf("a" to "model"), "Explain the comparison", acceptedAtMillis = 1)
                val plan = CombinedPolicyResolver.resolve(requested)
                assertEquals(collection, plan.requested.settings.collectionBias)
                assertEquals(effort, plan.requested.settings.effort)
                assertTrue(plan.optionalAllowance.generatedTokens > previousOutput)
                assertEquals(effort == 100, plan.renewable)
                assertEquals(2, plan.baselineByOwner.keys.count { it.startsWith("contributor:") })
                previousOutput = plan.optionalAllowance.generatedTokens
            }
        }
        assertEquals(5 to 1, CombinedPolicyResolver.evidenceWeights(0))
        assertEquals(1 to 5, CombinedPolicyResolver.evidenceWeights(100))
    }

    @Test
    fun explicitLengthOverridesEconomicalGuidanceWithoutChangingToolsOrGrants() {
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(effort = 0), listOf("a"), emptyMap(), "Write exactly 5000 words without web search", preferredOwnerUid = "a")
        val plan = CombinedPolicyResolver.resolve(requested, toolsAllowed = false)
        assertTrue(plan.finalOutputTokens >= 10000)
        assertEquals(0L, plan.optionalAllowance.toolOperations)
        assertFalse(plan.searchAllowed)
        assertEquals("a", requested.preferredOwnerUid)
        assertEquals(0, CombinedPolicyResolver.toolCallLimit(CombinedExecutionContext(requested, plan, "root"), 5000))
    }

    @Test
    fun totalCeilingProtectsFinalizationAndLabelsUnaffordableParticipants() {
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(), listOf("a", "b", "c"), emptyMap(), "Explain")
        val plan = CombinedPolicyResolver.resolve(requested, estimatedInputTokens = 1000, hardLimits = CombinedHardLimits(totalTokens = 10000))
        assertTrue(plan.baseline.inputTokens + plan.baseline.generatedTokens + plan.finalizationReserve.inputTokens + plan.finalizationReserve.generatedTokens <= 10000)
        assertTrue(plan.blockedParticipantUids.isNotEmpty())
        assertTrue(plan.finalizationReserve.generatedTokens > 0)
    }

    @Test
    fun fullScalePreservesExplicitProfileAndZeroLimits() {
        val requested = CombinedPolicyResolver.accept(ConversationCombinedSettings(effort = 100), listOf("a"), emptyMap(), "Compare the sources")
        val context = CombinedExecutionContext(requested, CombinedPolicyResolver.resolve(requested), "root")
        assertEquals(500, CombinedPolicyResolver.toolCallLimit(context, 500))
        assertEquals(50, CombinedPolicyResolver.toolCallLimit(context, 50))
        assertEquals(0, CombinedPolicyResolver.toolCallLimit(context, 500, 0))
        assertEquals(0, CombinedPolicyResolver.toolCallLimit(context.forRole(CombinedRunRole.SYNTHESIS), 500))
    }

    @Test(expected = IllegalArgumentException::class)
    fun futureSettingsDoNotPretendToApply() {
        CombinedPolicyResolver.accept(ConversationCombinedSettings(schemaVersion = 99), listOf("a"), emptyMap(), "Explain")
    }
}
