package dev.chungjungsoo.gptmobile.data.research.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchResponseBlockTest {
    @Test
    fun `many uncited paragraphs stay in one block without quadratic rescans`() {
        val answer = (1..500).joinToString("\n\n") { "Paragraph $it contains factual prose with no research citation." }
        val blocks = researchResponseBlocks(answer)
        assertEquals(1, blocks.size)
        assertEquals(answer, blocks.single().text)
        assertTrue(blocks.single().citationText.isEmpty())
    }

    @Test
    fun `cited paragraphs still receive evidence cards without repeating previous citations`() {
        val answer = "Uncited introduction.\n\nA study [S1a2b] supports a claim.\n\nAnother paragraph."
        val blocks = researchResponseBlocks(answer)
        assertEquals(2, blocks.size)
        assertTrue(blocks.first().citationText.contains("[S1a2b]"))
        assertTrue(blocks.last().citationText.isEmpty())
        assertTrue(blocks.last().text.contains("Another paragraph."))
    }

    @Test
    fun `citations inside fenced code do not create evidence cards`() {
        val answer = "```text\nhttps://example.org/code-only\n```\n\nA normal paragraph."
        val blocks = researchResponseBlocks(answer)
        assertEquals(1, blocks.size)
        assertFalse(blocks.single().citationText.isNotBlank())
    }
}
