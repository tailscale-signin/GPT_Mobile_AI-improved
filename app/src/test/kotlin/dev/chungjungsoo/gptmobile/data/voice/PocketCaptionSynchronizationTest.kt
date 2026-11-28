package dev.chungjungsoo.gptmobile.data.voice

import com.google.mediapipe.tasks.audio.texttospeech.WordTimestamp
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** These tests preserve supplied SDK timestamps; synthetic PCM does not establish acoustic alignment. */
class PocketCaptionSynchronizationTest {
    @Test fun aShorterFinalEstimateReachesQueuedCaptionsAtEveryTempoAndPauseSetting() {
        val original = WordTimestamp("Café", 0, 5, .15f, .85f)
        val corrected = original.copy(endSec = .55f)
        for (speed in listOf(.75f, 1f, 1.25f, 1.5f, 2f)) {
            for (trimQuiet in listOf(false, true)) {
                val expected = queuedCaptions(speed, trimQuiet, corrected)
                val revised = queuedCaptions(speed, trimQuiet, original, corrected)
                assertEquals("tempo=$speed trim=$trimQuiet", expected.map { it.frame to it.endFrame }, revised.map { it.frame to it.endFrame })
                assertEquals(listOf(0..3), revised.map { it.range })
                assertTrue(revised.all { it.provenance == VoiceWordCueProvenance.ESTIMATED })
            }
        }
    }

    @Test fun aShorterEstimateReleasesTheFollowingWordFromTheSameOrALaterPacket() {
        for (samePacket in listOf(false, true)) {
            val mapper = MediaPipeWordCueMapper("one two")
            val one = WordTimestamp("one", 0, 3, 0f, .8f)
            val two = WordTimestamp("two", 4, 7, .5f, .9f)
            mapper.accept(listOf(one), VOICE_PCM_SAMPLE_RATE.toLong())
            val updates = if (samePacket) {
                mapper.accept(listOf(one.copy(endSec = .4f), two), VOICE_PCM_SAMPLE_RATE.toLong())
            } else {
                mapper.accept(listOf(one.copy(endSec = .4f)), VOICE_PCM_SAMPLE_RATE.toLong()) +
                    mapper.accept(listOf(two), VOICE_PCM_SAMPLE_RATE.toLong())
            }
            val corrected = updates.single { it.startByte == 0 }
            assertEquals(9_600L, corrected.endFrame)
            assertEquals(1, corrected.revision)
            val following = updates.single { it.startByte == 4 }
            assertEquals(12_000L, following.startFrame)
        }
    }

    @Test fun anActiveEstimatedWordKeepsItsOnsetWhileItsCorrectedEndReleasesTheNextWord() {
        val mapper = MediaPipeWordCueMapper("one two")
        val frames = VoiceStreamFrames("one two")
        val captions = VoicePcmCaptions()
        fun accept(words: List<WordTimestamp>, samples: FloatArray = FloatArray(0)) {
            val chunk = VoicePcmChunk(samples, wordCues = mapper.accept(words, VOICE_PCM_SAMPLE_RATE.toLong()))
            captions.appendStream(0, "one two", 0, frames.accept(chunk), samples.size)
        }
        val first = WordTimestamp("one", 0, 3, 0f, .8f)
        accept(listOf(first), FloatArray(VOICE_PCM_SAMPLE_RATE) { .2f })
        assertEquals(0..2, captions.consumed(4_800)?.range)
        accept(listOf(first.copy(startSec = .1f, endSec = .4f)))
        assertEquals(0L, captions.active()?.frame)
        assertEquals(9_600L, captions.active()?.endFrame)
        accept(listOf(WordTimestamp("two", 4, 7, .5f, .9f)))
        assertEquals(IntRange.EMPTY, captions.consumed(10_000)?.range)
        assertEquals(4..6, captions.consumed(12_000)?.range)
        assertEquals(IntRange.EMPTY, captions.consumed(21_600)?.range)
        frames.finish()
    }

    @Test fun aCompletedWordCannotReplayWhenItsLateCorrectionAllowsTheFollowingWord() {
        val mapper = MediaPipeWordCueMapper("one two")
        val frames = VoiceStreamFrames("one two")
        val captions = VoicePcmCaptions()
        fun accept(words: List<WordTimestamp>, samples: FloatArray = FloatArray(0)) {
            val chunk = VoicePcmChunk(samples, wordCues = mapper.accept(words, VOICE_PCM_SAMPLE_RATE.toLong()))
            captions.appendStream(0, "one two", 0, frames.accept(chunk), samples.size)
        }
        val first = WordTimestamp("one", 0, 3, 0f, .8f)
        accept(listOf(first), FloatArray(VOICE_PCM_SAMPLE_RATE) { .2f })
        assertEquals(IntRange.EMPTY, captions.consumed(20_000)?.range)
        accept(listOf(first.copy(endSec = .4f)))
        assertEquals(IntRange.EMPTY, captions.active()?.range)
        accept(listOf(WordTimestamp("two", 4, 7, .5f, .9f)))
        val crossed = mutableListOf<IntRange>()
        assertEquals(4..6, captions.consumed(20_000, onWordCrossed = { crossed += it.range })?.range)
        assertEquals(listOf(4..6), crossed)
        assertEquals(IntRange.EMPTY, captions.consumed(21_600)?.range)
        frames.finish()
    }

