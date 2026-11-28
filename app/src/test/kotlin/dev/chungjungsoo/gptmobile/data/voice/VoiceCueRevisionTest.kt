package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceCueRevisionTest {
    @Test fun coalescedAudioPublishesTheOriginalBeforeItsCorrection() {
        for (quiet in listOf(null, VoiceQuietLimiter(1_000, 2))) {
            val stream = VoiceStreamPause(VoiceStreamFrames("one two"), quiet)
            val one = VoiceWordCue(0, 3, 0, 100)
            val two = VoiceWordCue(4, 7, 150, 250)
            val chunk = VoicePcmChunk(FloatArray(300) { .1f }, wordCues = listOf(one, two, one.copy(startFrame = 20, endFrame = 90, revision = 1)))
            val output = stream.accept(chunk)
            assertEquals(listOf(0, 0, 1), output.cues.map { it.revision })
            val captions = VoicePcmCaptions()
            captions.appendStream(0, "one two", 0, output.cues, output.samples.size)
            assertNull(captions.consumed(10))
            assertEquals(20L, captions.consumed(20)?.frame)
            stream.finish()
        }
    }

    @Test fun aQueuedWordAndActiveTailCanImproveWhileTheHeardOnsetAndOriginalSourceRemainCommitted() {
        val raw = VoiceStreamFrames("one two three")
        val captions = VoicePcmCaptions()
        val one = VoiceWordCue(0, 3, 0, 100)
        val two = VoiceWordCue(4, 7, 120, 250)
        val three = VoiceWordCue(8, 13, 300, 400)
        val first = VoicePcmChunk(FloatArray(500) { .1f }, wordCues = listOf(one, two, three))
        captions.appendStream(7, "one two three", 0, raw.accept(first), first.samples.size)
        assertEquals(0..2, captions.consumed(50)?.range)
        val corrected = VoicePcmChunk(
            FloatArray(0),
            wordCues = listOf(one.copy(startFrame = 10, endFrame = 90, revision = 1), two.copy(startFrame = 150, endFrame = 230, revision = 1))
        )
        captions.appendStream(7, "one two three", 0, raw.accept(corrected), 0)
        assertEquals(0L, captions.active()?.frame)
        assertEquals(90L, captions.active()?.endFrame)
        assertEquals(0..2, captions.active()?.range)
        assertEquals(IntRange.EMPTY, captions.consumed(140)?.range)
        assertEquals(4..6, captions.consumed(150)?.range)
        assertEquals(230L, captions.active()?.endFrame)
        assertEquals(1, captions.active()?.revision)
        assertEquals(IntRange.EMPTY, captions.consumed(230)?.range)
        assertEquals(8..12, captions.consumed(310)?.range)
        raw.finish()
    }

    @Test fun aCorrectionMayNotMoveAnUnheardWordBehindThePresentationWatermark() {
        val raw = VoiceStreamFrames("one two")
        val captions = VoicePcmCaptions()
        val one = VoiceWordCue(0, 3, 0, 80)
        val two = VoiceWordCue(4, 7, 150, 250)
        captions.appendStream(0, "one two", 0, raw.accept(VoicePcmChunk(FloatArray(300) { .1f }, wordCues = listOf(one, two))), 300)
        captions.consumed(130)
        captions.appendStream(0, "one two", 0, raw.accept(VoicePcmChunk(FloatArray(0), wordCues = listOf(two.copy(startFrame = 120, revision = 1)))), 0)
        assertNull(captions.consumed(140))
        assertEquals(150L, captions.consumed(150)?.frame)
    }

    @Test fun aShorterActiveTailDoesNotAdvanceAnExplicitlyPausedCaption() {
        val raw = VoiceStreamFrames("one")
        val captions = VoicePcmCaptions()
        val word = VoiceWordCue(0, 3, 0, 100)
        captions.appendStream(0, "one", 0, raw.accept(VoicePcmChunk(FloatArray(200) { .1f }, wordCues = listOf(word))), 200)
        assertEquals(0..2, captions.consumed(50)?.range)
        captions.appendStream(0, "one", 0, raw.accept(VoicePcmChunk(FloatArray(0), wordCues = listOf(word.copy(endFrame = 40, revision = 1)))), 0)
        assertNull(captions.consumed(50, advancing = false))
        assertEquals(0..2, captions.active()?.range)
        assertEquals(IntRange.EMPTY, captions.consumed(50)?.range)
        assertNull(captions.consumed(50))
    }

    @Test fun theFirstWordCanBeCorrectedBeforeAnyAudioWasPresented() {
        val raw = VoiceStreamFrames("one")
        val captions = VoicePcmCaptions()
        val word = VoiceWordCue(0, 3, 0, 100)
        captions.appendStream(0, "one", 0, raw.accept(VoicePcmChunk(FloatArray(200) { .1f }, wordCues = listOf(word))), 200)
        captions.appendStream(0, "one", 0, raw.accept(VoicePcmChunk(FloatArray(0), wordCues = listOf(word.copy(startFrame = 20, endFrame = 120, revision = 1)))), 0)
        assertNull(captions.consumed(10))
        assertEquals(20L, captions.consumed(20)?.frame)
    }

    @Test fun sourceIdentityNeighbourIntervalsAndRevisionSequenceAreValidatedBeforeCaptionMutation() {
        val original = VoiceWordCue(0, 5, 0, 100)
        val following = VoiceWordCue(6, 8, 120, 200)
        val invalid = listOf(
            original.copy(endByte = 4, revision = 1),
            original.copy(endFrame = 130, revision = 1),
            original.copy(endFrame = 110, revision = 2),
            original.copy(endFrame = 301, revision = 1),
            original.copy(startFrame = 10, finalEnd = false, revision = 1)
        )
        for (cue in invalid) {
            val raw = VoiceStreamFrames("Café go")
            raw.accept(VoicePcmChunk(FloatArray(300) { .1f }, wordCues = listOf(original, following)))
            assertThrows(IllegalArgumentException::class.java) { raw.accept(VoicePcmChunk(FloatArray(0), wordCues = listOf(cue))) }
        }
    }

    @Test fun aLateCorrectionUsesTheSameExactSilenceRemovalMapAsItsOriginalWord() {
        val raw = VoiceStreamFrames("one two")
        val stream = VoiceStreamPause(raw, VoiceQuietLimiter(1_000, 2))
        val original = stream.accept(
            VoicePcmChunk(
                floatArrayOf(.1f, 0f, 0f, 0f, 0f, .2f, .3f, .4f),
                wordCues = listOf(VoiceWordCue(0, 3, 0, 3), VoiceWordCue(4, 7, 5, 7))
            )
        )
        assertEquals(6, original.samples.size)
        val correction = stream.accept(VoicePcmChunk(FloatArray(0), wordCues = listOf(VoiceWordCue(4, 7, 6, 8, revision = 1)))).cues.single()
        assertEquals(4L, correction.startFrame)
        assertEquals(6L, correction.endFrame)
        assertEquals(4, correction.startByte)
        assertEquals(7, correction.endByte)
        assertEquals(1, correction.revision)
        stream.finish()
    }

    @Test fun anUnobservedOriginalCannotBeReplacedWithARevision() {
        val raw = VoiceStreamFrames("one")
        raw.accept(VoicePcmChunk(FloatArray(10) { .1f }, wordCues = listOf(VoiceWordCue(0, 3, 0, 100))))
        assertThrows(IllegalArgumentException::class.java) {
            raw.accept(VoicePcmChunk(FloatArray(0), wordCues = listOf(VoiceWordCue(0, 3, 0, 10, revision = 1))))
        }
    }
}
