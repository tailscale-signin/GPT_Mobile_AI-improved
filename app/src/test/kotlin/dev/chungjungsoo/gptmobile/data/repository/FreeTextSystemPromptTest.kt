package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.agent.CombinedTurnContext
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeTextSystemPromptTest {
    @Test fun compactPromptPreservesDeviceContextAndProjectConstraints() {
        val prompt = freeTextSystemPrompt("Answer in French.", CombinedTurnContext("2026-10-23T01:00:00Z", "2026-10-22", "America/Toronto"), "Use 500 words and cover modern history.")
        assertTrue(prompt.contains("date=2026-10-22; timezone=America/Toronto"))
        assertTrue(prompt.contains("Answer in French."))
        assertTrue(prompt.contains("Use 500 words and cover modern history."))
        assertTrue(prompt.contains("need no search"))
    }
}
