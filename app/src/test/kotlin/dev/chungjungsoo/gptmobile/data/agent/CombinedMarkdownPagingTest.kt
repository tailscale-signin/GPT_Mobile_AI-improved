package dev.chungjungsoo.gptmobile.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedMarkdownPagingTest {
    @Test fun boundariesKeepCombiningMarksFlagsEmojiAndCrLfTogether() {
        listOf("e\u0301", "👩🏽‍💻", "🇬🇧", "\r\n").forEach { cluster ->
            for (prefix in 8184..8191) {
                val text = "a".repeat(prefix) + cluster + "z".repeat(9000)
                val pages = CombinedPayload.pages(text).toList()
                assertEquals(text, pages.joinToString("") { it.second })
                assertTrue(pages.all { it.second.length <= CombinedPayload.PAGE_CHARACTERS })
                assertFalse(pages.drop(1).any { (start, _) -> start > prefix && start < prefix + cluster.length })
            }
        }
    }

    @Test fun continuedTablePagesRepeatOnlySyntheticHeaderAndKeepEveryOriginalRow() {
        val header = "| Name | Details |\n| :--- | ---: |\n"
        val text = header + (1..2000).joinToString("") { "| Item $it | évidence 🦋 for row $it |\n" } + "\nEnd."
        val frames = CombinedMarkdownPaging.frames(text).toList()
        assertTrue(frames.size > 5)
        assertEquals(text, frames.joinToString("") { text.substring(it.start, it.end) })
        frames.drop(1).filter { text.substring(it.start, it.end).startsWith("| Item") }.forEach {
            assertEquals(header, it.tableHeader)
            assertEquals("", it.openingFence)
            assertEquals("", it.closingFence)
        }
        assertTrue(frames.drop(1).any { it.tableHeader == header })
    }

    @Test fun codeFenceLengthAndLanguageSurvivePagesAndTableLikeCodeStaysCode() {
        val text = "````kotlin\n" + "| Name | Value |\n| --- | --- |\n".repeat(1500) + "```\nStill code.\n````\n\nAfter."
        val frames = CombinedMarkdownPaging.frames(text).toList()
        assertTrue(frames.size > 3)
        assertEquals(text, frames.joinToString("") { text.substring(it.start, it.end) })
        assertTrue(frames.drop(1).all { it.openingFence == "````kotlin\n" })
        assertTrue(frames.dropLast(1).all { it.closingFence == "\n````" })
        assertEquals("", frames.last().closingFence)
        assertTrue(frames.all { it.tableHeader.isEmpty() })
    }
}
