package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationSafetyTest {
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
