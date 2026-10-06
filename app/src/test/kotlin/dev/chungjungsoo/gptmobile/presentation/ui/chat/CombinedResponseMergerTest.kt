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

    @Test
    fun alignsMatchingSectionsDespiteDifferentSourceOrder() {
        val merged = mergeCombinedResponses(
            listOf(
                source("a", "## Politics\nPolitical fact A [S1].\n\n## Culture\nCultural fact A."),
                source("b", "## Culture\nCultural fact B [S2].\n\n## Politics\nPolitical fact B.")
            )
        )
        assertEquals("## Politics\nPolitical fact A [S1].\n\nPolitical fact B.\n\n## Culture\nCultural fact A.\n\nCultural fact B [S2].", merged)
    }

    @Test
    fun alignsMedievalLabelsWithoutMixingDistinctModernThemes() {
        val merged = mergeCombinedResponses(
            listOf(
                source("a", "## Medieval France\nPolitical context.\n\n## Modern politics\nNew constitution."),
                source("b", "## Middle Ages\nCultural context.\n\n## Modern culture\nNew art movement.")
            )
        )
        assertEquals(1, Regex("^## Medieval France$", RegexOption.MULTILINE).findAll(merged).count())
        assertTrue(merged.indexOf("Cultural context.") < merged.indexOf("## Modern politics"))
        assertTrue(merged.contains("## Modern culture\nNew art movement."))
    }

    @Test
    fun interleavesChronologicalEntriesAndRetainsDifferentFocusAndCitations() {
        val merged = mergeCombinedResponses(
            listOf(
                source("a", "## Timeline\n- **1789**: Revolution [S1].\n- **1815**: Waterloo [S2]."),
                source("b", "## Chronology\n- **843**: Treaty of Verdun [S3].\n- **1789**: Social reform [S4].\n- **1905**: Secularism [S5].")
            )
        )
        assertEquals("## Timeline\n- **843**: Treaty of Verdun [S3].\n- **1789**: Revolution [S1].\n- **1789**: Social reform [S4].\n- **1815**: Waterloo [S2].\n- **1905**: Secularism [S5].", merged)
    }

    @Test
    fun preservesEraRangesAndDoesNotMergeDisjointNamedEvents() {
        val merged = mergeCombinedResponses(
            listOf(
                source("a", "## World War (1914–1918)\nFirst conflict.\n\n## Medieval France (476–1453)\nMedieval politics."),
                source("b", "## World War (1939–1945)\nSecond conflict.\n\n## Middle Ages (500–1500)\nMedieval art.")
            )
        )
        assertTrue(merged.contains("## World War (1939–1945)\nSecond conflict."))
        assertTrue(merged.contains("**Middle Ages (500–1500)**"))
        assertTrue(merged.contains("Medieval art."))
    }

    @Test
    fun headingsInsideFencedCodeAndTableDuplicatesRemainIntact() {
        val code = "```markdown\n## Culture\n\nOriginal code example.\n```"
        val merged = mergeCombinedResponses(listOf(source("a", "## Example\n$code"), source("b", "## Culture\nActual cultural context.")))
        assertTrue(merged.contains(code))
        assertTrue(merged.contains("## Culture\nActual cultural context."))
    }

    @Test
    fun nestedFencesAndIndentedCodeNeverCreateResponseSections() {
        val code = "````markdown\n```\n## Culture\n\nExample inside a nested fence.\n```\n````"
        val indented = "    ## Politics\n    Indented code example."
        val merged = mergeCombinedResponses(listOf(source("a", "## Example\n$code\n\n$indented"), source("b", "## Culture\nActual cultural context.")))
        assertTrue(merged.contains(code))
        assertTrue(merged.contains(indented))
        assertTrue(merged.contains("## Culture\nActual cultural context."))
    }

    @Test
    fun datesSortAcrossBceAndCeWithNumberedListsRenumbered() {
        val merged = mergeCombinedResponses(
            listOf(
                source("a", "## Timeline\n1. 1789: Revolution.\n2. 1815: Waterloo."),
                source("b", "## Timeline\n1. 58 BCE: Roman campaigns.\n2. 843: Treaty.")
            )
        )
        assertEquals("## Timeline\n1. 58 BCE: Roman campaigns.\n2. 843: Treaty.\n3. 1789: Revolution.\n4. 1815: Waterloo.", merged)
    }

    @Test
    fun ordinaryNumberedListsKeepTheirOriginalOrderAndNumbers() {
        val first = "1. Political overview.\n2. Cultural overview."
        val second = "1. Economic overview.\n2. Religious overview."
        val merged = mergeCombinedResponses(listOf(source("a", "## Summary\n$first"), source("b", "## Summary\n$second")))
        assertEquals("## Summary\n$first\n\n$second", merged)
    }

    @Test
    fun alignsAdjectivalThemeLabelsWithTheirMatchingSections() {
        val merged = mergeCombinedResponses(
            listOf(
                source("a", "## Politics\nConstitutional changes.\n\n## Culture\nLiterary movements."),
                source("b", "## Cultural history\nArchitectural movements.\n\n## Political history\nReforms.")
            )
        )
        assertEquals("## Politics\nConstitutional changes.\n\nReforms.\n\n## Culture\nLiterary movements.\n\nArchitectural movements.", merged)
    }
}
