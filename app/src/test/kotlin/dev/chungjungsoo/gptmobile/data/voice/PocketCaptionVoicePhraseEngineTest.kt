package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketCaptionVoicePhraseEngineTest {
    private class Source(private val produce: (String, (VoicePcmChunk) -> Boolean) -> Unit) : VoicePhraseEngine {
        val texts = ArrayList<String>()
        var closed = false
        var cancelled = false
        override val supportsStreaming = true
        override fun stream(text: String, speaker: Int, speed: Float, emit: (VoicePcmChunk) -> Boolean) {
            texts += text
            produce(text, emit)
        }
        override fun resetCancellation() {
            cancelled = false
        }
        override fun cancel() {
            cancelled = true
        }
        override fun close() {
            closed = true
        }
    }

    private fun aligner(block: (String, FloatArray) -> PocketCaptionAlignment) = object : PocketCaptionAlignmentEngine {
        override fun align(text: String, samples: FloatArray, checkActive: () -> Unit): PocketCaptionAlignment {
            checkActive()
            return block(text, samples).also { checkActive() }
        }
        override fun close() = Unit
    }

    @Test
    fun captionsOffAndMissingPackPreserveImmediateUnsplitPcm() {
        for (enabled in listOf(false, true)) {
            val original = VoicePcmChunk(floatArrayOf(.1f, -.2f))
            val source = Source { _, emit -> emit(original) }
            var opens = 0
            val engine = PocketCaptionVoicePhraseEngine(source, {
                opens++
                null
            })
            engine.setCaptionAlignmentEnabled(enabled)
            val text = "one two three four five six seven eight nine ten"
            engine.stream(text, 0, 1f) {
                assertTrue(it === original)
                true
            }
            assertEquals(listOf(text), source.texts)
            assertEquals(if (enabled) 1 else 0, opens)
            assertEquals(if (enabled) PocketCaptionStatus.PACK_UNAVAILABLE else PocketCaptionStatus.DISABLED, engine.captionStatus)
            engine.close()
        }
    }

    @Test
    fun finalFutureCuesMapThroughRealPauseFilteringAndFrameValidation() {
        val pcm = FloatArray(20_000) { if (it < 4_000 || it >= 16_000) .25f else 0f }
        val source = Source { _, emit -> emit(VoicePcmChunk(pcm)) }
        val engine = PocketCaptionVoicePhraseEngine(source, {
            aligner { text, audio ->
                assertArrayEquals(pcm, audio, 0f)
                PocketCaptionPolicy.align(text, audio.size, PocketCaptionTranscript(listOf(PocketCaptionWord("Hello", 0.0, 4_000.0 / 24_000), PocketCaptionWord("world", 16_000.0 / 24_000, 20_000.0 / 24_000)), true))
            }
        })
        engine.setCaptionAlignmentEnabled(true)
        val timeline = VoiceStreamPause(VoiceStreamFrames("Hello world"), VoiceQuietLimiter(24_000, 100))
        val raw = ArrayList<Float>()
        val retained = ArrayList<Float>()
        val finalCues = ArrayList<VoiceStreamCue>()
        engine.stream("Hello world", 0, .5f) { chunk ->
            assertTrue(chunk.samples.size <= PocketCaptionPolicy.OUTPUT_PACKET_FRAMES)
            raw.addAll(chunk.samples.toList())
            val mapped = timeline.accept(chunk)
            retained.addAll(mapped.samples.toList())
            finalCues.addAll(mapped.cues.filter { it.finalEnd })
            true
        }
        timeline.finish()
        assertArrayEquals(pcm, raw.toFloatArray(), 0f)
        assertTrue(retained.size < pcm.size)
        assertEquals(listOf(0..4, 6..10), finalCues.map { it.range })
        assertEquals((retained.size - 4_000).toLong(), finalCues.last().startFrame)
        assertEquals(retained.size.toLong(), finalCues.last().endFrame)
        assertTrue(finalCues.all { it.provenance == VoiceWordCueProvenance.ASR_ALIGNMENT })
        assertEquals(PocketCaptionStatus.ALIGNED, engine.captionStatus)
        engine.close()
    }

    @Test
    fun subphrasesKeepCumulativeUtf8AndAudioOffsetsWithoutChangingSamples() {
        val sourcePcm = ArrayList<Float>()
        val source = Source { text, emit ->
            val pcm = FloatArray(12_000) { text.length / 100f }
            sourcePcm.addAll(pcm.toList())
            emit(VoicePcmChunk(pcm))
        }
        val engine = PocketCaptionVoicePhraseEngine(source, {
            aligner { text, audio ->
                val tokens = Regex("\\S+").findAll(text).map { it.value }.toList()
                // Controlled fake boundaries exercise transport, not an acoustic accuracy claim.
                PocketCaptionPolicy.align(text, audio.size, PocketCaptionTranscript(tokens.mapIndexed { index, word -> PocketCaptionWord(word, index * .05, (index + 1) * .05) }, true))
            }
        })
        engine.setCaptionAlignmentEnabled(true)
        val text = "Café one two three four five six seven eight nine"
        val chunks = ArrayList<VoicePcmChunk>()
        engine.stream(text, 0, 1f) {
            chunks += it
            true
        }
        assertEquals(text, source.texts.joinToString(""))
        assertEquals(2, source.texts.size)
        assertArrayEquals(sourcePcm.toFloatArray(), chunks.flatMap { it.samples.toList() }.toFloatArray(), 0f)
        val cues = chunks.flatMap { it.wordCues }
        assertEquals(10, cues.size)
        assertEquals(12_000L, cues[8].startFrame)
        assertEquals("eight", text.toByteArray().copyOfRange(cues[8].startByte, cues[8].endByte).decodeToString())
        val frames = VoiceStreamFrames(text)
        chunks.forEach { frames.accept(it) }
        frames.finish()
        engine.close()
    }

    @Test
    fun mismatchAndAudioLimitReleaseEverySampleExactlyOnceWithoutCues() {
        for (overflow in listOf(false, true)) {
            val size = if (overflow) PocketCaptionPolicy.MAX_PHRASE_FRAMES + 1 else 4_000
            val pcm = FloatArray(size) { .125f }
            val source = Source { _, emit ->
                emit(VoicePcmChunk(pcm.copyOfRange(0, size - 1)))
                emit(VoicePcmChunk(pcm.copyOfRange(size - 1, size)))
            }
            var calls = 0
            val engine = PocketCaptionVoicePhraseEngine(source, {
                aligner { _, _ ->
                    calls++
                    PocketCaptionAlignment(PocketCaptionStatus.TEXT_MISMATCH)
                }
            })
            engine.setCaptionAlignmentEnabled(true)
            val observed = ArrayList<Float>()
            engine.stream("hello", 0, .5f) {
                assertTrue(it.wordCues.isEmpty())
                observed.addAll(it.samples.toList())
                true
            }
            assertArrayEquals(pcm, observed.toFloatArray(), 0f)
            assertEquals(if (overflow) 0 else 1, calls)
            assertEquals(if (overflow) PocketCaptionStatus.AUDIO_LIMIT else PocketCaptionStatus.TEXT_MISMATCH, engine.captionStatus)
            engine.close()
        }
    }

    @Test
    fun cancellationDuringAlignmentSuppressesPcmAndCloseWaitsForNativeReturn() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val source = Source { _, emit -> emit(VoicePcmChunk(FloatArray(4_000) { .2f })) }
        val engine = PocketCaptionVoicePhraseEngine(source, {
            object : PocketCaptionAlignmentEngine {
                override fun align(text: String, samples: FloatArray, checkActive: () -> Unit): PocketCaptionAlignment {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    checkActive()
                    return PocketCaptionAlignment(PocketCaptionStatus.TEXT_MISMATCH)
                }
                override fun close() {
                    closed.countDown()
                }
            }
        })
        engine.setCaptionAlignmentEnabled(true)
        val worker = Thread {
            try {
                engine.stream("hello", 0, 1f) { error("Cancelled PCM must not escape") }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.also(Thread::start)
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        assertEquals(VoiceSpeechStage.VALIDATING, engine.executionProgress?.stage)
        assertTrue(engine.executionProgress?.active == true)
        engine.cancel()
        val closer = Thread { engine.close() }.also(Thread::start)
        assertFalse(closed.await(30, TimeUnit.MILLISECONDS))
        assertFalse(source.closed)
        release.countDown()
        worker.join(3_000)
        closer.join(3_000)
        assertTrue(failure.get() is CancellationException)
        assertTrue(source.closed)
        assertEquals(0L, closed.count)
    }

    @Test
    fun failedAlignmentRetirementQuarantinesWrapperButStillClosesDelegate() {
        val source = Source { _, emit -> emit(VoicePcmChunk(FloatArray(4_000) { .2f })) }
        var quarantined: Any? = null
        val engine = PocketCaptionVoicePhraseEngine(source, {
            object : PocketCaptionAlignmentEngine {
                override fun align(text: String, samples: FloatArray, checkActive: () -> Unit) = PocketCaptionAlignment(PocketCaptionStatus.TEXT_MISMATCH)
                override fun close() = throw IllegalStateException("unconfirmed native close")
            }
        }, { quarantined = it })
        engine.setCaptionAlignmentEnabled(true)
        engine.stream("hello", 0, 1f) { true }
        assertThrows(PocketCaptionCleanupFailure::class.java) { engine.close() }
        assertTrue(quarantined === engine)
        assertTrue(source.closed)
        assertThrows(IllegalStateException::class.java) { engine.resetCancellation() }
    }
}
