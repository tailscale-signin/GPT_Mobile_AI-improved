package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedWriterPolicyTest {
    @After fun clearObservations() = CombinedWriterObservations.clearForTest()

    @Test fun capableAppendWritersReserveUsefulPacketsInsteadOfTheEligibilityMinimum() {
        val profile = writer(16_384)
        val request = "Explain the collected repository findings"
        val minimum = CombinedWriterPolicy.requiredOutput(request, profile, appendContinuations = true)
        val preferred = CombinedWriterPolicy.preferredOutput(request, profile, appendContinuations = true)
        assertTrue(minimum < 1024)
        assertTrue(preferred >= 4096)
        val requested = CombinedPolicyResolver.accept(dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings(continueLongAnswers = true), listOf(profile.uid), mapOf(profile.uid to profile.model), request, singlePass = true)
        val budget = CombinedPolicyResolver.resolve(requested, requiredOutputTokens = preferred)
        val plan = CombinedWriterRequestPlan.freeze(profile, request, "Frozen complete input", combinedWriterSystemPrompt(null, "PASSAGES_V3", request, true), emptyList(), "PASSAGES_V3", budget.finalOutputTokens, appendContinuations = true)
        assertEquals(preferred, plan.effectiveOutputTokens)
        assertEquals(minimum, plan.requiredOutputTokens)
        val capped = CombinedWriterRequestPlan.freeze(profile, request, "Frozen complete input", "System", emptyList(), "PASSAGES_V3", budget.finalOutputTokens, userOutputLimit = 1024, appendContinuations = true)
        assertEquals(1024, capped.effectiveOutputTokens)
    }

    @Test fun smallAppendRoutesStillUseTheirAcceptedCeiling() {
        for (cap in listOf(1024, 1500)) assertEquals(cap, CombinedWriterPolicy.preferredOutput("Explain the findings", writer(cap), appendContinuations = true))
    }

    @Test fun preferredPacketsRespectUserOutputAndContextWhileMinimumAdmissionStillApplies() {
        val profile = writer(16_384)
        assertEquals(1024, CombinedWriterPolicy.preferredOutput("Explain the findings", profile, true, userOutputLimit = 1024))
        assertEquals(1024, CombinedWriterPolicy.preferredOutput("Explain the findings", profile, true, userContextLimit = 4096))
        val minimum = CombinedWriterPolicy.requiredOutput("Explain the findings", profile, true)
        assertEquals(minimum, CombinedWriterPolicy.preferredOutput("Explain the findings", profile, true, userOutputLimit = 128))
        assertTrue(runCatching { CombinedWriterRequestPlan.freeze(profile, "Explain the findings", "Frozen input", "System", emptyList(), "PASSAGES_V3", minimum, userOutputLimit = 128, appendContinuations = true) }.isFailure)
    }

    @Test fun shortPacketsAllowLargeFinalAnswersOn1024And1500TokenRoutes() {
        listOf(1024, 1500).forEach { cap ->
            val profile = writer(cap)
            assertNotNull(CombinedWriterPolicy.unavailableReason(profile, "Write the history of Portugal in 5000 words"))
            assertNull(CombinedWriterPolicy.unavailableReason(profile, "Write the history of Portugal in 5000 words", appendContinuations = true))
            assertTrue(CombinedWriterPolicy.requiredOutput("Write the history of Portugal in 5000 words", profile, appendContinuations = true) <= cap)
        }
    }

    @Test fun packetModeDoesNotIgnoreObservedReasoningOrStructuredFramingCapacity() {
        val small = writer(256)
        assertNotNull(CombinedWriterPolicy.unavailableReason(small, "Write in 5000 words", appendContinuations = true))
        val reasoner = writer(1024)
        CombinedWriterObservations.recordUsage(CombinedWriterObservations.key(reasoner), 2048)
        assertNotNull(CombinedWriterPolicy.unavailableReason(reasoner, "Write in 5000 words", appendContinuations = true))
    }

    @Test fun appendSystemPromptAllowsCursorPacketsWhileLegacyPromptStaysStrict() {
        val strict = combinedWriterSystemPrompt("Base instructions", "PASSAGES_V3", "History in 5000 words")
        val append = combinedWriterSystemPrompt("Base instructions", "PASSAGES_V3", "History in 5000 words", appendContinuations = true)
        assertTrue(strict.contains("This is one generation"))
        assertTrue(strict.contains("no continuation, verifier"))
        assertTrue(append.startsWith("Base instructions"))
        assertTrue(append.contains("one final answer across sequential output packets"))
        assertTrue(append.contains("96 visible words"))
        assertTrue(append.contains("saved cursor"))
        assertTrue(!append.contains("no continuation, verifier"))
    }

    private fun writer(cap: Int) = PlatformV2(uid = "writer-$cap", name = "Writer", model = "model-$cap", compatibleType = ClientType.CUSTOM, apiUrl = "https://writer.example/v1", enabled = true, maxTokens = cap)
}
