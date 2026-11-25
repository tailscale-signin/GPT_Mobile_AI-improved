package dev.chungjungsoo.gptmobile.data.voice

import com.google.mediapipe.tasks.audio.texttospeech.WordTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPipeWordCueMapperTest {
    @Test fun unicodeOffsetsAndTimesRemainRelativeToTheWholeStream() {
        val mapper = MediaPipeWordCueMapper("Café 🍵 go")
        val words = listOf(
            WordTimestamp("Café", 0, 5, 0f, .25f),
            WordTimestamp("🍵", 6, 10, .25f, .5f),
            WordTimestamp("go", 11, 13, 1f, 1.2f)
        )
        assertEquals(listOf(VoiceWordCue(0, 5, 0, 6_000)), mapper.accept(words, 6_000))
        assertEquals(listOf(VoiceWordCue(6, 10, 6_000, 12_000)), mapper.accept(words, 12_000))
        assertEquals(listOf(VoiceWordCue(11, 13, 24_000, 28_800)), mapper.accept(words, 28_800))
        assertTrue(mapper.accept(words, 28_800).isEmpty())
    }

    @Test fun invalidUtf8BoundariesAndNonFiniteTimesDoNotReachCaptions() {
        val mapper = MediaPipeWordCueMapper("Café go")
        val cues = mapper.accept(
            listOf(
                WordTimestamp("broken", 4, 5, 0f, .1f),
                WordTimestamp("bad time", 0, 5, Float.NaN, .1f),
                WordTimestamp("outside", 6, 999, .1f, .2f),
                WordTimestamp("go", 6, 8, .2f, .3f)
            ),
            24_000
        )
        assertEquals(listOf(VoiceWordCue(6, 8, 4_800, 7_200, VoiceWordCueProvenance.ESTIMATED)), cues)
    }

    @Test fun futureCueWaitsForAudioAndOverlappingUpdatedSpansAreNotRepeated() {
        val mapper = MediaPipeWordCueMapper("one two")
        val first = WordTimestamp("one", 0, 3, 0f, .5f)
        assertEquals(listOf(VoiceWordCue(0, 3, 0, 1_000, finalEnd = false)), mapper.accept(listOf(first), 1_000))
        assertTrue(mapper.accept(listOf(first), 1_000).isEmpty())
        assertEquals(listOf(VoiceWordCue(0, 3, 0, 12_000)), mapper.accept(emptyList(), 12_000))
        assertTrue(mapper.accept(listOf(first.copy(endChar = 4, startSec = .5f, endSec = .6f)), 14_400).isEmpty())
        assertTrue(mapper.accept(listOf(WordTimestamp("two", 4, 7, .4f, .6f)), 14_400).isEmpty())
    }

    @Test fun truncatedEstimateClosesAtTheLastProducedFrameAndFutureWordsStayUnpublished() {
        val mapper = MediaPipeWordCueMapper("word gap")
        val cues = listOf(WordTimestamp("word", 0, 4, 0f, 2f), WordTimestamp("gap", 5, 8, 2f, 3f))
        assertEquals(listOf(VoiceWordCue(0, 4, 0, 24_000, finalEnd = false)), mapper.accept(cues, 24_000))
        assertTrue(mapper.accept(emptyList(), 24_000).isEmpty())
        assertEquals(VoiceWordCue(0, 4, 0, 24_000), mapper.finish())
        assertEquals(null, mapper.finish())
    }

    @Test fun lateEarlierTextCannotReplaceAPartiallyPresentedWord() {
        val mapper = MediaPipeWordCueMapper("one two")
        val second = WordTimestamp("two", 4, 7, 0f, .5f)
        assertEquals(listOf(VoiceWordCue(4, 7, 0, 6_000, finalEnd = false)), mapper.accept(listOf(second), 6_000))
        assertEquals(listOf(VoiceWordCue(4, 7, 0, 12_000)), mapper.accept(listOf(WordTimestamp("one", 0, 3, 0f, .1f)), 12_000))
    }
}
