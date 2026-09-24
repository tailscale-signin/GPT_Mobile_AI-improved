package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedConversationTest {

    @Test
    fun `combined synthesis prompt preserves selected model order`() {
        val prompt = buildCombinedSynthesisPrompt(
            originalRequest = "Compare these approaches.",
            responses = listOf(
                CombinedModelResponse("first", "Local Llama", "First answer"),
                CombinedModelResponse("second", "Gemini", "Second answer"),
                CombinedModelResponse("third", "OpenAI", "Third answer")
            )
        )

        val first = prompt.indexOf("Local Llama")
        val second = prompt.indexOf("Gemini")
        val third = prompt.indexOf("OpenAI")

        assertTrue(first >= 0)
        assertTrue(first < second)
        assertTrue(second < third)
        assertTrue(prompt.contains("ORIGINAL USER REQUEST"))
        assertTrue(prompt.endsWith("FINAL RESPONSE:\n"))
    }

    @Test
    fun `combined synthesis prompt bounds oversized candidate responses`() {
        val huge = "x".repeat(50_000)
        val prompt = buildCombinedSynthesisPrompt(
            originalRequest = "Summarize.",
            responses = listOf(
                CombinedModelResponse("one", "One", huge),
                CombinedModelResponse("two", "Two", huge)
            )
        )

        assertTrue(prompt.length < 30_000)
        assertFalse(prompt.contains(huge))
    }

    @Test
    fun `combined retry reuses preserved raw lead response instead of previous synthesis`() {
        val rawRun = AgentRun(
            runId = "raw",
            chatId = 1,
            userMessageId = 1,
            assistantMessageId = 2,
            profileUid = "lead",
            providerSnapshot = "OPENAI",
            modelSnapshot = "model"
        )
        val synthesisRun = AgentRun(
            runId = "synth",
            chatId = 1,
            userMessageId = 1,
            assistantMessageId = 2,
            profileUid = "lead",
            providerSnapshot = COMBINED_SYNTHESIS_PROVIDER_PREFIX + "OPENAI",
            modelSnapshot = "model"
        )
        val message = MessageV2(
            chatId = 1,
            content = "Previous combined answer",
            platformType = "lead",
            currentRunId = "synth",
            revisions = listOf(
                AssistantRevision(
                    content = "Raw lead answer",
                    createdAt = 1,
                    runId = "raw"
                )
            )
        )

        val responses = combinedModelResponses(
            assistantRow = listOf(message),
            runsById = mapOf("raw" to rawRun, "synth" to synthesisRun),
            platforms = listOf(PlatformV2(uid = "lead", name = "Lead")),
            orderedPlatformUids = listOf("lead")
        )

        assertEquals("Raw lead answer", responses.single().content)
    }

    @Test
    fun `combined synthesis provider prefix is distinct from normal providers`() {
        assertTrue(COMBINED_SYNTHESIS_PROVIDER_PREFIX.startsWith("COMBINED_"))
        assertFalse("OPENAI".startsWith(COMBINED_SYNTHESIS_PROVIDER_PREFIX))
    }
}
