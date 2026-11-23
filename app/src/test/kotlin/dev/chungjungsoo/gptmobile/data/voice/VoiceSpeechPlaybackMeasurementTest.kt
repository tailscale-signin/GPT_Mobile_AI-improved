package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeechPlaybackMeasurementTest {
    @Test fun productionStagesRetainTheirDistinctClockAndSumEveryChunk() {
        var now = 1_000_000_000L
        val receipt = VoiceSpeechPlaybackMeasurement(VoiceSpeechBackend.CPU, 0) { now }
        now += 2_000_000_000L
        receipt.pcmReady(1_800, 24_000, 24_000, evidence("cpu"))
        now += 5_000_000L
        receipt.pcmQueued()
        now += 150_000_000L
        receipt.firstPresented("audio_timestamp", "speaker")
        now += 700_000_000L
        receipt.pcmReady(600, 48_000, 24_000, evidence("cpu"))
        receipt.pcmQueued()
        receipt.firstPresented("audio_timestamp", "speaker")
        receipt.terminal(2, 1, 350, "audio_timestamp", "speaker")
        val result = receipt.result(VoiceSpeechPlaybackState.COMPLETE, 1)
        assertEquals(2_000L, result.firstPcmMs)
        assertEquals(2_005L, result.firstQueuedPcmMs)
        assertEquals(2_155L, result.firstPresentedAudioMs)
        assertEquals(2_400L, result.totalSynthesisMs)
        assertEquals(3_000L, result.totalAudioMs)
        assertEquals(2, result.chunks)
        assertEquals(2, result.validatedChunks)
        assertEquals(2, result.underruns)
        assertEquals(1, result.bufferRestarts)
        assertEquals(350L, result.longestRefillMs)
        assertEquals("audio_timestamp", result.clockBasis)
        assertFalse(result.acceleratorExecutionObserved)
        assertNull(result.cancellationMs)
    }

    @Test fun terminalReceiptRejectsLateNativeAndCaptionUpdatesAndMeasuresAudioStop() {
        var now = 0L
        val receipt = VoiceSpeechPlaybackMeasurement(VoiceSpeechBackend.QNN_HTP, null) { now }
        now = 10_000_000L
        receipt.pcmReady(10, 24_000, 24_000, evidence("qnn-htp", 39))
        receipt.caption(timing(10))
        now = 20_000_000L
        receipt.cancelRequested()
        now = 27_000_000L
        receipt.terminal(null, 0, 0, "playback_head", "bluetooth")
        val before = receipt.result(VoiceSpeechPlaybackState.CANCELLED, null)
        now = 100_000_000L
        receipt.pcmReady(100, 48_000, 24_000, evidence("cpu"))
        receipt.caption(timing(0))
        receipt.firstPresented("audio_timestamp", "speaker")
        receipt.terminal(50, 5, 5_000, "unknown", "private-device-name")
        assertEquals(before, receipt.result(VoiceSpeechPlaybackState.CANCELLED, null))
        assertEquals(7L, before.cancellationMs)
        assertNull(before.firstPresentedAudioMs)
        assertNull(before.underruns)
        assertNull(before.thermalBefore)
        assertTrue(before.acceleratorExecutionObserved)
    }

    @Test fun captionsMeasureRegressionsAndAcceptedBufferViolationsWithoutRetainingText() {
        val receipt = VoiceSpeechPlaybackMeasurement(VoiceSpeechBackend.CPU, 0) { 0 }
        receipt.caption(timing(20))
        receipt.caption(timing(10))
        receipt.caption(timing(200, buffered = 100))
        val result = receipt.result(VoiceSpeechPlaybackState.FAILED, 0)
        assertEquals(3, result.captionObservations)
        assertEquals(1, result.captionClockRegressions)
        assertEquals(1, result.captionTimingViolations)
        assertNull(result.firstPcmMs)
    }

    @Test fun missingOrMismatchedProcessorEvidenceCannotBecomeAnAcceleratorObservation() {
        val receipt = VoiceSpeechPlaybackMeasurement(VoiceSpeechBackend.QNN_GPU, 0) { 0 }
        receipt.pcmReady(1, 100, 24_000, null)
        receipt.pcmReady(1, 100, 24_000, evidence("qnn-htp", 39))
        assertFalse(receipt.result(VoiceSpeechPlaybackState.FAILED, 0).acceleratorExecutionObserved)
        receipt.terminal(null, 0, 0, "unrecognized-clock", "serial-device-name")
        val result = receipt.result(VoiceSpeechPlaybackState.FAILED, 0)
        assertNull(result.clockBasis)
        assertEquals("other", result.route)
    }

    @Test fun oneSuccessfulAcceleratorChunkCannotHideMissingOrCpuFallbackEvidence() {
        val receipt = VoiceSpeechPlaybackMeasurement(VoiceSpeechBackend.QNN_HTP, 0) { 0 }
        receipt.pcmReady(1, 100, 24_000, evidence("qnn-htp", 39))
        receipt.pcmReady(1, 100, 24_000, null)
        receipt.pcmReady(1, 100, 24_000, evidence("cpu"))
        receipt.pcmReady(1, 100, 24_000, evidence("qnn-htp", 39))
        val result = receipt.result(VoiceSpeechPlaybackState.COMPLETE, 0)
        assertTrue(result.acceleratorExecutionObserved)
        assertEquals(4, result.chunks)
        assertEquals(2, result.validatedChunks)
    }

    @Test fun captionCoverageCountsPreparedDistinctWordsAndAcceptsClearCues() {
        val receipt = VoiceSpeechPlaybackMeasurement(VoiceSpeechBackend.CPU, 0) { 0 }
        receipt.captionsPrepared(0, listOf(0..4, 0..4, 6..9))
        receipt.caption(timing(10), 0, 0..4)
        receipt.caption(timing(20), 0, 0..4)
        receipt.caption(VoiceCaptionTiming("local", 100, 100, 100, 24_000, 0, true, bufferedEndFrame = 1_000), 0, IntRange.EMPTY)
        val partial = receipt.result(VoiceSpeechPlaybackState.COMPLETE, 0)
        assertEquals(2, partial.captionWordsExpected)
        assertEquals(1, partial.captionWordsObserved)
        assertEquals(0, partial.captionTimingViolations)
        receipt.caption(timing(110), 0, 6..9)
        assertEquals(2, receipt.result(VoiceSpeechPlaybackState.COMPLETE, 0).captionWordsObserved)
        receipt.caption(timing(120), 0, 11..15)
        assertEquals(1, receipt.result(VoiceSpeechPlaybackState.COMPLETE, 0).captionTimingViolations)
    }

    private fun timing(frame: Long, buffered: Long = 1_000) = VoiceCaptionTiming("local", 0, 100, frame, 24_000, 0, true, bufferedEndFrame = buffered)

    private fun evidence(provider: String, kernels: Int = 0) = VoiceSpeechExecutionEvidence(
        VoiceSpeechBackend.CPU, provider, "a".repeat(64), "test-runtime", "b".repeat(64),
        VoiceSpeechStage.READY, acceleratorKernels = kernels, verifiedAudio = true, observedAtMillis = 0, profileAvailable = kernels > 0
    )
}
