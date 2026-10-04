package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugMemorySourceTest {
    @Test
    fun `birthday value is highlighted without coloring the surrounding answer`() {
        val answer = "Your birthday is on August 4th 1987."
        val ranges = memoryHighlightRanges(answer, listOf("My birthday is August 4th 1987"))
        assertTrue(ranges.any { answer.substring(it) == "August 4th 1987" })
        assertTrue(ranges.none { it.first == 0 })
    }

    @Test
    fun `no recalled values means no pink answer spans`() {
        assertTrue(memoryHighlightRanges("August 4th 1987", emptyList()).isEmpty())
    }

    @Test
    fun `formatting and repeated facts preserve offsets without duplicates`() {
        val answer = "**August 4th 1987**, again August 4th 1987."
        val ranges = memoryHighlightRanges(answer, listOf("August 4th 1987", "August 4th 1987"))
        assertEquals(listOf("August 4th 1987", "August 4th 1987"), ranges.map { answer.substring(it) })
    }

    @Test
    fun `common isolated words do not claim memory provenance`() {
        assertTrue(memoryHighlightRanges("Your project is ready", listOf("Your birthday is August 4th 1987")).isEmpty())
    }

    @Test
    fun `memory source rows collapse repeated value labels and dedupe rows`() {
        val rows = debugMemorySourceRows(
            listOf(
                DebugMemorySource("August 4th 1987 observation August 4th 1987", "August 4th 1987"),
                DebugMemorySource("August 4th 1987 observation August 4th 1987", "August 4th 1987"),
                DebugMemorySource("User profile Je suis en vacances en France", "Je suis en vacances en France"),
                DebugMemorySource("User goal to know specifically about chatGPT pro subscription.", "to know specifically about chatGPT pro subscription.")
            )
        )

        assertEquals(
            listOf(
                "Observation: August 4th 1987",
                "User profile: Je suis en vacances en France",
                "User goal: to know specifically about chatGPT pro subscription."
            ),
            rows
        )
    }

    @Test
    fun `memory tool result ids parse without exposing bundle text`() {
        val result = """{"recalledFactIds":["birthday","birthday","profile"],"text":"Memory sourced: August 4th 1987"}"""

        assertEquals(listOf("birthday", "profile"), rememberedMemoryIdsFromToolResult(result))
    }

    @Test
    fun `memory source rows remove replacement characters and control characters`() {
        val rows = debugMemorySourceRows(listOf(DebugMemorySource("User profile Bad�\u0007 text", "Bad�\u0007 text")))

        assertEquals(listOf("User profile: Bad text"), rows)
    }
}
