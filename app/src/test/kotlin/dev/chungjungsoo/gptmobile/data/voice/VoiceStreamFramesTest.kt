package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStreamFramesTest {
    @Test fun utf8CuesPreserveAccentsAndSurrogatePairsAcrossPcmChunks() {
        val receiver = VoiceStreamFrames("é 🙂 go")
        val mapped = receiver.accept(
            VoicePcmChunk(
                FloatArray(2_400) { .1f },
                wordCues = listOf(
                    VoiceWordCue(0, 2, 0, 1_200),
                    VoiceWordCue(3, 7, 1_200, 4_800)
                )
            )
        )
        assertEquals(listOf(0..0, 2..3), mapped.map { it.range })
        assertEquals(2_400L, receiver.frames)
        receiver.accept(VoicePcmChunk(FloatArray(2_400) { .1f }))
        receiver.finish()
        assertTrue(mapped.all { it.provenance == VoiceWordCueProvenance.ESTIMATED })
    }

    @Test fun byteOffsetsInsideAnEncodedCodePointAreRejected() {
        assertThrows(IllegalStateException::class.java) {
            VoiceStreamFrames("é").accept(VoicePcmChunk(floatArrayOf(.1f), wordCues = listOf(VoiceWordCue(1, 2, 0, 1))))
        }
    }

    @Test fun normalizedGatewayWordsMapBackToTheSameDisplayedNumber() {
        val receiver = VoiceStreamFrames("twenty one", listOf(0..1, 0..1))
        val mapped = receiver.accept(
            VoicePcmChunk(
                FloatArray(2_400) { .1f },
                wordCues = listOf(
                    VoiceWordCue(0, 6, 0, 1_200, VoiceWordCueProvenance.NATIVE_DURATION),
                    VoiceWordCue(7, 10, 1_200, 2_400, VoiceWordCueProvenance.NATIVE_DURATION)
                )
            )
        )
        val captions = VoicePcmCaptions()
        captions.appendStream(0, "21", captions.streamStart(), mapped, 2_400)
        assertEquals(0..1, captions.consumed(600)?.range)
        assertEquals(0..1, captions.consumed(1_800)?.range)
        assertEquals(VoiceWordCueProvenance.NATIVE_DURATION, captions.active()?.provenance)
        receiver.finish()
    }

    @Test fun futureCuesWaitForPresentedPcmAndKeepEstimatedProvenance() {
        val captions = VoicePcmCaptions()
        captions.appendStream(
            0,
            "one two",
            0,
            listOf(
                VoiceStreamCue(0..2, 0, 1_200, VoiceWordCueProvenance.ESTIMATED),
                VoiceStreamCue(4..6, 1_200, 2_400, VoiceWordCueProvenance.ESTIMATED)
            ),
            600
        )
        assertEquals(0..2, captions.consumed(500)?.range)
        assertEquals(VoiceWordCueProvenance.ESTIMATED, captions.active()?.provenance)
        assertEquals(null, captions.consumed(1_500, advancing = false))
        captions.appendStream(0, "one two", 0, emptyList(), 1_800)
        assertEquals(4..6, captions.consumed(1_500)?.range)
        assertEquals(IntRange.EMPTY, captions.consumed(2_400)?.range)
    }

    @Test fun invalidRatesHugeChunksNonFinitePcmAndTruncatedCuesFailExplicitly() {
        assertThrows(IllegalArgumentException::class.java) { VoiceStreamFrames("one").accept(VoicePcmChunk(floatArrayOf(.1f), 16_000)) }
        assertThrows(IllegalArgumentException::class.java) { VoiceStreamFrames("one").accept(VoicePcmChunk(FloatArray(VoiceStreamFrames.MAX_CHUNK_FRAMES + 1))) }
        assertThrows(IllegalArgumentException::class.java) { VoiceStreamFrames("one").accept(VoicePcmChunk(floatArrayOf(Float.NaN))) }
        val truncated = VoiceStreamFrames("one")
        truncated.accept(VoicePcmChunk(floatArrayOf(.1f), wordCues = listOf(VoiceWordCue(0, 3, 0, 100))))
        assertThrows(IllegalArgumentException::class.java, truncated::finish)
    }

    @Test fun onlyTheOpenWordMayExtendAndItsPrefixMustBeBackedByPcm() {
        val open = VoiceWordCue(0, 3, 0, 1, finalEnd = false)
        for (invalid in listOf(open.copy(startByte = 1), open.copy(endByte = 4), open.copy(endFrame = 5), open.copy(provenance = VoiceWordCueProvenance.NATIVE_DURATION))) {
            val receiver = VoiceStreamFrames("one!")
            receiver.accept(VoicePcmChunk(floatArrayOf(.1f), wordCues = listOf(open)))
            assertThrows(IllegalArgumentException::class.java) { receiver.accept(VoicePcmChunk(floatArrayOf(.1f), wordCues = listOf(invalid))) }
        }
        val receiver = VoiceStreamFrames("one")
        receiver.accept(VoicePcmChunk(floatArrayOf(.1f), wordCues = listOf(open)))
        assertThrows(IllegalArgumentException::class.java, receiver::finish)
    }

    @Test fun firstPcmReachesTheConsumerBeforeTheProducerCompletes() {
        val engine = TestStreamEngine()
        var chunks = 0
        consumeVoiceStream(engine, "one two", 3, 1.25f, { true }) {
            assertFalse(engine.finished)
            chunks++
            true
        }
        assertEquals(2, chunks)
        assertTrue(engine.finished)
        assertFalse(engine.closed)
    }

    @Test fun rejectionCancelsImmediatelyWithoutClosingAnInFlightProducer() {
        val engine = TestStreamEngine()
        var chunks = 0
        assertThrows(CancellationException::class.java) {
            consumeVoiceStream(engine, "one two", 3, 1f, { true }) {
                chunks++
                false
            }
        }
        assertEquals(1, chunks)
        assertTrue(engine.cancelled)
        assertFalse(engine.finished)
        assertFalse(engine.closed)
        engine.close()
        assertTrue(engine.closed)
    }

    private class TestStreamEngine : VoicePhraseEngine {
        override val supportsStreaming = true
        var cancelled = false
        var closed = false
        var finished = false
        override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
            repeat(2) { emit(VoicePcmChunk(FloatArray(100) { .1f })) }
            finished = true
        }
        override fun cancel() {
            cancelled = true
        }
        override fun resetCancellation() {
            cancelled = false
        }
        override fun close() {
            closed = true
        }
    }
}
