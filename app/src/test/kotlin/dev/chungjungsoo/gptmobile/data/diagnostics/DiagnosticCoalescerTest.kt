package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticCoalescerTest {
    @Test fun repeatedReplayCompactionsRetainCountsTimingAndWorstMeasurements() {
        var now = 100L
        val coalescer = DiagnosticCoalescer { now }
        fun line(raw: Int, replay: Int, micros: Int) = "PRIMARY_REPLAY_COMPACTED · rawTokens=$raw · replayTokens=$replay · compactionUs=$micros · tokenCounts=ESTIMATED"
        assertEquals(1, coalescer.accept("Agent", line(1000, 500, 100), "I").size)
        now = 200
        assertTrue(coalescer.accept("Agent", line(618994, 10849, 900), "I").isEmpty())
        now = 300
        assertTrue(coalescer.accept("Agent", line(610000, 10000, 100), "I").isEmpty())
        val summary = coalescer.drain().single().second
        assertTrue(summary.contains("count=2"))
        assertTrue(summary.contains("firstMonotonicMs=200"))
        assertTrue(summary.contains("lastMonotonicMs=300"))
        assertTrue(summary.contains("max_rawTokens=618994"))
        assertTrue(summary.contains("max_replayTokens=10849"))
        assertTrue(summary.contains("max_compactionUs=900"))
        assertTrue(coalescer.drain().isEmpty())
        repeat(3) { assertEquals(1, coalescer.accept("Agent", "PRIMARY_REPLAY_BUDGET_OVERRUN · replayTokens=11000", "W").size) }
    }

    @Test fun noisyPolicyDecisionsAreCountedAndErrorsRemainIndividual() {
        var now = 0L
        val coalescer = DiagnosticCoalescer { now }
        val line = "TOOL_AUTHORIZATION_HIDDEN PERSONAL_DATA_LOCAL_ONLY"
        assertEquals(1, coalescer.accept("ToolCatalog", line, "I").size)
        repeat(25) { assertTrue(coalescer.accept("ToolCatalog", line, "I").isEmpty()) }
        now = 15_000
        assertTrue(coalescer.accept("ToolCatalog", line, "I").single().second.contains("count=26"))
        repeat(3) { assertEquals(1, coalescer.accept("Voice", "recognition rejected", "E").size) }
        coalescer.accept("ToolCatalog", line, "I")
        assertTrue(coalescer.drain().single().second.contains("count=1"))
        assertTrue(coalescer.drain().isEmpty())
    }
}
