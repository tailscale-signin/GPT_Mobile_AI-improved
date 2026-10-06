package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedResponseMergerTest {
    private fun source(id: String, text: String) = CombinedModelResponse(id, id, content = text)

    @Test
    fun preservesUniqueSentencesBesideRepeatedFacts() {
        val merged = mergeCombinedResponses(
            listOf(
                source("a", "A shared fact. First unique detail."),
                source("b", "A shared fact. Second unique detail.")
            )
        )
        assertEquals("A shared fact. First unique detail.\n\nSecond unique detail.", merged)
    }

    @Test
    fun deduplicatesAnIdenticalHeadingAndBodyBlock() {
        val section = "## Result\nA shared fact."
        assertEquals(section, mergeCombinedResponses(listOf(source("a", section), source("b", section))))
    }

    @Test
    fun preservesUniqueDetailsFromEveryProfileBeyondOldCharacterCap() {
        val first = "a".repeat(24000) + "\n\nUnique ending A."
        val second = "Shared finding.\n\nUnique ending B."
        val merged = mergeCombinedResponses(listOf(source("a", first), source("b", second)))
        assertTrue(merged.contains(first))
        assertTrue(merged.contains(second))
    }

    @Test
    fun removesOnlyRepeatedWholeProseBlocks() {
        val merged = mergeCombinedResponses(listOf(source("a", "Same fact.\n\nOne detail."), source("b", "Same  fact.\n\nAnother detail.")))
        assertEquals("Same fact.\n\nOne detail.\n\nAnother detail.", merged)
    }

    @Test
    fun preservesFencedCodeBlankLinesTablesAndConflictingEvidence() {
        val code = "```kotlin\nval a = 1\n\nval b = 2\n```"
        val table = "| A | B |\n|---|---|\n| 1 | 2 |\n| 1 | 2 |"
        val merged = mergeCombinedResponses(listOf(source("a", "$code\n\n$table\n\nEstimate: 5."), source("b", "Estimate: 7.")))
        assertTrue(merged.contains(code))
        assertTrue(merged.contains(table))
        assertTrue(merged.contains("Estimate: 5."))
        assertTrue(merged.contains("Estimate: 7."))
    }

    @Test
    fun preservesPartialContentWithoutTransportErrorNote() {
        assertEquals("Useful research.", mergeCombinedResponses(listOf(source("a", "Useful research.\n\n[Response stopped: Connection lost]"))))
    }
}