    @Test fun adjacentEstimatesFromOnePacketMoveTogetherWithoutLosingEitherWord() {
        for (boundary in listOf(.4f, .6f)) {
            val mapper = MediaPipeWordCueMapper("one two")
            val frames = VoiceStreamFrames("one two")
            val captions = VoicePcmCaptions()
            val one = WordTimestamp("one", 0, 3, 0f, .5f)
            val two = WordTimestamp("two", 4, 7, .5f, 1f)
            val original = VoicePcmChunk(FloatArray(VOICE_PCM_SAMPLE_RATE) { .2f }, wordCues = mapper.accept(listOf(one, two), VOICE_PCM_SAMPLE_RATE.toLong()))
            captions.appendStream(0, "one two", 0, frames.accept(original), original.samples.size)
            val updates = mapper.accept(listOf(one.copy(endSec = boundary), two.copy(startSec = boundary, endSec = .95f)), VOICE_PCM_SAMPLE_RATE.toLong())
            assertEquals("boundary=$boundary", 2, updates.size)
            captions.appendStream(0, "one two", 0, frames.accept(VoicePcmChunk(FloatArray(0), wordCues = updates)), 0)
            val crossed = mutableListOf<VoicePcmCue>()
            captions.consumed(VOICE_PCM_SAMPLE_RATE.toLong(), onWordCrossed = crossed::add)
            val expectedBoundary = if (boundary == .4f) 9_600L else 14_400L
            assertEquals(listOf(0L to expectedBoundary, expectedBoundary to 22_800L), crossed.map { it.frame to it.endFrame })
            assertEquals(listOf(0..2, 4..6), crossed.map { it.range })
            assertEquals(listOf(1, 1), crossed.map { it.revision })
            frames.finish()
        }
    }

    @Test fun aSharedBoundaryWaitsForItsNeighbourAudioWithoutDiscardingEitherCorrection() {
        val mapper = MediaPipeWordCueMapper("one two")
        val one = WordTimestamp("one", 0, 3, 0f, .5f)
        val two = WordTimestamp("two", 4, 7, .5f, .8f)
        mapper.accept(listOf(one, two), 24_000)
        assertTrue(mapper.accept(listOf(one.copy(endSec = .6f), two.copy(startSec = .6f, endSec = 1.2f)), 24_000).isEmpty())
        val ready = mapper.accept(emptyList(), 28_800)
        assertEquals(listOf(0, 4), ready.map { it.startByte }.sorted())
        assertEquals(listOf(14_400L, 28_800L), ready.sortedBy { it.startByte }.map { it.endFrame })
        assertTrue(ready.all { it.revision == 1 })
    }

    private fun queuedCaptions(speed: Float, trimQuiet: Boolean, initial: WordTimestamp, correction: WordTimestamp? = null): List<VoicePcmCue> {
        val text = "Café"
        val mapper = MediaPipeWordCueMapper(text)
        val tempo = VoiceTempoProcessor(speed)
        val stream = VoiceStreamPause(VoiceStreamFrames(text), if (trimQuiet) VoiceQuietLimiter(VOICE_PCM_SAMPLE_RATE, 70) else null)
        val captions = VoicePcmCaptions()
        val consume: (VoicePcmChunk) -> Unit = { chunk ->
            val retained = stream.accept(chunk)
            captions.appendStream(0, text, 0, retained.cues, retained.samples.size)
        }
        val pcm = FloatArray(VOICE_PCM_SAMPLE_RATE) { frame ->
            if (frame in 7_000 until 10_000) 0f else (.4 * sin(2 * PI * 180.0 * frame / VOICE_PCM_SAMPLE_RATE)).toFloat()
        }
        tempo.accept(VoicePcmChunk(pcm, wordCues = mapper.accept(listOf(initial), pcm.size.toLong())), consume)
        correction?.let {
            tempo.accept(VoicePcmChunk(FloatArray(0), wordCues = mapper.accept(listOf(it), pcm.size.toLong())), consume)
        }
        mapper.finish()?.let { tempo.accept(VoicePcmChunk(FloatArray(0), wordCues = listOf(it)), consume) }
        tempo.finish(consume)
        stream.finish()
        val crossed = mutableListOf<VoicePcmCue>()
        captions.consumed(stream.retainedFrames, onWordCrossed = crossed::add)
        return crossed
    }
}
