package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedWordingLineageTest {
    @Test fun mappingCacheBindsTheSourceAttemptAndAncestry() {
        val text = "The city approved the river restoration project in 2024."
        val source = CombinedModelResponse("cache-profile", "Cache", content = text, attemptId = "first", ancestryIds = setOf("parent-one"))
        val first = CombinedAnswerMappings.get(text, listOf(source))
        assertSame(first, CombinedAnswerMappings.get(text, listOf(source)))
        val attempt = CombinedAnswerMappings.get(text, listOf(source.copy(attemptId = "second")))
        val ancestry = CombinedAnswerMappings.get(text, listOf(source.copy(ancestryIds = setOf("parent-two"))))
        assertNotEquals(first.corpusDigest, attempt.corpusDigest)
        assertNotEquals(first.corpusDigest, ancestry.corpusDigest)
    }

    @Test fun excessiveLinkExpansionKeepsItsUnevaluatedStatusAndReusesTheCompletedMap() {
        val text = "The city approved the river restoration project in 2024."
        val answer = List(1100) { text }.joinToString("\n\n")
        val source = CombinedModelResponse("bounded-profile", "Bounded", content = text)
        val mapping = CombinedAnswerMappings.get(answer, listOf(source))
        assertFalse(mapping.evaluated)
        assertTrue(mapping.links.isEmpty())
        assertTrue(mapping.matches(answer))
        assertSame(mapping, CombinedAnswerMappings.get(answer, listOf(source)))
    }

    private val athens = "Wealth from trade and silver mines funded public works and naval power."
    private val tang = "Poetry flourished as Li Bai and Du Fu became revered across China."
    private val sources = listOf(
        CombinedModelResponse("athens", "Athens profile", content = athens),
        CombinedModelResponse("tang", "Tang profile", content = tang)
    )

    @Test fun mergedAnswerHasDistinctProfileRangesWithoutChangingText() {
        val answer = "😀 **Athens**\n\n$athens\n\n**Tang China**\n\n$tang"
        val spans = combinedWordingSpans(answer, sources)
        assertEquals(listOf(setOf("athens"), setOf("tang")), spans.map { it.profileUids })
        assertEquals(listOf(athens.dropLast(1), tang.dropLast(1)), spans.map { answer.substring(it.start, it.end) })
    }

    @Test fun formattingAndCaseChangesPreserveUtf16Offsets() {
        val answer = "<!-- chat_subject: Ancient cultures -->\n\n😀 WEALTH from **trade and silver mines** funded public works and naval power."
        val spans = combinedWordingSpans(answer, sources)
        assertEquals(1, spans.size)
        assertEquals("WEALTH from **trade and silver mines** funded public works and naval power", answer.substring(spans.single().start, spans.single().end))
    }

    @Test fun duplicateWordingIsSharedRegardlessOfProfileOrder() {
        val duplicate = sources.first().copy(platformUid = "duplicate")
        val spans = combinedWordingSpans(athens, sources + duplicate)
        assertEquals(setOf("athens", "duplicate"), spans.single().profileUids)
        assertEquals(spans, combinedWordingSpans(athens, (sources + duplicate).reversed()))
    }

    @Test fun overlapIsPartitionedAndSharedTextNeverClaimsExclusiveOwnership() {
        val first = "The citizens funded public works and naval power through silver mines."
        val second = "Public works and naval power through silver mines funded the citizens."
        val spans = combinedWordingSpans(first, listOf(CombinedModelResponse("one", "One", content = first), CombinedModelResponse("two", "Two", content = second)))
        assertTrue(spans.any { it.profileUids == setOf("one", "two") })
        assertTrue(spans.zipWithNext().all { (a, b) -> a.end <= b.start })
    }

    @Test fun changedSourcesAndUnmatchedParaphrasesDoNotKeepOldColours() {
        assertTrue(combinedWordingSpans(athens, sources).isNotEmpty())
        assertTrue(combinedWordingSpans(athens, sources.drop(1)).isEmpty())
        assertTrue(combinedWordingSpans("Mining and trading paid for civic infrastructure.", sources).isEmpty())
        assertTrue(combinedWordingSpans("Yes, I can help.", sources).isEmpty())
    }

    @Test fun paragraphBoundariesAndMetadataNeverBecomeWordingMatches() {
        val content = "<!-- chat_subject: A long descriptive subject about ancient cultures -->\nAlpha beta gamma\n\ndelta epsilon zeta."
        assertTrue(combinedWordingSpans(content, listOf(CombinedModelResponse("one", "One", content = content))).isEmpty())
    }
}
