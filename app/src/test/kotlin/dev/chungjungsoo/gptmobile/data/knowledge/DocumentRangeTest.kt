package dev.chungjungsoo.gptmobile.data.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentRangeTest {
    private val source = (0 until 3301).joinToString("") { ('a'.code + it % 26).toChar().toString() }
    private val chunks = (source.indices step 1000).mapIndexed { index, offset ->
        val end = minOf(offset + 1200, source.length)
        KnowledgeChunk("d:$index", "d", index, offset, end, source.substring(offset, end))
    }

    @Test fun paginatedReadEqualsOriginalAcrossOverlaps() {
        val pages = (source.indices step 137).joinToString("") { offset -> reconstructDocumentRange(chunks, offset, minOf(offset + 137, source.length)) }
        assertEquals(source, pages)
        assertEquals(source, reconstructDocumentRange(chunks, 0, source.length))
    }

    @Test fun missingSourceRangesFailRatherThanInventOffsets() {
        assertTrue(runCatching { reconstructDocumentRange(chunks.filterNot { it.chunkIndex == 1 }, 1150, 2100) }.isFailure)
    }
}
