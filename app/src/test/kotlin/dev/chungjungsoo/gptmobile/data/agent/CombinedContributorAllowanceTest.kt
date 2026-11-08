package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedContributorAllowanceTest {
    private fun request(prompt: String = "Explain the result", expanded: Boolean = false) = CombinedPolicyResolver.accept(
        ConversationCombinedSettings(effort = 50, preservedFields = if (expanded) JsonObject(mapOf("synthesisVerbosity" to JsonPrimitive(100))) else JsonObject(emptyMap())),
        listOf("p"),
        mapOf("p" to "model"),
        prompt
    )
    private fun cap(name: String, value: Long) = CombinedCapRecord(name, value, CombinedCapOrigin.PROFILE_CONFIGURED, CombinedCapScope.INVOCATION, "Fixture", "p")

    @Test fun ordinaryUnqualifiedReasoningDoesNotInventAnExtraAllowance() {
        assertEquals(2048, CombinedPolicyResolver.resolve(request()).initialDraftOutputTokens)
    }

    @Test fun acceptedReasoningAndExpandedDetailGetAUsefulFirstSegment() {
        val policy = request(expanded = true)
        val plan = CombinedPolicyResolver.resolve(policy, caps = listOf(cap("profileReasoningExpected", 1)))
        assertEquals(6144, plan.initialDraftOutputTokens)
        assertEquals(6144L, plan.baselineByOwner.getValue("contributor:p").generatedTokens)
        assertEquals(policy.settings.continueLongAnswers, plan.requested.settings.continueLongAnswers)
    }

    @Test fun explicitScopeIncreasesPlanningWithoutExceedingProfileCeiling() {
        val plan = CombinedPolicyResolver.resolve(request("Provide 2000 words"), caps = listOf(cap("profileReasoningExpected", 1), cap("profileOutputTokens", 3000)))
        assertTrue(plan.initialDraftOutputTokens > 2048)
        assertEquals(3000L, plan.baselineByOwner.getValue("contributor:p").generatedTokens)
    }

    @Test fun acceptedTurnHardLimitStillBlocksUnfundedLargerSegments() {
        val plan = CombinedPolicyResolver.resolve(request(expanded = true), hardLimits = CombinedHardLimits(generatedTokens = 512), caps = listOf(cap("profileReasoningExpected", 1)))
        assertTrue("p" in plan.blockedParticipantUids)
        assertTrue(plan.finalizationReserve.generatedTokens <= 512)
        assertTrue(plan.baselineByOwner.values.sumOf { it.generatedTokens } + plan.finalizationReserve.generatedTokens <= 512)
    }
}
