package dev.chungjungsoo.gptmobile.data.voice

import com.google.mediapipe.tasks.audio.texttospeech.WordTimestamp
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Packet-order regressions; synthetic audio does not measure acoustic word boundaries. */
class PocketOpenCaptionRevisionTest {
    @Test fun aShortenedOpenEstimateReleasesTheFollowingWordInsteadOfDroppingIt() {
        val mapper = MediaPipeWordCueMapper("one two")
        val original = WordTimestamp("one", 0, 3, 0f, 2f)
        assertEquals(listOf(VoiceWordCue(0, 3, 0, 12_000, finalEnd = false)), mapper.accept(listOf(original), 12_000))
        val updated = mapper.accept(
            listOf(original.copy(endSec = .4f), WordTimestamp("two", 4, 7, .4f, .8f)),
            24_000
        )
        assertEquals(
            listOf(VoiceWordCue(0, 3, 0, 12_000), VoiceWordCue(0, 3, 0, 9_600, revision = 1), VoiceWordCue(4, 7, 9_600, 19_200)),
            updated
        )
        assertTrue(mapper.accept(emptyList(), 24_000).isEmpty())
        assertEquals(null, mapper.finish())
    }

    @Test fun anOpenOnsetCorrectionPreservesItsIdentityAndReachesThePresentationQueue() {
        val mapper = MediaPipeWordCueMapper("Café go")
        val original = WordTimestamp("Café", 0, 5, 0f, 2f)
        mapper.accept(listOf(original), 12_000)
        val updated = mapper.accept(
            listOf(original.copy(startSec = .1f, endSec = .6f), WordTimestamp("go", 6, 8, .6f, .9f)),
            24_000
        )
        assertEquals(listOf(0, 1, 0), updated.map { it.revision })
        assertEquals(2_400L, updated[1].startFrame)
        assertEquals(14_400L, updated[1].endFrame)
        assertEquals(6, updated.last().startByte)
    }

    @Test fun anOpenCorrectionIsRetainedUntilItsAudioArrivesEvenWithoutRepeatedMetadata() {
        val mapper = MediaPipeWordCueMapper("Café go")
        val original = WordTimestamp("Café", 0, 5, 0f, 2f)
        mapper.accept(listOf(original), 12_000)
        assertTrue(mapper.accept(listOf(original.copy(startSec = .1f, endSec = .6f)), 12_000).isEmpty())
        assertEquals(14_400L, mapper.timestampedThroughFrame)
        val updated = mapper.accept(emptyList(), 14_400)
        assertEquals(listOf(0, 1), updated.map { it.revision })
        assertEquals(2_400L, updated.last().startFrame)
        assertEquals(14_400L, updated.last().endFrame)
        val following = mapper.accept(listOf(WordTimestamp("go", 6, 8, .6f, .9f)), 24_000)
        assertEquals(6, following.single().startByte)
    }

    @Test fun aOneTimeOpenCorrectionCanExtendBeyondTheOriginalEndWithoutBeingLost() {
        val mapper = MediaPipeWordCueMapper("one two")
        val original = WordTimestamp("one", 0, 3, 0f, 1f)
        mapper.accept(listOf(original), 12_000)
        mapper.accept(listOf(original.copy(startSec = .1f, endSec = 1.5f)), 14_400)
        assertEquals(36_000L, mapper.timestampedThroughFrame)
        val originalFrontier = mapper.accept(emptyList(), 24_000)
        assertTrue(originalFrontier.none { it.finalEnd })
        val corrected = mapper.accept(emptyList(), 36_000)
        assertEquals(listOf(0, 1), corrected.map { it.revision })
        assertEquals(2_400L, corrected.last().startFrame)
        assertEquals(36_000L, corrected.last().endFrame)
    }

    @Test fun aPublishedCorrectionReleasesItsFutureAudioFromTheCaptionBuffer() {
        val mapper = MediaPipeWordCueMapper("one")
        val original = WordTimestamp("one", 0, 3, 0f, .5f)
        mapper.accept(listOf(original), 12_000)
        assertTrue(mapper.accept(listOf(original.copy(endSec = 1f)), 12_000).isEmpty())
        assertEquals(24_000L, mapper.timestampedThroughFrame)
        val corrected = mapper.accept(emptyList(), 24_000).single()
        assertEquals(1, corrected.revision)
        assertEquals(24_000L, corrected.endFrame)
    }

