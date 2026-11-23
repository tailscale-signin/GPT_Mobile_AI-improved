package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.conversation.AnswerProjection
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputTraceOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantDisplayPreparationTest {
    @Test fun filteredThinkingCannotTransferProtectionToAnAssembledAnswer() {
        val display = prepareAssistantDisplay("Al<think>Alice</think>ice and Bob", combined = false, privacyProtectedValues = listOf("Alice", "Bob"))
        assertEquals("Alice and Bob", display.response)
        assertEquals(listOf("Bob"), display.privacyProtectedValues)
    }

    @Test fun unchangedProtectedWordsSurviveReasoningAndLeadingSubjectRemoval() {
        val raw = "<think>Hidden.</think><!-- chat_subject: Greeting --> Hello **Alice**."
        for (combined in listOf(false, true)) {
            val display = prepareAssistantDisplay(raw, combined, privacyProtectedValues = listOf("Alice"))
            assertEquals("Hello **Alice**.", display.response)
            assertEquals(listOf("Alice"), display.privacyProtectedValues)
        }
        assertEquals(listOf("Alice"), prepareAssistantDisplay("Hello Alice  \n", combined = false, privacyProtectedValues = listOf("Alice")).privacyProtectedValues)
    }

    @Test fun sourceCleanupWithoutAnOffsetMapWithholdsPrivacyEvidence() {
        val raw = "Protected Al ice.\nAl [S1]ice"
        val display = prepareAssistantDisplay(raw, combined = false, privacyProtectedValues = listOf("Al ice"))
        assertEquals("Protected Al ice.\nAl ice", display.response)
        assertTrue(display.privacyProtectedValues.isEmpty())
        assertEquals(listOf("Al ice"), prepareAssistantDisplay(raw, combined = true, privacyProtectedValues = listOf("Al ice")).privacyProtectedValues)
    }

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
