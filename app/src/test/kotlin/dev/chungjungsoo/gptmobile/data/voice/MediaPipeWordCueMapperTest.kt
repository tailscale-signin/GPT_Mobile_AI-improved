package dev.chungjungsoo.gptmobile.data.voice

import com.google.mediapipe.tasks.audio.texttospeech.WordTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPipeWordCueMapperTest {
    @Test fun aNewerEstimateReplacesAFutureWordBeforeItHasAnyAudio() {
        val mapper = MediaPipeWordCueMapper("one two")
        val one = WordTimestamp("one", 0, 3, 0f, .1f)
        val two = WordTimestamp("two", 4, 7, 1f, 1.5f)
        assertEquals(listOf(VoiceWordCue(0, 3, 0, 2_400)), mapper.accept(listOf(one, two), 2_400))
        assertEquals(listOf(VoiceWordCue(4, 7, 4_800, 9_600)), mapper.accept(listOf(two.copy(startSec = .2f, endSec = .4f)), 12_000))
    }

    @Test fun completedEstimatedWordsReceiveExplicitCorrectionsWithoutConsumingAnotherCueSlot() {
        val mapper = MediaPipeWordCueMapper("one two", cueLimit = 2)
        val one = WordTimestamp("one", 0, 3, 0f, .4f)
        val two = WordTimestamp("two", 4, 7, .5f, .8f)
        mapper.accept(listOf(one, two), 24_000)
        val correction = mapper.accept(listOf(one.copy(startSec = .1f, endSec = .3f)), 24_000).single()
        assertEquals(VoiceWordCue(0, 3, 2_400, 7_200, revision = 1), correction)
        assertTrue(mapper.accept(listOf(one.copy(startSec = .1f, endSec = .3f)), 24_000).isEmpty())
        assertTrue(mapper.accept(listOf(one.copy(endSec = .6f)), 24_000).isEmpty())
        assertTrue(mapper.accept(listOf(one.copy(endChar = 4)), 24_000).isEmpty())
    }

    @Test fun anOpenEstimateCanFinishSoonerWithoutRetractingTheObservedPrefix() {
        val mapper = MediaPipeWordCueMapper("one")
        val first = WordTimestamp("one", 0, 3, 0f, 1f)
        assertEquals(listOf(VoiceWordCue(0, 3, 0, 2_400, finalEnd = false)), mapper.accept(listOf(first), 2_400))
        assertEquals(listOf(VoiceWordCue(0, 3, 0, 4_800)), mapper.accept(listOf(first.copy(endSec = .2f)), 4_800))
    }

    @Test fun oldAndOverlappingCorrectionsNeverReopenRetiredText() {
        val mapper = MediaPipeWordCueMapper("one two", frameLimit = VOICE_PCM_SAMPLE_RATE * 120L)
        val one = WordTimestamp("one", 0, 3, 0f, .4f)
        val two = WordTimestamp("two", 4, 7, .5f, .8f)
        mapper.accept(listOf(one, two), 24_000)
        assertTrue(mapper.accept(listOf(one.copy(startSec = .1f)), VOICE_PCM_SAMPLE_RATE * 62L).isEmpty())
        assertTrue(mapper.accept(listOf(two.copy(startSec = .2f)), VOICE_PCM_SAMPLE_RATE * 62L).isEmpty())
    }

    @Test fun aPublishedCorrectionCannotConsumeTheNextPendingWord() {
        val mapper = MediaPipeWordCueMapper("one two")
        val one = WordTimestamp("one", 0, 3, 0f, .1f)
        val two = WordTimestamp("two", 4, 7, .2f, .3f)
        mapper.accept(listOf(one, two), 2_400)
        assertTrue(mapper.accept(listOf(one.copy(endSec = .25f)), 2_400).isEmpty())
        assertEquals(listOf(VoiceWordCue(4, 7, 4_800, 7_200)), mapper.accept(emptyList(), 7_200))
    }

    @Test fun revisionHistoryIsBoundedByTheMostRecentWordsAsWellAsAudioAge() {
        val count = VoiceStreamFrames.MAX_REVISION_WORDS + 1
        val mapper = MediaPipeWordCueMapper("one ".repeat(count).trimEnd())
        val words = List(count) { index -> WordTimestamp("one", index * 4, index * 4 + 3, index * .01f, index * .01f + .005f) }
        assertEquals(count, mapper.accept(words, 100_000).size)
        assertTrue(mapper.accept(listOf(words.first().copy(startSec = .001f)), 100_000).isEmpty())
        val retained = mapper.accept(listOf(words[1].copy(startSec = .011f)), 100_000).single()
        assertEquals(4, retained.startByte)
        assertEquals(1, retained.revision)
    }

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

    @Test fun repeatedPacketsShareOneCueBudgetAcrossPendingAndAlreadyEmittedWords() {
        val mapper = MediaPipeWordCueMapper("one two six", cueLimit = 2)
        val one = WordTimestamp("one", 0, 3, 0f, .25f)
        val two = WordTimestamp("two", 4, 7, .25f, .5f)
        val six = WordTimestamp("six", 8, 11, .5f, .75f)
        assertEquals(listOf(VoiceWordCue(0, 3, 0, 6_000)), mapper.accept(listOf(one, two), 6_000))
        assertTrue(mapper.accept(listOf(one, six), 6_000).isEmpty())
        assertEquals(listOf(VoiceWordCue(4, 7, 6_000, 12_000)), mapper.accept(listOf(two, six), 18_000))
        assertTrue(mapper.accept(listOf(six), 18_000).isEmpty())
    }

    @Test fun lateEarlierTextCannotReplaceAPartiallyPresentedWord() {
        val mapper = MediaPipeWordCueMapper("one two")
        val second = WordTimestamp("two", 4, 7, 0f, .5f)
        assertEquals(listOf(VoiceWordCue(4, 7, 0, 6_000, finalEnd = false)), mapper.accept(listOf(second), 6_000))
        assertEquals(listOf(VoiceWordCue(4, 7, 0, 12_000)), mapper.accept(listOf(WordTimestamp("one", 0, 3, 0f, .1f)), 12_000))
    }
}
