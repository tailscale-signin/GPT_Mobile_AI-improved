package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameTimingWindowTest {
    @Test fun periodicReportsAndCaptureEpochChangesNeverReuseAWindowSequence() {
        val window = FrameTimingWindow(0)
        window.align(1, 0)
        val first = window.sequence
        window.observe(8_000_000, 10_000_000, 0, false, 10)
        assertEquals(JsonPrimitive(first), window.callbackCoverage(5000)["windowSequence"])
        assertEquals(first, window.sequence)
        window.clear(5000)
        assertEquals(first + 1, window.sequence)
        val second = window.sequence
        assertFalse(window.align(1, 5001))
        assertEquals(second, window.sequence)
        window.align(null, 5100)
        window.align(2, 5200)
        assertEquals(second + 2, window.sequence)
        assertEquals(JsonPrimitive(window.sequence), window.callbackCoverage(10200)["windowSequence"])
    }

    @Test fun callbackCoverageIncludesSilentWindowsWithoutInventingSessionJank() {
        val window = FrameTimingWindow(0)
        window.align(1, 100)
        val silent = window.callbackCoverage(5100)
        assertEquals(JsonPrimitive("NO_CALLBACKS"), silent["coverage"])
        assertEquals(JsonPrimitive(0), silent["observedCallbacks"])
        assertEquals(JsonPrimitive(false), silent["sessionJankRateAvailable"])
        window.observe(80_000_000, 10_000_000, 2, false, 1100)
        window.observe(90_000_000, 10_000_000, 0, false, 3100)
        val observed = window.callbackCoverage(5100)
        assertEquals(JsonPrimitive("CALLBACKS_DROPPED"), observed["coverage"])
        assertEquals(JsonPrimitive(1000L), observed["firstCallbackDelayMs"])
        assertEquals(JsonPrimitive(2000L), observed["maximumCallbackGapMs"])
        assertEquals(JsonPrimitive(2000L), observed["lastCallbackAgeMs"])
        assertEquals(JsonPrimitive(false), observed["callbackGapsAreJankEvidence"])
        window.clear(5100)
        assertEquals(JsonPrimitive("NO_CALLBACKS"), window.callbackCoverage(10100)["coverage"])
        assertEquals(JsonPrimitive(0L), window.callbackCoverage(10100)["maximumCallbackGapMs"])
    }

    @Test fun thresholdFlushAndAlreadyQueuedTimerShareOneDeadline() {
        val window = FrameTimingWindow(0)
        window.align(1, 0)
        repeat(120) { window.observe(7_000_000, 10_000_000, 0, false) }
        assertTrue(window.due(5000))
        window.clear(5000)
        window.observe(7_000_000, 10_000_000, 0, false)
        assertFalse("A timer queued before the threshold flush must not emit a one-frame window", window.due(5006))
        assertEquals(4994L, window.nextDelayMs(5006))
        assertEquals(1, window.frames)
        assertEquals(121L, window.observedFrames)
        assertTrue(window.due(10_000))
    }

    @Test fun optOutAndNewEpochClearEveryAggregateAndRequestFreshProcessBaselines() {
        val window = FrameTimingWindow(0)
        assertTrue(window.align(1, 100))
        window.observe(600_000_000, 8_000_000, 3, true)
        window.work.observe(FrameWorkBreakdown.Phase.DRAW, 500_000_000, true)
        window.runCoverage.observe(mapOf("old-run" to "RENDERING"), 1)
        window.evidence.observe(1000, 600_000_000, setOf("old-run"), 1) { 90_000_000 }
        assertTrue(window.align(null, 200))
        assertFalse(window.observe(800_000_000, 8_000_000, 4, true))
        assertTrue(window.align(2, 300))
        assertFalse(window.align(2, 301))
        window.observe(5_000_000, 8_000_000, 0, false)
        assertEquals(1, window.frames)
        assertEquals(1L, window.observedFrames)
        assertEquals(0, window.slow)
        assertEquals(0, window.dropped)
        assertEquals(5_000_000L, window.maximumNs)
        assertEquals(5_000_000L, window.totalNs)
        assertEquals(8_000_000L, window.minimumDeadlineNs)
        assertEquals(8_000_000L, window.maximumDeadlineNs)
        assertEquals(0, window.fallbackDeadlines)
        assertEquals(0, window.histogram.stallsOver500Ms)
        assertTrue(window.work.maximumNanos().values.all { it == null })
        assertTrue(window.runCoverage.snapshot(2).isEmpty())
        assertEquals(JsonPrimitive(0), window.evidence.snapshot(2).getValue("window").jsonObject["frameSamples"])
        assertEquals(300L, window.startedMs)
        assertFalse(window.due(5100))
        assertTrue(window.due(5300))
    }
}
