package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePerformanceHistoryTest {
    @Test fun last64FramePercentilesDoNotClaimTheWholeObservedPopulation() {
        val history = VoicePerformanceHistory()
        repeat(6295) { index -> history.observe("frame_duration", if (index < 6231) 400 else 5, publish = false) }
        history.framesDropped(3)
        history.framesDropped(-1)
        history.publish()
        val snapshot = history.snapshots.value
        val frame = snapshot.rows.single()
        assertEquals(6295L, snapshot.sampledFrames)
        assertEquals(3L, snapshot.droppedFrameSamples)
        assertEquals(64, frame.count)
        assertEquals(6295L, frame.observedSamples)
        assertEquals(5L, frame.p95Ms)
        history.clear()
        assertEquals(VoicePerformanceSnapshot(), history.snapshots.value)
        history.observe("frame_duration", 9)
        assertEquals(1L, history.snapshots.value.rows.single().observedSamples)
    }

    @Test fun percentilesKeepColdAndWarmEnginesSeparateAndBoundSamples() {
        val history = VoicePerformanceHistory(8)
        (1L..12).forEach { history.observe("text_to_audio", it * 10, "KOKORO", true) }
        history.observe("text_to_audio", 900, "KOKORO", false)
        val rows = history.snapshots.value.rows
        val warm = rows.single { it.warm == true }
        assertEquals(8, warm.count)
        assertEquals(12L, warm.observedSamples)
        assertEquals(80L, warm.p50Ms)
        assertEquals(120L, warm.p95Ms)
        assertEquals(900L, rows.single { it.warm == false }.p50Ms)
    }

    @Test fun missingAndInvalidMeasurementsNeverBecomeZeroLatencyClaims() {
        val history = VoicePerformanceHistory()
        history.observe("untrusted field", 12)
        history.observe("text_to_audio", -1)
        history.observe("text_to_audio", 10, "fake_gpu")
        assertTrue(history.snapshots.value.rows.isEmpty())
        history.health(0, 128_000)
        assertEquals(null, history.snapshots.value.captureQueueMs)
        history.clear()
        assertEquals(VoicePerformanceSnapshot(), history.snapshots.value)
    }

    @Test fun frameCollectionDoesNotPublishOnEveryFrame() {
        val history = VoicePerformanceHistory()
        history.observe("frame_interval", 16, publish = false)
        assertTrue(history.snapshots.value.rows.isEmpty())
        history.publish()
        assertEquals(1L, history.snapshots.value.sampledFrames)
        assertEquals(16L, history.snapshots.value.rows.single().p50Ms)
    }

    @Test fun actualSpeechProvidersNeverShareAPercentilePool() {
        val history = VoicePerformanceHistory()
        listOf("KOKORO_CPU", "QNN_GPU", "QNN_HTP", "GATEWAY_CUDA").forEachIndexed { index, engine ->
            history.observe("text_to_audio", (index + 1) * 100L, engine, true)
        }
        val rows = history.snapshots.value.rows
        assertEquals(4, rows.size)
        assertEquals(setOf(100L, 200L, 300L, 400L), rows.map { it.p50Ms }.toSet())
        assertTrue(rows.all { it.count == 1 })
    }
}
