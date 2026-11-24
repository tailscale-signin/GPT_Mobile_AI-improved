package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.CombinedDocumentBlock
import dev.chungjungsoo.gptmobile.data.agent.CombinedMarkdownFrame
import dev.chungjungsoo.gptmobile.data.agent.CombinedPayload
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedDocumentPageCacheTest {
    @Test fun unchangedInlinePageReusesWorkButReplacementAndAttributionInvalidateIt() = runBlocking {
        val cache = InlineDocumentPageCache()
        val frame = CombinedMarkdownFrame(0, 5)
        val key = InlineDocumentPageKey(0, 0, frame, 0)
        var reads = 0
        suspend fun load(version: InlineDocumentPageKey): CombinedDocumentBlock = cache.get(version) {
            reads++
            CombinedDocumentBlock(0, 0, "page$reads", researchHash("page$reads"), emptyList())
        }
        val first = load(key)
        assertSame(first, load(key.copy()))
        assertEquals(1, reads)
        load(key.copy(replacement = 1))
        load(key.copy(passageRevision = 1))
        load(key.copy(frame = frame.copy(end = 6)))
        assertEquals(4, reads)
        assertTrue(
            runCatching {
                cache.get(key.copy(index = 1)) { CombinedDocumentBlock(0, 0, "wrong", "", emptyList()) }
            }.isFailure
        )
    }

    @Test fun inlinePageCacheEvictsOldTextWhileScrollingAndRetainsAtMostEightPages() = runBlocking {
        val cache = InlineDocumentPageCache()
        var loads = 0
        for (index in 0 until 100) {
            val frame = CombinedMarkdownFrame(index * 2048, (index + 1) * 2048)
            val key = InlineDocumentPageKey(index, 0, frame, 0)
            cache.get(key) {
                loads++
                CombinedDocumentBlock(index, frame.start, "x".repeat(2048), "digest", emptyList())
            }
            assertTrue(cache.retainedPages <= 8)
        }
        cache.get(InlineDocumentPageKey(0, 0, CombinedMarkdownFrame(0, 2048), 0)) {
            loads++
            CombinedDocumentBlock(0, 0, "x".repeat(2048), "digest", emptyList())
        }
        assertEquals(101, loads)
        assertEquals(8, cache.retainedPages)
    }

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
