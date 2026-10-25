package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationSafetyTest {
    private fun bundle(text: String) = ProviderEvent.CombinedAnswer(text, CombinedQualityReport(revisionId = "final", answerDigest = dev.chungjungsoo.gptmobile.data.research.researchHash(text), complete = true))

    @Test fun atomicReplacementDoesNotCountItsProvisionalBodyTwiceOrInventRepetition() = runTest {
        val body = "abcdefgh".repeat(128)
        val final = bundle(body)
        val events = GenerationSafety(body.length, true).apply(flowOf(ProviderEvent.TextDelta(body), final, ProviderEvent.Completed)).toList()
        assertEquals(listOf(ProviderEvent.TextDelta(body), final, ProviderEvent.Completed), events)
        assertTrue(events[1] === final)
    }

    @Test fun oversizedAtomicReplacementIsRejectedWithoutTruncatingItsBodyOrReport() = runTest {
        val final = bundle("abcdefghi")
        val events = GenerationSafety(6, false).apply(flowOf(ProviderEvent.TextDelta("abc"), final, ProviderEvent.Completed)).toList()
        assertEquals(ProviderEvent.TextDelta("abc"), events.first())
        assertTrue(events.last() is ProviderEvent.Failed)
        assertFalse(events.any { it is ProviderEvent.CombinedAnswer || it == ProviderEvent.Completed })
    }

    @Test fun atomicReplacementKeepsReasoningAndEarlierRoundCharges() = runTest {
        val guard = GenerationSafety(10, false)
        guard.apply(flowOf(ProviderEvent.ThinkingDelta("1234"), ProviderEvent.TextDelta("ab"), ProviderEvent.Completed)).toList()
        val events = guard.apply(flowOf(bundle("cdefg"), ProviderEvent.Completed)).toList()
        assertTrue(events.single() is ProviderEvent.Failed)
    }

    @Test fun aFullAtomicBodyStillHonorsTheRepetitionSafeguard() = runTest {
        val events = GenerationSafety(0, true).apply(flowOf(bundle("abcdefgh".repeat(1024)), ProviderEvent.Completed)).toList()
        assertTrue(events.single() is ProviderEvent.Failed)
        assertTrue((events.single() as ProviderEvent.Failed).message.contains("sustained exact repetition"))
    }

    @Test fun sizeStopKeepsPartialOutput() = runTest {
        val events = GenerationSafety(5, false).apply(flowOf(ProviderEvent.TextDelta("abcdef"), ProviderEvent.Completed)).toList()
        assertEquals("abcde", (events.first() as ProviderEvent.TextDelta).text)
        assertTrue(events.last() is ProviderEvent.Failed)
        assertFalse(events.contains(ProviderEvent.Completed))
    }

    @Test fun budgetSpansRoundsAndReasoning() = runTest {
        val guard = GenerationSafety(5, false)
        guard.apply(flowOf(ProviderEvent.ThinkingDelta("abcd"))).toList()
        val events = guard.apply(flowOf(ProviderEvent.TextDelta("ef"))).toList()
        assertEquals("e", (events.first() as ProviderEvent.TextDelta).text)
        assertTrue(events.last() is ProviderEvent.Failed)
    }

    @Test fun disabledSizeGuardPreservesLongText() = runTest {
        val text = "some content ".repeat(50_000)
        val events = GenerationSafety(0, false).apply(flowOf(ProviderEvent.TextDelta(text), ProviderEvent.Completed)).toList()
        assertEquals(text, (events.first() as ProviderEvent.TextDelta).text)
        assertEquals(ProviderEvent.Completed, events.last())
    }

    @Test fun repeatedBlocksStopWithoutRetry() = runTest {
        val events = GenerationSafety(0, true).apply(flowOf(ProviderEvent.TextDelta("abcdefgh".repeat(1024)), ProviderEvent.Completed)).toList()
        assertTrue(events.last() is ProviderEvent.Failed)
    }

    @Test fun surrogatePairIsNotSplit() = runTest {
        val events = GenerationSafety(2, false).apply(flowOf(ProviderEvent.TextDelta("a\uD83D\uDE00"))).toList()
        assertEquals("a", (events.first() as ProviderEvent.TextDelta).text)
    }
}
