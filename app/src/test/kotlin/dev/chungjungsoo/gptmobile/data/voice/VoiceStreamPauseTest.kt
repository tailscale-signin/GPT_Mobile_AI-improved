package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStreamPauseTest {
    @Test fun shortAndMinimalKeepTheirExactQuietAllowanceAcrossChunks() {
        for (pause in listOf(VoicePauseDuration.SHORT, VoicePauseDuration.MINIMAL)) {
            val quietMs = requireNotNull(pause.maxQuietMs)
            val quietFrames = VOICE_PCM_SAMPLE_RATE * quietMs / 1_000
            val stream = VoiceStreamPause(VoiceStreamFrames("one"), VoiceQuietLimiter(VOICE_PCM_SAMPLE_RATE, quietMs))
            val first = floatArrayOf(.2f, -.4f) + FloatArray(quietFrames - 1)
            val second = FloatArray(100) + floatArrayOf(.5f, -.8f) + FloatArray(quietFrames + 17)
            assertSame(first, stream.accept(VoicePcmChunk(first)).samples)
            assertArrayEquals(FloatArray(1) + floatArrayOf(.5f, -.8f) + FloatArray(quietFrames), stream.accept(VoicePcmChunk(second)).samples, 0f)
            assertEquals(quietFrames * 2L + 4, stream.retainedFrames)
            stream.finish()
        }
    }

    @Test fun naturalPreservesSamplesFramesAndImmediateFutureCues() {
        assertNull(VoicePauseDuration.NATURAL.maxQuietMs)
        val stream = VoiceStreamPause(VoiceStreamFrames("Café 🙂"), null)
        val first = floatArrayOf(.25f, -0.0f, 0.0f)
        val initial = stream.accept(
            VoicePcmChunk(
                first,
                wordCues = listOf(
                    VoiceWordCue(0, 5, 0, 2, VoiceWordCueProvenance.NATIVE_DURATION),
                    VoiceWordCue(6, 10, 2, 8, VoiceWordCueProvenance.ESTIMATED)
                )
            )
        )
        assertSame(first, initial.samples)
        assertEquals(
            listOf(
                VoiceStreamCue(0..3, 0, 2, VoiceWordCueProvenance.NATIVE_DURATION, startByte = 0, endByte = 5),
                VoiceStreamCue(5..6, 2, 8, VoiceWordCueProvenance.ESTIMATED, startByte = 6, endByte = 10)
            ),
            initial.cues
        )
        assertEquals(3L, stream.retainedFrames)
        val remaining = FloatArray(5)
        assertSame(remaining, stream.accept(VoicePcmChunk(remaining)).samples)
        assertEquals(8L, stream.retainedFrames)
        stream.finish()
    }

    @Test fun futureCuesExposeObservedPrefixesAndFinalizeBeforeTheNextWordPrefix() {
        val stream = VoiceStreamPause(VoiceStreamFrames("one two"), VoiceQuietLimiter(1_000, 2))
        val captions = VoicePcmCaptions()
        val first = stream.accept(
            VoicePcmChunk(
                floatArrayOf(.4f, 0f, 0f),
                wordCues = listOf(VoiceWordCue(0, 3, 0, 4), VoiceWordCue(4, 7, 4, 9))
            )
        )
        assertEquals(listOf(VoiceStreamCue(0..2, 0, 3, VoiceWordCueProvenance.ESTIMATED, finalEnd = false, startByte = 0, endByte = 3)), first.cues)
        captions.appendStream(1, "one two", 0, first.cues, first.samples.size)
        assertEquals(0..2, captions.consumed(2, true)?.range)
        val second = stream.accept(VoicePcmChunk(floatArrayOf(0f, .5f)))
        assertArrayEquals(floatArrayOf(.5f), second.samples, 0f)
        assertEquals(
            listOf(
                VoiceStreamCue(0..2, 0, 3, VoiceWordCueProvenance.ESTIMATED, startByte = 0, endByte = 3),
                VoiceStreamCue(4..6, 3, 4, VoiceWordCueProvenance.ESTIMATED, finalEnd = false, startByte = 4, endByte = 7)
            ),
            second.cues
        )
        captions.appendStream(1, "one two", 0, second.cues, second.samples.size)
        assertEquals(4..6, captions.consumed(3, true)?.range)
        val third = stream.accept(VoicePcmChunk(FloatArray(4)))
        assertEquals(listOf(VoiceStreamCue(4..6, 3, 6, VoiceWordCueProvenance.ESTIMATED, startByte = 4, endByte = 7)), third.cues)
        captions.appendStream(1, "one two", 0, third.cues, third.samples.size)
        assertEquals(4..6, captions.consumed(4, true)?.range)
        assertEquals(IntRange.EMPTY, captions.consumed(6, true)?.range)
        assertEquals(6L, captions.streamStart())
        assertEquals(6L, stream.retainedFrames)
        stream.finish()
    }

    @Test fun aFullyTrimmedTailFinalizesThePublishedPrefixWithoutExtendingItsEnd() {
        val stream = VoiceStreamPause(VoiceStreamFrames("one"), VoiceQuietLimiter(1_000, 2))
        val captions = VoicePcmCaptions()
        val first = stream.accept(VoicePcmChunk(floatArrayOf(.1f, 0f, 0f), wordCues = listOf(VoiceWordCue(0, 3, 0, 5))))
        captions.appendStream(1, "one", 0, first.cues, first.samples.size)
        assertEquals(0..2, captions.consumed(3)?.range)
        val final = stream.accept(VoicePcmChunk(FloatArray(2)))
        assertTrue(final.samples.isEmpty())
        assertEquals(listOf(VoiceStreamCue(0..2, 0, 3, VoiceWordCueProvenance.ESTIMATED, startByte = 0, endByte = 3)), final.cues)
        captions.appendStream(1, "one", 0, final.cues, 0)
        assertEquals(IntRange.EMPTY, captions.consumed(3)?.range)
        assertNull(captions.consumed(3))
        stream.finish()
    }

    @Test fun fullyTrimmedAndMetadataOnlyChunksCanStillDeliverLateRetainedCues() {
        val stream = VoiceStreamPause(VoiceStreamFrames("one two"), VoiceQuietLimiter(1_000, 2))
        assertEquals(6, stream.accept(VoicePcmChunk(floatArrayOf(.1f, 0f, 0f, 0f, .2f, 0f, 0f))).samples.size)
        val trimmed = stream.accept(VoicePcmChunk(FloatArray(3), wordCues = listOf(VoiceWordCue(0, 3, 0, 4))))
        assertTrue(trimmed.samples.isEmpty())
        assertEquals(listOf(VoiceStreamCue(0..2, 0, 3, VoiceWordCueProvenance.ESTIMATED, startByte = 0, endByte = 3)), trimmed.cues)
        val metadata = stream.accept(
            VoicePcmChunk(FloatArray(0), wordCues = listOf(VoiceWordCue(4, 7, 4, 7, VoiceWordCueProvenance.NATIVE_DURATION)))
        )
        assertTrue(metadata.samples.isEmpty())
        assertEquals(listOf(VoiceStreamCue(4..6, 3, 6, VoiceWordCueProvenance.NATIVE_DURATION, startByte = 4, endByte = 7)), metadata.cues)
        assertEquals(6L, stream.retainedFrames)
        stream.finish()
    }

    @Test fun fullyRemovedCueDoesNotInventAnAudioFrameOrCaptionInterval() {
        val stream = VoiceStreamPause(VoiceStreamFrames("one"), VoiceQuietLimiter(1_000, 2))
        val first = stream.accept(VoicePcmChunk(floatArrayOf(.1f, 0f, 0f)))
        val removed = stream.accept(VoicePcmChunk(FloatArray(5), wordCues = listOf(VoiceWordCue(0, 3, 3, 7))))
        assertTrue(removed.samples.isEmpty())
        assertTrue(removed.cues.isEmpty())
        val captions = VoicePcmCaptions()
        captions.appendStream(1, "one", 0, first.cues, first.samples.size)
        captions.appendStream(1, "one", 0, removed.cues, removed.samples.size)
        assertEquals(3L, captions.streamStart())
        assertEquals(3L, stream.retainedFrames)
        stream.finish()
    }

    @Test fun quietStateSpansPhrasesButTheirCueMapsStartAtZero() {
        val limiter = VoiceQuietLimiter(1_000, 2)
        val first = VoiceStreamPause(VoiceStreamFrames("first"), limiter)
        assertEquals(3, first.accept(VoicePcmChunk(floatArrayOf(.3f, 0f, 0f))).samples.size)
        first.finish()
        val second = VoiceStreamPause(VoiceStreamFrames("one two"), limiter)
        val opening = second.accept(VoicePcmChunk(FloatArray(2) { .0002f }, wordCues = listOf(VoiceWordCue(0, 3, 0, 2))))
        assertTrue(opening.samples.isEmpty())
        assertTrue(opening.cues.isEmpty())
        val ending = second.accept(VoicePcmChunk(floatArrayOf(.5f, 0f, 0f, 0f), wordCues = listOf(VoiceWordCue(4, 7, 2, 6))))
        assertArrayEquals(floatArrayOf(.5f, 0f, 0f), ending.samples, 0f)
        assertEquals(listOf(VoiceStreamCue(4..6, 0, 3, VoiceWordCueProvenance.ESTIMATED, startByte = 4, endByte = 7)), ending.cues)
        assertEquals(3L, second.retainedFrames)
        second.finish()
        // Raw near-quiet PCM satisfies the existing signal validator but can be entirely
        // removed after the preceding phrase consumed the shared quiet allowance.
        val third = VoiceStreamPause(VoiceStreamFrames("hush"), limiter)
        val empty = third.accept(VoicePcmChunk(FloatArray(5) { .0002f }, wordCues = listOf(VoiceWordCue(0, 4, 0, 5))))
        assertTrue(empty.samples.isEmpty())
        assertTrue(empty.cues.isEmpty())
        assertEquals(0L, third.retainedFrames)
        third.finish()
    }

    @Test fun rawValidationRunsBeforeTheSharedLimiterOrRetainedCountersChange() {
        val invalid = listOf(
            VoicePcmChunk(FloatArray(5), sampleRate = 8_000) to "STREAM_SAMPLE_RATE",
            VoicePcmChunk(floatArrayOf(0f, 0f, 0f, Float.NaN)) to "STREAM_PCM_INVALID",
            VoicePcmChunk(FloatArray(VoiceStreamFrames.MAX_CHUNK_FRAMES + 1)) to "STREAM_FRAME_LIMIT",
            VoicePcmChunk(FloatArray(5), wordCues = listOf(VoiceWordCue(0, 4, 0, 5))) to "STREAM_CUE_BYTE_BOUNDARY"
        )
        for ((chunk, code) in invalid) {
            val limiter = VoiceQuietLimiter(1_000, 2)
            val rejected = VoiceStreamPause(VoiceStreamFrames("Café"), limiter)
            assertEquals(code, assertThrows(RuntimeException::class.java) { rejected.accept(chunk) }.message)
            assertEquals(0L, rejected.retainedFrames)
            val next = VoiceStreamPause(VoiceStreamFrames("ok"), limiter)
            assertArrayEquals(floatArrayOf(0f, 0f, .1f), next.accept(VoicePcmChunk(floatArrayOf(0f, 0f, 0f, .1f))).samples, 0f)
            next.finish()
        }
    }

    @Test fun rawCompletionStillRejectsSilentAndTruncatedStreams() {
        val silent = VoiceStreamPause(VoiceStreamFrames("one"), VoiceQuietLimiter(1_000, 2))
        silent.accept(VoicePcmChunk(FloatArray(5)))
        assertEquals("STREAM_SILENT", assertThrows(IllegalArgumentException::class.java) { silent.finish() }.message)
        val truncated = VoiceStreamPause(VoiceStreamFrames("one"), VoiceQuietLimiter(1_000, 2))
        truncated.accept(VoicePcmChunk(floatArrayOf(.1f, 0f, 0f, 0f, 0f), wordCues = listOf(VoiceWordCue(0, 3, 0, 8))))
        assertEquals("STREAM_INCOMPLETE", assertThrows(IllegalArgumentException::class.java) { truncated.finish() }.message)
    }

    @Test fun lateCueRanksCoverZeroAndWordEdgesWithoutCountingLaterDeletions() {
        val stream = VoiceStreamPause(VoiceStreamFrames("a b c d"), VoiceQuietLimiter(VOICE_PCM_SAMPLE_RATE, 0))
        val first = FloatArray(65).also {
            it[0] = .1f
            it[63] = .2f
        }
        val second = FloatArray(63).also {
            it[0] = .3f
            it[62] = .4f
        }
        assertArrayEquals(floatArrayOf(.1f, .2f), stream.accept(VoicePcmChunk(first)).samples, 0f)
        assertArrayEquals(floatArrayOf(.3f, .4f), stream.accept(VoicePcmChunk(second)).samples, 0f)
        val late = stream.accept(
            VoicePcmChunk(
                FloatArray(0),
                wordCues = listOf(
                    VoiceWordCue(0, 1, 0, 63),
                    VoiceWordCue(2, 3, 63, 64),
                    VoiceWordCue(4, 5, 64, 65),
                    VoiceWordCue(6, 7, 65, 128)
                )
            )
        )
        assertEquals(
            listOf(
                VoiceStreamCue(0..0, 0, 1, VoiceWordCueProvenance.ESTIMATED, startByte = 0, endByte = 1),
                VoiceStreamCue(2..2, 1, 2, VoiceWordCueProvenance.ESTIMATED, startByte = 2, endByte = 3),
                VoiceStreamCue(6..6, 2, 4, VoiceWordCueProvenance.ESTIMATED, startByte = 6, endByte = 7)
            ),
            late.cues
        )
        assertEquals(4L, stream.retainedFrames)
        stream.finish()
    }

    @Test fun maximumRawDurationStillBoundsMostlyTrimmedAudioAndMapsItsFinalBoundary() {
        val raw = VoiceStreamFrames("one")
        val stream = VoiceStreamPause(raw, VoiceQuietLimiter(VOICE_PCM_SAMPLE_RATE, 70))
        val quiet = FloatArray(VoiceStreamFrames.MAX_CHUNK_FRAMES)
        val first = quiet.copyOf().also { it[0] = .1f }
        val opening = stream.accept(VoicePcmChunk(first, wordCues = listOf(VoiceWordCue(0, 3, 0, VoiceStreamFrames.MAX_STREAM_FRAMES))))
        assertEquals(1_681, opening.samples.size)
        assertEquals(listOf(VoiceStreamCue(0..2, 0, 1_681, VoiceWordCueProvenance.ESTIMATED, finalEnd = false, startByte = 0, endByte = 3)), opening.cues)
        repeat(3) {
            val middle = stream.accept(VoicePcmChunk(quiet))
            assertTrue(middle.samples.isEmpty())
            assertEquals(opening.cues, middle.cues)
        }
        val last = stream.accept(VoicePcmChunk(quiet))
        assertTrue(last.samples.isEmpty())
        assertEquals(listOf(VoiceStreamCue(0..2, 0, 1_681, VoiceWordCueProvenance.ESTIMATED, startByte = 0, endByte = 3)), last.cues)
        assertEquals(VoiceStreamFrames.MAX_STREAM_FRAMES, raw.frames)
        assertEquals(1_681L, stream.retainedFrames)
        stream.finish()
        assertEquals("STREAM_FRAME_LIMIT", assertThrows(IllegalArgumentException::class.java) { stream.accept(VoicePcmChunk(floatArrayOf(0f))) }.message)
        assertEquals(1_681L, stream.retainedFrames)
    }

    @Test fun fullyTrimmedCallbackStillCancelsTheProducerImmediately() {
        val limiter = VoiceQuietLimiter(1_000, 2)
        limiter.filter(FloatArray(2))
        val stream = VoiceStreamPause(VoiceStreamFrames("one"), limiter)
        var active = true
        var cancelled = false
        var closed = false
        var continued = false
        val engine = object : VoicePhraseEngine {
            override val supportsStreaming = true
            override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
                emit(VoicePcmChunk(FloatArray(3) { .0002f }))
                continued = true
                emit(VoicePcmChunk(floatArrayOf(.1f)))
            }
            override fun resetCancellation() = Unit
            override fun cancel() {
                cancelled = true
            }
            override fun close() {
                closed = true
            }
        }
        assertThrows(CancellationException::class.java) {
            consumeVoiceStream(engine, "one", 0, 1f, { active }) { chunk ->
                val retained = stream.accept(chunk)
                assertTrue(retained.samples.isEmpty())
                active = false
                active
            }
        }
        assertEquals(0L, stream.retainedFrames)
        assertTrue(cancelled)
        assertFalse(continued)
        assertFalse(closed)
    }
}
