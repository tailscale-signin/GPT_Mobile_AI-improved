package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.conversation.AnswerProjection
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputTraceOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun filteredTextKeepsOnlyItsOwnPersistedInvocationIdentity() {
        val owner = OutputTraceOwner("run", 4, "part", 12, "profile")
        val model = OutputLineage(OutputOrigin.MODEL_TEXT, "physical-invocation")
        val prepared = prepareAssistantDisplay("<think>Hidden.</think>Visible answer.", combined = false).copy(outputLineage = model)
        assertEquals("Visible answer.", prepared.response)
        assertEquals(owner.copy(origin = model.origin, invocationId = model.invocationId), prepared.traceOwner(owner))
        val notice = prepared.copy(outputLineage = OutputLineage(OutputOrigin.SYSTEM_ERROR, "failed-invocation"))
        assertEquals(OutputOrigin.SYSTEM_ERROR, notice.traceOwner(owner)?.origin)
        val unowned = prepared.copy(outputLineage = null).traceOwner(owner.copy(origin = model.origin, invocationId = model.invocationId))
        assertEquals(OutputOrigin.UNKNOWN, unowned?.origin)
        assertNull(unowned?.invocationId)
    }
}
