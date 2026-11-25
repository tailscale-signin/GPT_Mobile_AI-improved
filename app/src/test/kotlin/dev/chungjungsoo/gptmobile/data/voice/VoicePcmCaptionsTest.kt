package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class VoicePcmCaptionsTest {
    @Test fun aDelayedClockPollCrossesAll47FixtureWordsAndRendersOnlyTheCurrentWord() {
        val source = VoiceSpeechBenchmarkFixture.LONG.text
        val manifest = VoiceSpeechCaptionManifest.from(listOf(source))
        val captions = VoicePcmCaptions()
        val cues = Regex("""\S+""").findAll(source).mapIndexed { index, match ->
            VoiceStreamCue(match.range, index * 100L, (index + 1) * 100L, VoiceWordCueProvenance.ESTIMATED).lexicalRange(source)
        }.toList()
        assertEquals(47, cues.size)
        captions.appendStream(0, source, 0, cues, 4700)
        val crossed = mutableListOf<VoiceSpeechCaptionSpan>()
        assertNull(captions.consumed(2100, advancing = false, onWordCrossed = { error("paused clock") }))
        val latest = captions.consumed(4650, onWordCrossed = { crossed += VoiceSpeechCaptionSpan(it.segment, it.range.first, it.range.last) })
        assertEquals(manifest.spans, crossed.toSet())
        assertEquals(cues.last().range, latest?.range)
        assertNull(captions.consumed(4650, onWordCrossed = { error("duplicate crossing") }))
        for ((text, expected) in listOf("10.25," to "10.25", "C++!" to "C++", ".NET" to ".NET", "…" to "…")) {
            val cue = VoiceStreamCue(text.indices, 0, 1, VoiceWordCueProvenance.ESTIMATED).lexicalRange(text)
            assertEquals(expected, text.slice(cue.range))
        }
    }

    @Test fun modelBoundariesIncludePaddingAndSpacesButHighlightOnlyTheWords() {
        // PAD, word, space, word, punctuation, PAD. Each value is in model duration units.
        val frames = kokoroWordFrames(longArrayOf(2, 4, 1, 3, 1, 2), intArrayOf(1, 2, 3, 4), 2, 7800)
        assertArrayEquals(intArrayOf(1200, 3600, 4200, 6000), frames)
        val clock = VoicePcmCaptions()
        clock.append(0, "Go go.", listOf(0..1, 3..4), frames, 7800)
        assertNull(clock.consumed(1199))
        assertEquals(0..1, clock.consumed(1200)?.range)
        assertNull(clock.consumed(3400))
        assertEquals(IntRange.EMPTY, clock.consumed(3600)?.range)
        assertEquals(3..4, clock.consumed(4200)?.range)
        assertEquals(IntRange.EMPTY, clock.consumed(6000)?.range)
        assertNull(clock.consumed(7800))
    }

    @Test fun expansionsKeepOneHighlightAcrossSpokenWordsAndLimitersRemapEveryBoundary() {
        val limiter = VoiceQuietLimiter(1000, 2)
        val clock = VoicePcmCaptions()
        val frames = intArrayOf(2, 4, 7, 9, 10, 11)
        val samples = limiter.filter(floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 1f, 0f, 1f, 0f, 0f), frames)
        assertArrayEquals(intArrayOf(2, 4, 6, 8, 9, 10), frames)
        clock.append(0, "We're here", listOf(0..4, 0..4, 6..9), frames, samples.size)
        assertEquals(0..4, clock.consumed(2)?.range)
        assertNull(clock.consumed(6))
        assertEquals(IntRange.EMPTY, clock.consumed(8)?.range)
        assertEquals(6..9, clock.consumed(9)?.range)
        assertEquals(IntRange.EMPTY, clock.consumed(10)?.range)

        val next = intArrayOf(3, 4)
        val nextSamples = limiter.filter(floatArrayOf(0f, 0f, 0f, 1f), next)
        assertArrayEquals(intArrayOf(0, 1), next)
        clock.append(1, "Ok", listOf(0..1), next, nextSamples.size)
        assertEquals(1, clock.consumed(samples.size.toLong())?.segment)
        assertEquals(IntRange.EMPTY, clock.consumed(samples.size + 1L)?.range)
    }

    @Test fun oneInferencePhraseKeepsTheOriginalOffsetsThroughNormalizationAndExpansion() {
        val text = "API v2.5, café cafe\u0301 hol\u00ADiday #12!"
        val timed = KokoroSpeechText.timed(text) { true }.phrases!!.single()
        assertEquals("a p i version two point five, cafe cafe holiday number twelve!", timed.speech)
        assertEquals(
            List(3) { "API" } + List(4) { "v2.5" } + listOf("café", "cafe\u0301", "hol\u00ADiday", "#", "12"),
            timed.wordRanges.map { text.substring(it) }
        )
    }

    @Test fun expansionCanCrossPhraseLimitWithoutLosingItsOriginalRange() {
        val text = "go ".repeat(15) + "API done."
        val phrases = KokoroSpeechText.timed(text) { true }.phrases!!
        assertEquals(listOf("go ".repeat(15).trimEnd(), "a p i done."), phrases.map { it.speech })
        assertEquals(listOf("API", "API", "API", "done"), phrases.flatMap { it.wordRanges }.takeLast(4).map { text.substring(it) })
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedTimingCannotReleaseMisalignedAudio() {
        kokoroWordFrames(longArrayOf(1, 2, 1), intArrayOf(1, 2), 1, 2401)
    }

    @Test fun observedPrefixesReplaceQueuedAndActiveWordEndsWithoutMovingTheStart() {
        val captions = VoicePcmCaptions()
        val prefix = VoiceStreamCue(0..2, 0, 10, VoiceWordCueProvenance.ESTIMATED, finalEnd = false)
        captions.appendStream(1, "one two", 0, listOf(prefix), 10)
        for (end in 11L..100L) {
            captions.appendStream(1, "one two", 0, listOf(prefix.copy(endFrame = end)), 1)
        }
        val first = requireNotNull(captions.consumed(1))
        assertEquals(0L, first.frame)
        assertEquals(100L, first.endFrame)
        assertEquals(0..2, first.range)
        assertNull(captions.consumed(1))
        for (end in 101L..200L) {
            captions.appendStream(1, "one two", 0, listOf(prefix.copy(endFrame = end)), 1)
        }
        assertNull(captions.consumed(1, advancing = false))
        val extended = requireNotNull(captions.consumed(1))
        assertEquals(first.frame, extended.frame)
        assertEquals(first.range, extended.range)
        assertEquals(first.provenance, extended.provenance)
        assertEquals(200L, extended.endFrame)
        assertNull(captions.consumed(1))
        // No end marker exists until finalization, so an empty refill keeps the word.
        assertNull(captions.consumed(200))
        assertEquals(0..2, captions.active()?.range)
        captions.appendStream(1, "one two", 0, listOf(prefix.copy(endFrame = 200, finalEnd = true)), 0)
        assertEquals(IntRange.EMPTY, captions.consumed(200)?.range)
        assertNull(captions.consumed(200))
        assertEquals(200L, captions.streamStart())
    }

    @Test fun unchangedEndCanFinalizeAndStartTheNextPrefixInTheSameCallback() {
        val captions = VoicePcmCaptions()
        val first = VoiceStreamCue(0..2, 0, 10, VoiceWordCueProvenance.NATIVE_DURATION, finalEnd = false)
        captions.appendStream(1, "one two", 0, listOf(first), 10)
        assertEquals(0..2, captions.consumed(5)?.range)
        val second = VoiceStreamCue(4..6, 10, 15, VoiceWordCueProvenance.NATIVE_DURATION, finalEnd = false)
        captions.appendStream(1, "one two", 0, listOf(first.copy(finalEnd = true), second), 5)
        assertEquals(4..6, captions.consumed(10)?.range)
        assertEquals(15L, captions.active()?.endFrame)
        captions.appendStream(1, "one two", 0, listOf(second.copy(finalEnd = true)), 0)
        assertEquals(IntRange.EMPTY, captions.consumed(15)?.range)
    }

    @Test fun revisionsCannotChangeWordIdentityMoveBackwardsOrExceedObservedAudio() {
        val original = VoiceStreamCue(0..2, 0, 10, VoiceWordCueProvenance.ESTIMATED, finalEnd = false)
        val invalid = listOf(
            original.copy(startFrame = 1),
            original.copy(range = 4..6),
            original.copy(provenance = VoiceWordCueProvenance.NATIVE_DURATION),
            original.copy(endFrame = 9),
            original.copy(endFrame = 11)
        )
        for (revision in invalid) {
            val captions = VoicePcmCaptions()
            captions.appendStream(1, "one two", 0, listOf(original), 10)
            assertThrows(IllegalArgumentException::class.java) { captions.appendStream(1, "one two", 0, listOf(revision), 0) }
        }
        for ((segment, text, start) in listOf(Triple(2, "one two", 0L), Triple(1, "one ten", 0L), Triple(1, "one two", 1L))) {
            val captions = VoicePcmCaptions()
            captions.appendStream(1, "one two", 0, listOf(original), 10)
            assertThrows(IllegalArgumentException::class.java) { captions.appendStream(segment, text, start, listOf(original), 1) }
        }
    }

    @Test fun anOpenWordMustFinishBeforeAnotherWordAndCannotReopenAfterFinalization() {
        val original = VoiceStreamCue(0..2, 0, 10, VoiceWordCueProvenance.ESTIMATED, finalEnd = false)
        val next = VoiceStreamCue(4..6, 10, 15, VoiceWordCueProvenance.ESTIMATED, finalEnd = false)
        val captions = VoicePcmCaptions()
        captions.appendStream(1, "one two", 0, listOf(original), 10)
        assertThrows(IllegalArgumentException::class.java) { captions.appendStream(1, "one two", 0, listOf(next), 5) }
        captions.appendStream(1, "one two", 0, listOf(original.copy(finalEnd = true)), 0)
        assertThrows(IllegalArgumentException::class.java) { captions.appendStream(1, "one two", 0, listOf(original), 0) }
        assertThrows(IllegalArgumentException::class.java) { captions.appendStream(1, "one two", 0, listOf(original.copy(finalEnd = true)), 0) }
        assertThrows(IllegalArgumentException::class.java) { captions.appendStream(1, "one two", 0, listOf(next.copy(startFrame = 9)), 5) }
        captions.appendStream(1, "one two", 0, listOf(next), 5)
        assertEquals(4..6, captions.consumed(10)?.range)
    }

    @Test fun emptySourceRangesDoNotOwnAnOpenCaption() {
        val captions = VoicePcmCaptions()
        captions.appendStream(1, "one", 0, listOf(VoiceStreamCue(IntRange.EMPTY, 0, 5, VoiceWordCueProvenance.ESTIMATED, finalEnd = false)), 5)
        captions.appendStream(1, "one", 0, listOf(VoiceStreamCue(0..2, 5, 10, VoiceWordCueProvenance.ESTIMATED)), 5)
        assertEquals(0..2, captions.consumed(5)?.range)
        assertEquals(IntRange.EMPTY, captions.consumed(10)?.range)
    }

    @Test fun refillClearsOnlyTheCompletedCurrentWordWithoutStartingTheNextWord() {
        val captions = VoicePcmCaptions()
        val first = VoiceStreamCue(0..2, 0, 10, VoiceWordCueProvenance.ESTIMATED, finalEnd = false)
        val next = VoiceStreamCue(4..6, 10, 20, VoiceWordCueProvenance.ESTIMATED, finalEnd = false)
        captions.appendStream(1, "one two", 0, listOf(first), 10)
        assertEquals(0..2, captions.consumed(10)?.range)
        captions.appendStream(1, "one two", 0, listOf(first.copy(finalEnd = true), next), 10)
        // An explicit pause still retains its word, even after its final metadata arrives.
        assertNull(captions.consumed(10, advancing = false))
        assertEquals(0..2, captions.active()?.range)
        assertEquals(IntRange.EMPTY, captions.consumed(10, advancing = false, completedThrough = 10)?.range)
        assertNull(captions.consumed(20, advancing = false, completedThrough = 20))
        assertEquals(IntRange.EMPTY, captions.active()?.range)
        assertEquals(4..6, captions.consumed(11)?.range)
        captions.appendStream(1, "one two", 0, listOf(next.copy(finalEnd = true)), 0)
        // Refill cannot move lastConsumed beyond the last advancing observation.
        assertNull(captions.consumed(20, advancing = false, completedThrough = 20))
        assertEquals(IntRange.EMPTY, captions.consumed(20)?.range)
        assertNull(captions.consumed(20))
    }

    @Test fun refillCompletionCannotClearAheadOfMeasuredPresentation() {
        val captions = VoicePcmCaptions()
        val prefix = VoiceStreamCue(0..2, 0, 20, VoiceWordCueProvenance.NATIVE_DURATION, finalEnd = false)
        captions.appendStream(1, "one", 0, listOf(prefix), 20)
        // A calibrated caption clock may be ahead of the actual presentation clock.
        assertEquals(0..2, captions.consumed(20)?.range)
        captions.appendStream(1, "one", 0, listOf(prefix.copy(finalEnd = true)), 0)
        assertNull(captions.consumed(20, advancing = false, completedThrough = 10))
        assertNull(captions.consumed(20, advancing = false, completedThrough = 19))
        assertEquals(0..2, captions.active()?.range)
        val cleared = requireNotNull(captions.consumed(20, advancing = false, completedThrough = 20))
        assertEquals(IntRange.EMPTY, cleared.range)
        assertEquals(20L, cleared.frame)
        assertNull(captions.consumed(20, advancing = false, completedThrough = 20))
        assertNull(captions.consumed(20))
    }

    @Test fun refillNeverPublishesAnUnpresentedFirstCue() {
        val captions = VoicePcmCaptions()
        captions.appendStream(1, "one", 0, listOf(VoiceStreamCue(0..2, 0, 10, VoiceWordCueProvenance.ESTIMATED)), 10)
        assertNull(captions.consumed(10, advancing = false, completedThrough = 10))
        assertNull(captions.active())
        assertEquals(0..2, captions.consumed(1)?.range)
        assertEquals(IntRange.EMPTY, captions.consumed(10)?.range)
    }
}
