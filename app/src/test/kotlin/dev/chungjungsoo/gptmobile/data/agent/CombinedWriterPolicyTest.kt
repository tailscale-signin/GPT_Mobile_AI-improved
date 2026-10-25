package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedWriterPolicyTest {
    @After fun clearObservations() = CombinedWriterObservations.clearForTest()

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
