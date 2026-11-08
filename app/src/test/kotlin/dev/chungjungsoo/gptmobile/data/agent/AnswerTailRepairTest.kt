package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AnswerTailRepairTest {
    private val prefix = "A completed paragraph with established evidence. ".repeat(50).trimEnd() + "\n\n"

    @Test fun onlyTheUnfinishedTailIsSubmittedAndCompletedParagraphsRemainByteIdentical() = runTest {
        val repair = AnswerTailRepair.plan(prefix + "The remaining explanation is", listOf("UNFINISHED_PROSE"))!!
        assertEquals(prefix, repair.preservedPrefix)
        assertEquals("The remaining explanation is", repair.unfinishedTail)
        val delegate = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) =
                flowOf(ProviderEvent.TextDelta("The remaining explanation "), ProviderEvent.TextDelta("is complete."), ProviderEvent.Completed)
        }
        val events = repair.session(delegate).streamRound(emptyList(), emptyList()).toList()
        assertEquals(prefix + "The remaining explanation is complete.", events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test fun otherAnswerRequirementsAndStructuralContentKeepCompleteAnswerValidation() {
        val draft = prefix + "The explanation is unfinished"
        assertNotNull(AnswerTailRepair.plan(draft, listOf("OUTPUT_TRUNCATED", "UNFINISHED_PROSE")))
        assertNull(AnswerTailRepair.plan(draft, listOf("OUTPUT_TRUNCATED")))
        assertNull(AnswerTailRepair.plan(draft, listOf("WORD_LIMIT_EXCEEDED")))
        assertNull(AnswerTailRepair.plan(draft, listOf("UNFINISHED_PROSE", "SUBSTANTIAL_REPETITION")))
        assertNull(AnswerTailRepair.plan(prefix + "```kotlin\nval x =", listOf("OUTPUT_TRUNCATED")))
        assertNull(AnswerTailRepair.plan(prefix + "| A | B", listOf("OUTPUT_TRUNCATED")))
        assertNull(AnswerTailRepair.plan(prefix + "- A final list item", listOf("OUTPUT_TRUNCATED")))
        assertNull(AnswerTailRepair.plan(prefix + "> An unfinished quotation", listOf("OUTPUT_TRUNCATED")))
        assertNull(AnswerTailRepair.plan("Short answer", listOf("UNFINISHED_PROSE")))
    }
}
