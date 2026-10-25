package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.CombinedDocumentBlock
import dev.chungjungsoo.gptmobile.data.agent.CombinedPayload
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedDocumentPageCacheTest {
    @Test fun scrollingAMillionCharactersRetainsOnlyEightPagesAndUsesBoundedReads() = runBlocking {
        val requests = mutableListOf<Pair<Int, Int>>()
        val count = 125
        val cache = CombinedDocumentPageCache { first, requested ->
            requests += first to requested
            (first until minOf(first + requested, count)).map { index ->
                val text = "$index:" + "x".repeat(CombinedPayload.PAGE_CHARACTERS - "$index:".length)
                CombinedDocumentBlock(index, index * CombinedPayload.PAGE_CHARACTERS, text, researchHash(text), emptyList())
            }
        }
        for (index in 0 until count) {
            assertEquals(index, cache.get(index).sequence)
            assertTrue(cache.retainedPages <= 8)
            assertTrue(cache.retainedCharacters <= 8 * CombinedPayload.PAGE_CHARACTERS)
        }
        assertTrue(requests.all { it.second == 4 })
        assertEquals(32, requests.size)
        assertEquals(0, cache.get(0).sequence)
        assertEquals(33, requests.size)
        assertTrue(cache.retainedPages <= 8)
    }
}
