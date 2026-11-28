package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRecognitionTranscriptTest {
    @Test
    fun sherpaSegmentOffsetsPreserveTokenEmissionsWithoutInventingWordEnds() {
        val result = tokens(offset = 2.0, audioFrames = 48_000)

        assertEquals(listOf(33_600L, 40_000L), result.timings.map { it.startFrame })
        assertEquals(listOf(0 until 5, 6 until 11), result.timings.map { it.range })
        assertTrue(result.timings.all { it.endFrame == null && it.provenance == VoiceRecognitionTimingProvenance.TOKEN_EMISSION })
        assertNull(result.wordRangeAt(35_000))
        assertNull(result.latestTokenRangeAt(33_599))
        assertEquals(0 until 5, result.latestTokenRangeAt(35_000))
        assertEquals(6 until 11, result.latestTokenRangeAt(40_000))
    }

    @Test
    fun sentencePieceAndWordPieceSubwordsMapOnlyToTheirExactSourceSpans() {
        val sentencePiece = tokens(pieces = listOf("\u2581Hel", "lo", "\u2581world"), times = listOf(.1, .2, .5))
        val wordPiece = tokens(pieces = listOf("Hel", "##lo", "world"), times = listOf(.1, .2, .5))

        assertEquals(listOf(0 until 3, 3 until 5, 6 until 11), sentencePiece.timings.map { it.range })
        assertEquals(sentencePiece.timings, wordPiece.timings)
        val repeated = tokens(text = "go go", pieces = listOf("\u2581go", "\u2581go"))
        assertEquals(listOf(0 until 2, 3 until 5), repeated.timings.map { it.range })
    }

    @Test
    fun unsupportedByteTokensAndTextDisagreementKeepTextButRejectTheEntireTimingSet() {
        for (pieces in listOf(listOf("\u2581Hello", "<0x77>"), listOf("\u2581Hello", "\u2581there"), listOf("\u2581Hello", ""))) {
            val result = tokens(pieces = pieces)
            assertEquals("Hello world", result.text)
            assertTrue(result.timings.isEmpty())
            assertEquals(VoiceRecognitionTimingIssue.SOURCE_MISMATCH, result.timingIssue)
        }
        assertEquals(VoiceRecognitionTimingIssue.TIMESTAMP_SHAPE, tokens(times = listOf(.1)).timingIssue)
        assertEquals(VoiceRecognitionTimingIssue.SOURCE_MISMATCH, tokens(text = "Hello world extra").timingIssue)
    }

    @Test
    fun malformedNonMonotonicAndOutOfAudioTimesNeverReachCaptions() {
        for (times in listOf(listOf(Double.NaN, .5), listOf(.1, Double.POSITIVE_INFINITY), listOf(-.1, .5), listOf(.5, .1), listOf(.1, 1.001))) {
            val result = tokens(times = times)
            assertEquals(VoiceRecognitionTimingIssue.INVALID_BOUNDARY, result.timingIssue)
            assertTrue(result.timings.isEmpty())
        }
        assertEquals(VoiceRecognitionTimingIssue.INVALID_BOUNDARY, tokens(offset = Double.NaN).timingIssue)
        assertEquals(VoiceRecognitionTimingIssue.INVALID_BOUNDARY, tokens(offset = -.1).timingIssue)
        assertEquals(VoiceRecognitionTimingIssue.INVALID_BOUNDARY, tokens(offset = 1.0).timingIssue)
    }

    @Test
    fun utf16RangesCannotBisectSurrogatePairs() {
        val complete = tokens(text = "\uD83D\uDC4B", pieces = listOf("\uD83D\uDC4B"), times = listOf(.1))
        assertEquals(0 until 2, complete.timings.single().range)
        val split = tokens(text = "\uD83D\uDC4B", pieces = listOf("\uD83D", "\uDC4B"), times = listOf(.1, .2))
        assertEquals(VoiceRecognitionTimingIssue.SOURCE_MISMATCH, split.timingIssue)
        assertEquals("", complete.trimmed(1).text)
        assertTrue(complete.trimmed(1).timings.isEmpty())
    }

    @Test
    fun suppliedWordIntervalsRetainTheirRealEndsAndPunctuationDoesNotShiftSourceWords() {
        val result = words("Hello, world!", listOf(VoiceRecognitionWord("Hello", .1, .3), VoiceRecognitionWord("world", .5, .8)))

        assertEquals(listOf(0 until 5, 7 until 12), result.timings.map { it.range })
        assertEquals(listOf(4_800L, 12_800L), result.timings.map { it.endFrame })
        assertEquals(0 until 5, result.wordRangeAt(1_600))
        assertNull(result.wordRangeAt(4_800))
        assertNull(result.wordRangeAt(7_999))
        assertEquals(7 until 12, result.wordRangeAt(8_000))
        assertNull(result.latestTokenRangeAt(8_000))
    }

    @Test
    fun missingWordEndsStayUnknownAndOverlapsOrWrongWordsRejectAllTiming() {
        val startOnly = words("Hello world", listOf(VoiceRecognitionWord("Hello", .1), VoiceRecognitionWord("world", .5)))
        assertTrue(startOnly.timings.all { it.endFrame == null })
        assertNull(startOnly.wordRangeAt(2_000))
        val overlap = words("Hello world", listOf(VoiceRecognitionWord("Hello", .1, .6), VoiceRecognitionWord("world", .5, .8)))
        assertEquals(VoiceRecognitionTimingIssue.INVALID_BOUNDARY, overlap.timingIssue)
        val wrong = words("Hello world", listOf(VoiceRecognitionWord("Hello", .1, .3), VoiceRecognitionWord("there", .5, .8)))
        assertEquals(VoiceRecognitionTimingIssue.SOURCE_MISMATCH, wrong.timingIssue)
        assertTrue(wrong.timings.isEmpty())
    }

    @Test
    fun displayTrimmingPreservesAbsoluteInputTimeButDictionaryCorrectionsDiscardOldRanges() {
        val source = tokens(text = " \tHello world  ")
        val trimmed = source.trimmed()
        assertEquals("Hello world", trimmed.text)
        assertEquals(listOf(0 until 5, 6 until 11), trimmed.timings.map { it.range })
        assertEquals(source.timings.map { it.startFrame }, trimmed.timings.map { it.startFrame })
        assertEquals(trimmed, source.withText("Hello world"))
        assertEquals("Hello", source.trimmed(5).text)
        assertEquals(listOf(0 until 5), source.trimmed(5).timings.map { it.range })
        val corrected = source.withText("Hello Earth")
        assertEquals("Hello Earth", corrected.text)
        assertTrue(corrected.timings.isEmpty())
        assertEquals(VoiceRecognitionTimingIssue.SOURCE_MISMATCH, corrected.timingIssue)
    }

    @Test
    fun streamRevisionLedgerRejectsLateCallbacksAndPreservesUsefulPartialOnBlankFinal() {
        val ledger = VoiceRecognitionRevisions(7)
        val first = tokens(revision = 1)
        assertNotNull(ledger.accept(first))
        assertNull(ledger.accept(first.copy(revision = 0)))
        assertNull(ledger.accept(first.copy(streamId = 8, revision = 2)))
        assertNull(ledger.accept(first.copy(revision = 2, audioFrames = 8_100)))
        assertNull(ledger.accept(first.copy(revision = 2, sampleRate = 48_000)))
        val final = ledger.accept(first.copy(text = "", timings = emptyList(), revision = 2, isFinal = true, audioFrames = 17_000))
        assertNotNull(final)
        assertEquals("Hello world", final!!.text)
        assertEquals(first.timings, final.timings)
        assertTrue(final.isFinal)
        assertNull(ledger.accept(first.copy(revision = 3, audioFrames = 18_000)))
        assertEquals(final, ledger.current)
    }

    @Test
    fun acceptedRevisionSnapshotsDoNotShareMutableNativeTimingLists() {
        val mutable = tokens().timings.toMutableList()
        val ledger = VoiceRecognitionRevisions(7)
        val accepted = ledger.accept(tokens().copy(timings = mutable))!!
        mutable.clear()
        assertEquals(2, accepted.timings.size)
    }

    @Test
    fun transcriptSizeLimitDoesNotLeaveAHalfSurrogateOrRetainUnmappedTiming() {
        val text = "a".repeat(7_999) + "\uD83D\uDC4B"
        val result = tokens(text = text, pieces = emptyList(), times = emptyList())
        assertEquals(7_999, result.text.length)
        assertFalse(result.text.last().isSurrogate())
        assertTrue(result.timings.isEmpty())
        assertEquals(VoiceRecognitionTimingIssue.TEXT_LIMIT, result.timingIssue)
    }

    private fun tokens(
        text: String = "Hello world",
        pieces: List<String> = listOf("\u2581Hello", "\u2581world"),
        times: List<Double> = listOf(.1, .5),
        offset: Double = 0.0,
        audioFrames: Long = 16_000,
        revision: Long = 1
    ) = VoiceRecognitionTranscript.fromTokens(text, pieces, times, offset, 7, revision, false, 16_000, audioFrames)

    private fun words(text: String, words: List<VoiceRecognitionWord>) =
        VoiceRecognitionTranscript.fromWords(text, words, 0.0, 7, 1, true, 16_000, 16_000)
}
