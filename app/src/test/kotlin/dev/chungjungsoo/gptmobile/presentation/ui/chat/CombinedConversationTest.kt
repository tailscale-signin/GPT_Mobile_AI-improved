package dev.chungjungsoo.gptmobile.presentation.ui.chat

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
    fun `combined synthesis provider prefix is distinct from normal providers`() {
        assertTrue(COMBINED_SYNTHESIS_PROVIDER_PREFIX.startsWith("COMBINED_"))
        assertFalse("OPENAI".startsWith(COMBINED_SYNTHESIS_PROVIDER_PREFIX))
    }
}
