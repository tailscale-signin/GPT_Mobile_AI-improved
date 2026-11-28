package dev.chungjungsoo.gptmobile.data.voice

import dev.melo.kokoro.StreamingAsr
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SherpaNativeSpeechDecoderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun endpointRequestsDrainAndOnlyFinishPublishesTerminalTiming() {
        val native = FakeNative()
        val decoder = decoder(native)
        val events = mutableListOf<LocalSpeechEvent>()
        decoder.start(File("model"), events::add)
        native.next = result("hello", listOf("▁hello"), listOf(.1f), endpoint = true, frames = 8_000)
        decoder.accept(FloatArray(640))
        assertFalse(checkNotNull(events.filterIsInstance<LocalSpeechEvent.Final>().single().transcript).isFinal)

        // PCM already queued by capture must still reach native inference.
        native.next = result("hello world", listOf("▁hello", "▁world"), listOf(.1f, .6f), frames = 16_000)
        decoder.accept(FloatArray(640))
        decoder.finish()
        decoder.finish()
        val finals = events.filterIsInstance<LocalSpeechEvent.Final>()
        assertEquals(2, finals.size)
        val final = checkNotNull(finals.last().transcript)
        assertTrue(final.isFinal)
        assertEquals("hello world", final.text)
        assertEquals(16_000L, final.audioFrames)
        assertEquals(listOf(1_600L, 9_600L), final.timings.map { it.startFrame })
        assertTrue(final.timings.all { it.endFrame == null })
        val revisions = events.mapNotNull {
            when (it) {
                is LocalSpeechEvent.Partial -> it.transcript?.revision
                is LocalSpeechEvent.Final -> it.transcript?.revision
                else -> null
            }
        }
        assertTrue(revisions.zipWithNext().all { (previous, next) -> next > previous })
        assertEquals(2, native.accepts)
        assertEquals(1, native.finishes)
        decoder.close()
    }

    @Test fun prewarmingDoesNotVerifyExecutionAndResidentReuseStartsAnIndependentClock() {
        val native = FakeNative()
        var preparations = 0
        val pack = checkNotNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.ZIPFORMER_EN, VoiceRecognitionCompute.CPU))
        val decoder = SherpaNativeSpeechDecoder(pack, pack.compute, File(temporary.root, "cache"), 2, { "libraries" }, {
            preparations++
            native
        })
        val first = mutableListOf<LocalSpeechEvent>()
        decoder.start(File("model"), first::add)
        assertEquals("false", decoder.executionEvidence()["recognitionExecutionVerified"])
        native.next = result("hello", listOf("▁hello"), listOf(.1f), frames = 8_000)
        decoder.accept(FloatArray(640))
        decoder.finish()
        val firstTranscript = checkNotNull(first.filterIsInstance<LocalSpeechEvent.Final>().single().transcript)
        assertEquals("true", decoder.executionEvidence()["recognitionExecutionVerified"])
        decoder.releaseStream()
        val second = mutableListOf<LocalSpeechEvent>()
        decoder.start(File("model"), second::add)
        assertEquals("false", decoder.executionEvidence()["recognitionExecutionVerified"])
        native.next = result("hello", listOf("▁hello"), listOf(.1f), frames = 8_000)
        decoder.accept(FloatArray(640))
        decoder.finish()
        val secondTranscript = checkNotNull(second.filterIsInstance<LocalSpeechEvent.Final>().single().transcript)
        assertNotEquals(firstTranscript.streamId, secondTranscript.streamId)
        assertEquals(firstTranscript.timings, secondTranscript.timings)
        assertEquals(1, preparations)
        assertEquals(1, native.resets)
        decoder.close()
        decoder.close()
        assertEquals(1, native.closes)
    }

    @Test fun explicitGpuFailureIsActionableAndCannotBecomeCpuSuccess() {
        val native = FakeNative()
        val pack = checkNotNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.NEMOTRON_3_5, VoiceRecognitionCompute.GPU))
        val decoder = decoder(native, pack)
        decoder.configureRecognition("ja", listOf("custom vocabulary"))
        decoder.start(File("model")) {}
        native.failure = StreamingAsr.Failure("ASR_ACCELERATOR_NOT_EXECUTED")
        val failure = assertThrows(VoiceRecognitionRuntimeException::class.java) { decoder.accept(FloatArray(640)) }
        assertEquals("ASR_ACCELERATOR_NOT_EXECUTED", failure.code)
        val report = decoder.executionEvidence()
        assertEquals("GPU", report["recognitionRequestedProvider"])
        assertEquals("not_executed", report["recognitionActualProvider"])
        assertEquals("false", report["recognitionExecutionVerified"])
        assertEquals("not_supported_by_this_pack", report["recognitionContextualBias"])
        assertEquals(failure.code, report["recognitionFailure"])
        decoder.cancel()
        assertEquals(1, native.cancellations)
        decoder.close()
    }

    @Test fun unsupportedTimingsRetainTheTranscriptAndPromptAliasesRemainExplicit() {
        val native = FakeNative()
        val decoder = decoder(native)
        val events = mutableListOf<LocalSpeechEvent>()
        decoder.start(File("model"), events::add)
        native.next = result("hello", listOf("wrong"), listOf(.1f), frames = 8_000)
        decoder.accept(FloatArray(640))
        decoder.finish()
        val final = checkNotNull(events.filterIsInstance<LocalSpeechEvent.Final>().single().transcript)
        assertEquals("hello", final.text)
        assertTrue(final.timings.isEmpty())
        assertEquals(VoiceRecognitionTimingIssue.SOURCE_MISMATCH, final.timingIssue)
        assertEquals("ja-JP", nemotronPromptLanguage("ja"))
        assertEquals("zh-TW", nemotronPromptLanguage("zh-Hant"))
        assertEquals("fr-CA", nemotronPromptLanguage("fr_CA"))
        assertEquals("nb-NO", nemotronPromptLanguage("nb"))
        assertThrows(VoiceRecognitionRuntimeException::class.java) { nemotronPromptLanguage("el") }
        decoder.close()
    }

    @Test fun cancellationInvalidatesCachedExecutionEvidenceBeforeTheWorkerReturns() {
        val native = FakeNative()
        val decoder = decoder(native)
        decoder.start(File("model")) {}
        native.next = result("hello", listOf("▁hello"), listOf(.1f), frames = 8_000)
        decoder.accept(FloatArray(640))
        assertEquals("true", decoder.executionEvidence()["recognitionExecutionVerified"])

        decoder.cancel()

        assertEquals(1, native.cancellations)
        assertEquals("true", decoder.executionEvidence()["recognitionCancelled"])
        assertEquals("false", decoder.executionEvidence()["recognitionExecutionVerified"])
        decoder.close()
    }

    @Test fun cancellationDuringModelConstructionRetiresTheNewHandleWithoutStartingCapture() {
        val native = FakeNative()
        val pack = checkNotNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.ZIPFORMER_EN, VoiceRecognitionCompute.CPU))
        lateinit var decoder: SherpaNativeSpeechDecoder
        decoder = SherpaNativeSpeechDecoder(pack, pack.compute, File(temporary.root, "cache"), 2, { "libraries" }, {
            decoder.cancel()
            native
        })

        val failure = assertThrows(VoiceRecognitionRuntimeException::class.java) { decoder.start(File("model")) {} }

        assertEquals("ASR_CANCELLED", failure.code)
        assertEquals(1, native.cancellations)
        assertEquals("false", decoder.executionEvidence()["recognitionExecutionVerified"])
        decoder.close()
        assertEquals(1, native.closes)
    }

    @Test fun failedCloseRetainsOwnerAndCannotRetryNativeFreeOrReuse() {
        val native = FakeNative()
        val decoder = decoder(native)
        decoder.start(File("model")) {}
        native.next = result("hello", listOf("▁hello"), listOf(.1f), frames = 8_000)
        decoder.accept(FloatArray(640))
        native.closeFailure = StreamingAsr.Failure("ASR_RETIREMENT_UNCONFIRMED")

        val failure = assertThrows(VoiceRecognitionRuntimeException::class.java) { decoder.close() }

        assertEquals("ASR_RETIREMENT_UNCONFIRMED", failure.code)
        assertSame(failure, assertThrows(VoiceRecognitionRuntimeException::class.java) { decoder.close() })
        assertSame(failure, assertThrows(VoiceRecognitionRuntimeException::class.java) { decoder.start(File("model")) {} })
        decoder.cancel()
        assertEquals(1, native.closes)
        assertEquals(0, native.resets)
        assertEquals(0, native.cancellations)
        assertEquals("false", decoder.executionEvidence()["recognitionExecutionVerified"])
        assertEquals("ASR_RETIREMENT_UNCONFIRMED", decoder.executionEvidence()["recognitionFailure"])
    }

    @Test fun failedConstructionRollbackRemainsUnretiredWithoutAJavaHandle() {
        val pack = checkNotNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.NEMOTRON_3_5, VoiceRecognitionCompute.NPU))
        var creations = 0
        val decoder = SherpaNativeSpeechDecoder(pack, pack.compute, File(temporary.root, "cache"), 2, { "libraries" }, {
            creations++
            throw StreamingAsr.Failure("ASR_RETIREMENT_UNCONFIRMED")
        })

        val failure = assertThrows(VoiceRecognitionRuntimeException::class.java) { decoder.start(File("model")) {} }

        assertEquals("ASR_RETIREMENT_UNCONFIRMED", failure.code)
        assertSame(failure, assertThrows(VoiceRecognitionRuntimeException::class.java) { decoder.close() })
        assertSame(failure, assertThrows(VoiceRecognitionRuntimeException::class.java) { decoder.start(File("model")) {} })
        assertEquals(1, creations)
        assertEquals("false", decoder.executionEvidence()["recognitionExecutionVerified"])
    }

    @Test fun safeInitializationFailureDoesNotInventAnUnretiredNativeOwner() {
        val pack = checkNotNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.ZIPFORMER_EN, VoiceRecognitionCompute.CPU))
        val decoder = SherpaNativeSpeechDecoder(pack, pack.compute, File(temporary.root, "cache"), 2, { "libraries" }, {
            throw StreamingAsr.Failure("ASR_INITIALIZATION_FAILED")
        })

        val failure = assertThrows(VoiceRecognitionRuntimeException::class.java) { decoder.start(File("model")) {} }

        assertEquals("ASR_INITIALIZATION_FAILED", failure.code)
        decoder.close()
        decoder.close()
    }

    @Test fun closeLinkageFailureIsUncertainAndDoesNotResurrectTheHandle() {
        val native = FakeNative().apply { closeFailure = UnsatisfiedLinkError("fixture unavailable") }
        val decoder = decoder(native)
        decoder.start(File("model")) {}

        val failure = assertThrows(VoiceRecognitionRuntimeException::class.java) { decoder.close() }

        assertEquals("ASR_RETIREMENT_UNCONFIRMED", failure.code)
        assertSame(failure, assertThrows(VoiceRecognitionRuntimeException::class.java) { decoder.close() })
        assertEquals(1, native.closes)
    }

    private fun decoder(
        native: FakeNative,
        pack: VoiceRecognitionModelPack = checkNotNull(VoiceRecognitionModelCatalog.pack(VoiceRecognitionModel.ZIPFORMER_EN, VoiceRecognitionCompute.CPU))
    ) = SherpaNativeSpeechDecoder(pack, pack.compute, File(temporary.root, "cache"), 2, { "libraries" }, { native })

    private class FakeNative : SherpaSpeechNative {
        var next = result("", emptyList(), emptyList(), frames = 0)
        var failure: StreamingAsr.Failure? = null
        var closeFailure: Throwable? = null
        var accepts = 0
        var finishes = 0
        var resets = 0
        var closes = 0
        var cancellations = 0
        override fun accept(samples: FloatArray): StreamingAsr.Result {
            failure?.let { throw it }
            accepts++
            return next
        }
        override fun finish(): StreamingAsr.Result {
            finishes++
            return next
        }
        override fun reset(language: String) {
            resets++
            next = result("", emptyList(), emptyList(), frames = 0)
        }
        override fun evidence() = StreamingAsr.Evidence(
            "cpu", if (next.acceptedAudioFrames > 0) "cpu" else "not_executed", if (next.acceptedAudioFrames > 0) 1 else 0,
            next.acceptedAudioFrames, LongArray(3), LongArray(3), LongArray(3), next.acceptedAudioFrames > 0, "test"
        )
        override fun cancel() {
            cancellations++
        }
        override fun close() {
            closes++
            closeFailure?.let { throw it }
        }
    }

    companion object {
        private fun result(text: String, tokens: List<String>, times: List<Float>, endpoint: Boolean = false, frames: Long) =
            StreamingAsr.Result(text, tokens.toTypedArray(), times.toFloatArray(), 0f, 0, endpoint, frames)
    }
}