    @Test fun aRevisionMustPrecedeTheFollowingWordThroughTempoAndPauseTransforms() {
        for (speed in listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)) {
            for (trimQuiet in listOf(false, true)) {
                val processor = VoiceTempoProcessor(speed)
                val stream = VoiceStreamPause(VoiceStreamFrames("one two"), if (trimQuiet) VoiceQuietLimiter(VOICE_PCM_SAMPLE_RATE, 70) else null)
                val captions = VoicePcmCaptions()
                val consume: (VoicePcmChunk) -> Unit = { chunk ->
                    val retained = stream.accept(chunk)
                    captions.appendStream(0, "one two", 0, retained.cues, retained.samples.size)
                }
                val original = VoiceWordCue(0, 3, 0, 12_000)
                processor.accept(VoicePcmChunk(tone(12_000), wordCues = listOf(original.copy(finalEnd = false))), consume)
                processor.accept(
                    VoicePcmChunk(
                        tone(12_000),
                        wordCues = listOf(original, original.copy(endFrame = 9_600, revision = 1), VoiceWordCue(4, 7, 9_600, 19_200))
                    ),
                    consume
                )
                processor.finish(consume)
                stream.finish()
                val crossed = mutableListOf<VoicePcmCue>()
                captions.consumed(stream.retainedFrames, onWordCrossed = crossed::add)
                assertEquals("speed=$speed trim=$trimQuiet", listOf(0..2, 4..6), crossed.map { it.range })
                assertEquals(crossed.first().endFrame, crossed.last().frame)
                assertEquals(1, crossed.first().revision)
            }
        }
    }

    @Test fun openSdkRevisionsReachBothWordsAcrossTheWholeCaptionPipeline() {
        for (speed in listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)) {
            for (trimQuiet in listOf(false, true)) {
                val mapper = MediaPipeWordCueMapper("one two")
                val processor = VoiceTempoProcessor(speed)
                val stream = VoiceStreamPause(VoiceStreamFrames("one two"), if (trimQuiet) VoiceQuietLimiter(VOICE_PCM_SAMPLE_RATE, 70) else null)
                val captions = VoicePcmCaptions()
                val consume: (VoicePcmChunk) -> Unit = { chunk ->
                    val retained = stream.accept(chunk)
                    captions.appendStream(0, "one two", 0, retained.cues, retained.samples.size)
                }
                val original = WordTimestamp("one", 0, 3, 0f, 2f)
                processor.accept(VoicePcmChunk(tone(12_000), wordCues = mapper.accept(listOf(original), 12_000)), consume)
                processor.accept(
                    VoicePcmChunk(
                        tone(12_000),
                        wordCues = mapper.accept(listOf(original.copy(endSec = .4f), WordTimestamp("two", 4, 7, .4f, .8f)), 24_000)
                    ),
                    consume
                )
                mapper.finish()?.let { processor.accept(VoicePcmChunk(FloatArray(0), wordCues = listOf(it)), consume) }
                processor.finish(consume)
                stream.finish()
                val crossed = mutableListOf<VoicePcmCue>()
                captions.consumed(stream.retainedFrames, onWordCrossed = crossed::add)
                assertEquals("speed=$speed trim=$trimQuiet", listOf(0..2, 4..6), crossed.map { it.range })
                assertEquals(crossed.first().endFrame, crossed.last().frame)
            }
        }
    }

    @Test fun anOpenCorrectionPreservesTheHeardOnsetAndOnlyRepairsTheActiveEnd() {
        val mapper = MediaPipeWordCueMapper("one two")
        val stream = VoiceStreamFrames("one two")
        val captions = VoicePcmCaptions()
        fun accept(words: List<WordTimestamp>, samples: FloatArray, available: Long) {
            val chunk = VoicePcmChunk(samples, wordCues = mapper.accept(words, available))
            captions.appendStream(0, "one two", 0, stream.accept(chunk), samples.size)
        }
        val original = WordTimestamp("one", 0, 3, 0f, 2f)
        accept(listOf(original), tone(12_000), 12_000)
        assertEquals(0..2, captions.consumed(2_400)?.range)
        accept(listOf(original.copy(startSec = .1f, endSec = .4f), WordTimestamp("two", 4, 7, .4f, .8f)), tone(12_000), 24_000)
        assertEquals(0L, captions.active()?.frame)
        assertEquals(9_600L, captions.active()?.endFrame)
        assertEquals(4..6, captions.consumed(9_600)?.range)
        stream.finish()
    }

    @Test fun anActiveWordWhoseCorrectedIntervalIsEntirelyPastCannotBlockFollowingSpeech() {
        val mapper = MediaPipeWordCueMapper("one two")
        val stream = VoiceStreamFrames("one two")
        val captions = VoicePcmCaptions()
        fun accept(words: List<WordTimestamp>, samples: FloatArray = FloatArray(0)) {
            val chunk = VoicePcmChunk(samples, wordCues = mapper.accept(words, 24_000))
            captions.appendStream(0, "one two", 0, stream.accept(chunk), samples.size)
        }
        val original = WordTimestamp("one", 0, 3, .6f, 2f)
        accept(listOf(original), tone(24_000))
        assertEquals(0..2, captions.consumed(16_000)?.range)
        accept(listOf(original.copy(startSec = .3f, endSec = .5f), WordTimestamp("two", 4, 7, .5f, .9f)))
        val crossed = mutableListOf<VoicePcmCue>()
        assertEquals(4..6, captions.consumed(16_000, onWordCrossed = crossed::add)?.range)
        assertEquals(listOf(4..6), crossed.map { it.range })
        assertEquals(12_000L, crossed.single().frame)
        assertEquals(IntRange.EMPTY, captions.consumed(21_600)?.range)
        stream.finish()
    }

    @Test fun repeatedPastCorrectionsRetainAnExplicitlyPausedWordUntilPresentationResumes() {
        val frames = VoiceStreamFrames("one two")
        val captions = VoicePcmCaptions()
        fun accept(cues: List<VoiceWordCue>, samples: FloatArray = FloatArray(0)) {
            val chunk = VoicePcmChunk(samples, wordCues = cues)
            captions.appendStream(0, "one two", 0, frames.accept(chunk), samples.size)
        }
        val word = VoiceWordCue(0, 3, 14_400, 24_000)
        accept(listOf(word), tone(24_000))
        val active = captions.consumed(16_000)
        accept(listOf(word.copy(startFrame = 7_200, endFrame = 12_000, revision = 1)))
        accept(listOf(word.copy(startFrame = 7_200, endFrame = 10_800, revision = 2)))
        accept(listOf(VoiceWordCue(4, 7, 12_000, 21_600)))
        assertEquals(null, captions.consumed(16_000, advancing = false))
        assertEquals(active, captions.active())
        assertEquals(4..6, captions.consumed(16_000)?.range)
        frames.finish()
    }

    @Test fun anInvalidatedHeardWordCanClearDuringRefillWithoutAdvancingTheFollowingWord() {
        val frames = VoiceStreamFrames("one two")
        val captions = VoicePcmCaptions()
        fun accept(cues: List<VoiceWordCue>, samples: FloatArray = FloatArray(0)) {
            val chunk = VoicePcmChunk(samples, wordCues = cues)
            captions.appendStream(0, "one two", 0, frames.accept(chunk), samples.size)
        }
        val word = VoiceWordCue(0, 3, 14_400, 24_000)
        accept(listOf(word), tone(24_000))
        captions.consumed(16_000)
        accept(listOf(word.copy(startFrame = 7_200, endFrame = 12_000, revision = 1), VoiceWordCue(4, 7, 12_000, 21_600)))
        assertEquals(IntRange.EMPTY, captions.consumed(16_000, advancing = false, completedThrough = 16_000)?.range)
        assertEquals(null, captions.consumed(16_000, advancing = false, completedThrough = 16_000))
        assertEquals(4..6, captions.consumed(16_000)?.range)
        frames.finish()
    }

    private fun tone(size: Int) = FloatArray(size) { (.4 * sin(2 * PI * 180.0 * it / VOICE_PCM_SAMPLE_RATE)).toFloat() }
}
