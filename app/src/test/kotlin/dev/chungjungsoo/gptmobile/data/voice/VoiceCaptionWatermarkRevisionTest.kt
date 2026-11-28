package dev.chungjungsoo.gptmobile.data.voice

import com.google.mediapipe.tasks.audio.texttospeech.WordTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceCaptionWatermarkRevisionTest {
    @Test fun aRejectedPastOnsetStillReleasesAValidShortenedTailForTheFollowingWord() {
        for (samePacket in listOf(false, true)) {
            val text = "one two three"
            val mapper = MediaPipeWordCueMapper(text)
            val frames = VoiceStreamFrames(text)
            val captions = VoicePcmCaptions()
            fun accept(words: List<WordTimestamp>, samples: FloatArray = FloatArray(0)) {
                val chunk = VoicePcmChunk(samples, wordCues = mapper.accept(words, 28_800))
                captions.appendStream(0, text, 0, frames.accept(chunk), samples.size)
            }
            val two = WordTimestamp("two", 4, 7, .6f, 1f)
            accept(listOf(WordTimestamp("one", 0, 3, 0f, .4f), two), FloatArray(28_800) { .2f })
            assertEquals(IntRange.EMPTY, captions.consumed(12_000)?.range)

            val corrected = two.copy(startSec = .45f, endSec = .8f)
            val third = WordTimestamp("three", 8, 13, .85f, 1.1f)
            if (samePacket) {
                accept(listOf(corrected, third))
            } else {
                accept(listOf(corrected))
                accept(listOf(third))
            }

            // The old onset is retained; the late estimate cannot replay a word
            // behind the presented audio. Its observed shorter end remains useful.
            assertNull(captions.consumed(13_000))
            val observed = mutableListOf<VoicePcmCue>()
            val second = requireNotNull(captions.consumed(14_400, onWordCrossed = observed::add))
            assertEquals(14_400L, second.frame)
            assertEquals(19_200L, second.endFrame)
            assertEquals(1, second.revision)
            assertEquals(IntRange.EMPTY, captions.consumed(19_200)?.range)
            assertEquals(8..12, captions.consumed(20_400, onWordCrossed = observed::add)?.range)
            assertEquals(IntRange.EMPTY, captions.consumed(26_400)?.range)
            assertEquals(listOf(4..6, 8..12), observed.map { it.range })
            frames.finish()
        }
    }

    @Test fun aQueuedWordWithNoRemainingIntervalIsOmittedWithoutBlockingRealFollowingAudio() {
        for (samePacket in listOf(false, true)) {
            for ((end, followingStart) in listOf(.55f to .57f, .6f to .62f)) {
                val text = "one two three"
                val mapper = MediaPipeWordCueMapper(text)
                val frames = VoiceStreamFrames(text)
                val captions = VoicePcmCaptions()
                fun accept(words: List<WordTimestamp>, samples: FloatArray = FloatArray(0)) {
                    val chunk = VoicePcmChunk(samples, wordCues = mapper.accept(words, 28_800))
                    captions.appendStream(0, text, 0, frames.accept(chunk), samples.size)
                }
                val two = WordTimestamp("two", 4, 7, .6f, 1f)
                accept(listOf(WordTimestamp("one", 0, 3, 0f, .4f), two), FloatArray(28_800) { .2f })
                val completed = requireNotNull(captions.consumed(12_000))
                val revised = two.copy(startSec = .45f, endSec = end)
                val third = WordTimestamp("three", 8, 13, followingStart, 1.1f)
                if (samePacket) {
                    accept(listOf(revised, third))
                } else {
                    accept(listOf(revised))
                    accept(listOf(third))
                }
                assertEquals(completed, captions.active())
                assertNull(captions.consumed(12_000))
                val crossed = mutableListOf<VoicePcmCue>()
                captions.consumed(26_400, onWordCrossed = crossed::add)
                val retained = crossed.single()
                assertEquals(8..12, retained.range)
                assertEquals(kotlin.math.round(followingStart.toDouble() * VOICE_PCM_SAMPLE_RATE).toLong(), retained.frame)
                assertEquals(26_400L, retained.endFrame)
                assertEquals(0, retained.revision)
                assertEquals(28_800L, frames.frames)
                frames.finish()
            }
        }
    }

    @Test fun anOmittedCaptionRetainsItsObservedEndForDirectInputOverlapValidation() {
        val captions = captionsWithOmittedTail()
        val overlapping = VoiceStreamCue(8..12, 12_720, 26_400, VoiceWordCueProvenance.ESTIMATED, startByte = 8, endByte = 13)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            captions.appendStream(0, "one two three", 0, listOf(overlapping), 0)
        }
        assertEquals("STREAM_CUE_OVERLAP", failure.message)
        val following = overlapping.copy(startFrame = 13_680)
        captions.appendStream(0, "one two three", 0, listOf(following), 0)
        val crossed = mutableListOf<VoicePcmCue>()
        captions.consumed(26_400, onWordCrossed = crossed::add)
        assertEquals(listOf(8..12), crossed.map { it.range })
        assertEquals(13_680L, crossed.single().frame)
    }

    @Test fun anotherRevisionCanReleaseAnOmittedTailWithoutRevivingItsWord() {
        val captions = captionsWithOmittedTail()
        val revised = VoiceStreamCue(4..6, 10_800, 12_480, VoiceWordCueProvenance.ESTIMATED, revision = 2, startByte = 4, endByte = 7)
        captions.appendStream(0, "one two three", 0, listOf(revised), 0)
        val following = VoiceStreamCue(8..12, 12_720, 26_400, VoiceWordCueProvenance.ESTIMATED, startByte = 8, endByte = 13)
        captions.appendStream(0, "one two three", 0, listOf(following), 0)
        val crossed = mutableListOf<VoicePcmCue>()
        captions.consumed(26_400, onWordCrossed = crossed::add)
        assertEquals(listOf(8..12), crossed.map { it.range })
        assertEquals(12_720L, crossed.single().frame)
    }

    @Test fun malformedOverlapsAreStillRejectedBeforeCaptionMutation() {
        val frames = VoiceStreamFrames("one two")
        val captions = VoicePcmCaptions()
        val two = VoiceWordCue(4, 7, 14_400, 24_000)
        val original = VoicePcmChunk(FloatArray(28_800) { .2f }, wordCues = listOf(VoiceWordCue(0, 3, 0, 9_600), two))
        captions.appendStream(0, "one two", 0, frames.accept(original), original.samples.size)
        captions.consumed(12_000)
        val invalid = VoicePcmChunk(FloatArray(0), wordCues = listOf(two.copy(startFrame = 8_400, endFrame = 19_200, revision = 1)))
        assertThrows(IllegalArgumentException::class.java) {
            captions.appendStream(0, "one two", 0, frames.accept(invalid), 0)
        }
        assertEquals(14_400L, captions.consumed(14_400)?.frame)
        assertEquals(24_000L, captions.active()?.endFrame)
    }

    private fun captionsWithOmittedTail(): VoicePcmCaptions {
        val captions = VoicePcmCaptions()
        val one = VoiceStreamCue(0..2, 0, 9_600, VoiceWordCueProvenance.ESTIMATED, startByte = 0, endByte = 3)
        val two = VoiceStreamCue(4..6, 14_400, 24_000, VoiceWordCueProvenance.ESTIMATED, startByte = 4, endByte = 7)
        captions.appendStream(0, "one two three", 0, listOf(one, two), 28_800)
        captions.consumed(12_000)
        captions.appendStream(0, "one two three", 0, listOf(two.copy(startFrame = 10_800, endFrame = 13_200, revision = 1)), 0)
        return captions
    }
}
