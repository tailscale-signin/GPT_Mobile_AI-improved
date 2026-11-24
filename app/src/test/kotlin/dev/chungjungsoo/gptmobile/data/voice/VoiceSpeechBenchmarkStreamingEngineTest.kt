package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeechBenchmarkStreamingEngineTest {
    @Test fun realCallbackAndConsumerWaitBelongToTheCompleteStreamWallClock() {
        val now = AtomicLong()
        val receipt = VoiceSpeechEnginePlaybackObservation(now::get)
        var emittedBeforeReturn = false
        var returned = false
        val delegate = engine { emit ->
            now.addAndGet(10_000_000)
            emit(VoicePcmChunk(floatArrayOf(.1f)))
            now.addAndGet(30_000_000)
            returned = true
        }
        val bridge = VoiceSpeechBenchmarkStreamingEngine(delegate, { null }, { receipt })
        bridge.stream("word", 0, 1f) {
            emittedBeforeReturn = !returned
            now.addAndGet(20_000_000)
            true
        }
        assertTrue(emittedBeforeReturn)
        assertTrue(receipt.validated())
        assertEquals(1, receipt.callCount())
        assertEquals(60L, receipt.streamWallMs())
        assertNull(bridge.executionEvidence)
        assertEquals("mediapipe-phonon-cpu-requested", bridge.executionProvider)
    }

    @Test fun consumerCancellationCannotBecomeStructurallyCompleteEvenIfProducerIgnoresItsReturn() {
        val receipt = VoiceSpeechEnginePlaybackObservation()
        val cancelled = AtomicBoolean()
        val bridge = VoiceSpeechBenchmarkStreamingEngine(engine(cancelled) { emit -> emit(VoicePcmChunk(floatArrayOf(.1f))) }, { null }, { receipt })
        assertThrows(CancellationException::class.java) { bridge.stream("word", 0, 1f) { false } }
        assertFalse(receipt.validated())
        assertNull(receipt.streamWallMs())
        assertTrue(cancelled.get())
    }

    @Test fun aDelayedStreamRetainsItsOriginalReceiptAndInvalidPcmNeverValidatesANewReply() {
        val original = VoiceSpeechEnginePlaybackObservation()
        val replacement = VoiceSpeechEnginePlaybackObservation()
        var current = original
        val bridge = VoiceSpeechBenchmarkStreamingEngine(
            engine { emit ->
                current = replacement
                emit(VoicePcmChunk(floatArrayOf(.1f)))
            },
            { null },
            { current }
        )
        bridge.stream("word", 0, 1f) { true }
        assertTrue(original.validated())
        assertFalse(replacement.validated())
        assertEquals(0, replacement.callCount())

        val invalid = VoiceSpeechBenchmarkStreamingEngine(engine { emit -> emit(VoicePcmChunk(floatArrayOf(Float.NaN))) }, { null }, { replacement })
        assertThrows(IllegalArgumentException::class.java) { invalid.stream("word", 0, 1f) { true } }
        assertFalse(replacement.validated())
    }

    @Test fun failedDestructionPublishesAnExplicitQuarantineSignal() {
        val failed = AtomicBoolean()
        val delegate = object : VoicePhraseEngine {
            override val supportsStreaming = true
            override fun resetCancellation() = Unit
            override fun cancel() = Unit
            override fun close(): Unit = error("Native destructor failure")
        }
        val bridge = VoiceSpeechBenchmarkStreamingEngine(delegate, { null }, { null }) { failed.set(true) }
        assertThrows(IllegalStateException::class.java) { bridge.close() }
        assertTrue(failed.get())
    }

    @Test fun structuralPlaybackDoesNotSupplyNativeProviderOrCrossEngineQualification() {
        val row = VoiceSpeechPlaybackResult(
            VoiceSpeechBackend.PHONON_CPU, VoiceSpeechPlaybackState.COMPLETE,
            chunks = 2, totalAudioMs = 500, firstPresentedAudioMs = 100,
            protocolVersion = VoiceSpeechPlaybackProtocol.VERSION, manifestWordsExpected = 1,
            manifestWordsPrepared = 1, manifestWordsObserved = 1, unexpectedCaptionWords = 0,
            cleanupState = "RETIRED", activePlaybackUnderruns = 0, thermalBefore = 0, thermalAfter = 0,
            streamedAudioValidated = true, requestedDelegate = "CPU", observedProviderStatus = "NOT_EXPOSED_BY_SDK", engineStreamCalls = 1
        )
        assertFalse(row.executionVerified)
        assertTrue(row.streamingStructureVerified)
        assertEquals(VoiceSpeechPlaybackQuality.STREAMING_PLAYBACK_OBSERVED, row.quality)
        assertEquals(VoiceSpeechPlaybackQuality.SLOW_START, row.copy(firstPresentedAudioMs = 6_000).quality)
        assertEquals(VoiceSpeechPlaybackQuality.COMPLETE_WITH_STALLS, row.copy(bufferRestarts = 1).quality)
        assertEquals(VoiceSpeechPlaybackQuality.INCOMPLETE, row.copy(streamedAudioValidated = false).quality)
        assertEquals(VoiceSpeechPlaybackQuality.INCOMPLETE, row.copy(requestedBackend = VoiceSpeechBackend.QNN_GPU).quality)
    }

    @Test fun boundedSdkReceiptsPreserveExactFrameDurationWithoutInventingCaptionViolations() {
        val measurement = VoiceSpeechPlaybackMeasurement(VoiceSpeechBackend.PHONON_CPU, 0)
        repeat(1_000) { measurement.pcmReady(1, 128, VOICE_PCM_SAMPLE_RATE, null) }
        val result = measurement.result(VoiceSpeechPlaybackState.COMPLETE, 0)
        assertEquals(1_000, result.chunks)
        assertEquals(128, result.chunkRecords.size)
        assertEquals(872, result.omittedPcmRecords)
        assertEquals(5_333L, result.totalAudioMs)
        assertEquals(682L, result.chunkRecords.sumOf { it.audioMs })
        assertEquals(0, result.captionTimingViolations)
        assertEquals(0, result.validatedChunks)
        assertFalse(result.executionVerified)
    }

    @Test fun nativeReceiptsRetainTheirExactCoverageRequirement() {
        val measurement = VoiceSpeechPlaybackMeasurement(VoiceSpeechBackend.CPU, 0)
        repeat(129) { measurement.pcmReady(1, 600, VOICE_PCM_SAMPLE_RATE, null) }
        val result = measurement.result(VoiceSpeechPlaybackState.COMPLETE, 0)
        assertEquals(128, result.chunkRecords.size)
        assertEquals(0, result.omittedPcmRecords)
        assertEquals(1, result.captionTimingViolations)
    }

    @Test fun incrementalReceiptKeepsOneStreamAndDoesNotInventACompleteInputHash() {
        val receipt = VoiceSpeechEnginePlaybackObservation()
        val measurement = VoiceSpeechPlaybackMeasurement(VoiceSpeechBackend.PHONON_CPU, 0)
        val delegate = object : VoicePhraseEngine {
            override val supportsStreaming = true
            override val supportsIncrementalInput = true
            override fun streamInput(nextText: () -> String?, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                assertEquals("first ", nextText())
                assertTrue(emit(VoicePcmChunk(floatArrayOf(.1f))))
                assertEquals("second", nextText())
                assertTrue(emit(VoicePcmChunk(floatArrayOf(.1f))))
                assertNull(nextText())
            }
            override fun resetCancellation() = Unit
            override fun cancel() = Unit
            override fun close() = Unit
        }
        val bridge = VoiceSpeechBenchmarkStreamingEngine(delegate, { measurement }, { receipt })
        val deltas = listOf("first ", "second").iterator()
        measurement.attemptStarted("previous complete phrase")
        bridge.streamInput({ if (deltas.hasNext()) deltas.next() else null }, 0, 1f) {
            measurement.pcmReady(1, it.samples.size, it.sampleRate, null)
            true
        }
        assertTrue(receipt.validated())
        assertEquals(1, receipt.callCount())
        val result = measurement.result(VoiceSpeechPlaybackState.COMPLETE, 0)
        assertEquals(2, result.chunkRecords.size)
        assertTrue(result.chunkRecords.all { it.inputSha256 == null && it.inputBytes == null })
        assertNull(result.failedInputBytes)
    }

    private fun engine(cancelled: AtomicBoolean = AtomicBoolean(), produce: ((VoicePcmChunk) -> Boolean) -> Unit) = object : VoicePhraseEngine {
        override val supportsStreaming = true
        override val executionProvider = "mediapipe-phonon-cpu-requested"
        override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) = produce(emit)
        override fun resetCancellation() = Unit
        override fun cancel() {
            cancelled.set(true)
        }
        override fun close() = Unit
    }
}
