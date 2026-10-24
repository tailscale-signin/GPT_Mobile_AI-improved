package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.conversation.AnswerProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AssistantDisplayPreparationTest {
    @Test fun preparationSeparatesReasoningAndPreservesCombinedCitationText() {
        val raw = "<think>Private work.</think>Portugal joined the EU in 1986. [Source](https://example.com/history)"
        val display = prepareAssistantDisplay(raw, combined = true)
        assertEquals("Private work.", display.thinking.trim())
        assertEquals(AnswerProjection.text(raw), display.citationText)
        assertEquals(display.citationText, display.response)
        assertFalse(display.response.contains("Private work"))
    }
}
