package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLiteralPronunciationPipelineTest {
    private val corrections = mapOf("snake_case" to "custom", "R#1" to "release")

    @Test fun actualBufferPreservesAcceptedLiteralKeysUntilCorrectionsAreApplied() {
        val input = "Read snake_case and R#1."
        val prepared = requireNotNull(VoiceSpeechBuffer(preserveLiteralTerms = true).append(input, final = true)).joinToString("")
        assertEquals(input, prepared)
        val projection = VoicePronunciationOverrides.project(prepared, corrections)
        assertEquals("Read custom and release.", projection.text)
        for ((original, replacement) in corrections) {
            val start = projection.text.indexOf(replacement)
            assertEquals(original, prepared.substring(requireNotNull(projection.sourceRange(start, start + replacement.length))))
        }
    }

    @Test fun nativeAndSdkConsumeTheSameBufferLiteralsAndKeepOriginalRanges() {
        val input = "Read snake_case and R#1."
        val prepared = requireNotNull(VoiceSpeechBuffer(preserveLiteralTerms = true).append(input, final = true)).joinToString("")
        val native = KokoroSpeechText.withPolicy(prepared, { true }, corrections, 16, timed = true)
        assertEquals("read custom and release.", requireNotNull(native.parts).joinToString(" "))
        assertEquals(listOf("Read", "snake_case", "and", "R#1"), requireNotNull(native.phrases).flatMap { it.wordRanges }.map { prepared.substring(it) })
        val plan = VoiceStreamingPhrasePlan(prepared, corrections, 1f)
        val phrases = generateSequence { plan.next(16) }.toList()
        assertEquals("Read custom and release.", phrases.joinToString("") { it.text })
        val characters = phrases.flatMap { it.sourceCharacters }
        val spoken = phrases.joinToString("") { it.text }
        for ((original, replacement) in corrections) {
            val first = spoken.indexOf(replacement)
            val range = characters[first].first..characters[first + replacement.length - 1].last
            assertEquals(original, prepared.substring(range))
        }
        assertTrue(phrases.all { it.text.length <= 128 && it.text.length == it.sourceCharacters.size })
    }

    @Test fun everyCharacterLiveBufferAndFinalReplayRetainTheSameLiteralTerms() {
        val input = "Read snake_case and R#1. Keep snake case and Rnumber 1 unchanged."
        val buffer = VoiceSpeechBuffer(preserveLiteralTerms = true)
        val live = mutableListOf<String>()
        for (end in 1..input.length) {
            val snapshot = input.take(end)
            live += requireNotNull(buffer.append(snapshot, final = false, earlyStart = true))
            assertTrue(input.startsWith(live.joinToString("")))
            assertTrue(requireNotNull(buffer.append(snapshot, final = false, earlyStart = true)).isEmpty())
        }
        live += requireNotNull(buffer.append(input, final = true, earlyStart = true))
        val replay = requireNotNull(VoiceSpeechBuffer(preserveLiteralTerms = true).append(input, final = true)).joinToString("")
        assertEquals(input, replay)
        assertEquals(replay, live.joinToString(""))
        val liveSpeech = live.joinToString("") { VoicePronunciationOverrides.project(it, corrections).text }
        assertEquals("Read custom and release. Keep snake case and Rnumber 1 unchanged.", liveSpeech)
        assertEquals(VoicePronunciationOverrides.project(replay, corrections).text, liveSpeech)
    }

    @Test fun literalCorrectionsCannotRevealReasoningCodeOrRawAndEncodedUrls() {
        val input = "Read snake_case. <think>R#1</think> https://example.test/R#1 " +
            "&#104;ttps://example.test/snake_case\n```text\nR#1\n```\n`snake_case`"
        val buffer = VoiceSpeechBuffer(preserveLiteralTerms = true)
        val chunks = mutableListOf<String>()
        for (end in 1..input.length) {
            chunks += requireNotNull(buffer.append(input.take(end), final = false, earlyStart = true))
            val spoken = chunks.joinToString("") { VoicePronunciationOverrides.project(it, corrections).text }
            assertFalse(spoken.contains("release"))
            assertFalse(spoken.contains("example"))
            assertFalse(spoken.contains("http"))
        }
        chunks += requireNotNull(buffer.append(input, final = true, earlyStart = true))
        val spoken = chunks.joinToString("") { VoicePronunciationOverrides.project(it, corrections).text }
        assertEquals(1, Regex("custom").findAll(spoken).count())
        assertFalse(spoken.contains("release"))
        assertTrue(spoken.contains("Code is available in the chat."))
    }

    @Test fun unmatchedIdentifiersKeepTheirDefaultRenderingWithoutCreatingNewMatches() {
        val input = "Read snake_case and HTTP__status with _clear_ **labels** and R#1."
        val prepared = requireNotNull(VoiceSpeechBuffer(preserveLiteralTerms = true).append(input, final = true)).joinToString("")
        assertEquals(VoiceSpeechText.project(input), VoicePronunciationOverrides.project(prepared, emptyMap()).text)
        val neighbors = "snake_casework R#10 snake case Rnumber 1"
        val preparedNeighbors = requireNotNull(VoiceSpeechBuffer(preserveLiteralTerms = true).append(neighbors, final = true)).joinToString("")
        val projected = VoicePronunciationOverrides.project(preparedNeighbors, corrections)
        assertEquals("snake casework Rnumber 10 snake case Rnumber 1", projected.text)
        assertFalse(projected.text.contains("custom"))
        assertFalse(projected.text.contains("release"))
        assertEquals(projected.text.length, projected.sourceCharacters.size)
        assertTrue(projected.sourceCharacters.all { it.first >= 0 && it.last < neighbors.length })
    }

    @Test fun canonicalUnicodeCorrectionsMapTheWholeOriginalLiteralIdentifier() {
        val input = "Sa\u0303o_case and R#1"
        val prepared = requireNotNull(VoiceSpeechBuffer(preserveLiteralTerms = true).append(input, final = true)).joinToString("")
        assertEquals(input, prepared)
        val projected = VoicePronunciationOverrides.project(prepared, mapOf("São_case" to "sow custom", "R#1" to "release"))
        assertEquals("sow custom and release", projected.text)
        assertEquals("Sa\u0303o_case", input.substring(requireNotNull(projected.sourceRange(0, 10))))
        assertEquals("R#1", input.substring(requireNotNull(projected.sourceRange(15, 22))))
    }

    @Test fun literalSpansCannotJumpOverInteriorRawOrEntityEncodedSourceAddresses() {
        val candidates = listOf(
            "x_https://example.test/R#1.",
            "x_www.example.test/R#1.",
            "R#www.example.test/R#1.",
            "x_h&#116;tps://example.test/R#1.",
            "x_https&#58;//example.test/R#1."
        )
        for (candidate in candidates) {
            assertInteriorFiltering(candidate, corrections + mapOf("x_https" to "leaked", "x_www.example.test" to "leaked", "R#www.example.test" to "leaked"))
        }
    }

    @Test fun survivingLiteralCorrectionsKeepTheirRangesAfterAnInteriorAddressIsRemoved() {
        val input = "See x_https://example.test/private then snake_case and R#1."
        val aliases = corrections + mapOf("x_https" to "leaked")
        val prepared = requireNotNull(VoiceSpeechBuffer(preserveLiteralTerms = true).append(input, final = true)).joinToString("")
        // Keep the separators on both sides of the removed address, as the existing projection does.
        assertEquals("See x  then snake_case and R#1.", prepared)
        assertEquals(VoiceSpeechText.project(input), VoicePronunciationOverrides.project(prepared, emptyMap()).text)
        val native = KokoroSpeechText.withPolicy(prepared, { true }, aliases, 16, timed = true)
        val nativeText = requireNotNull(native.parts).joinToString(" ")
        assertTrue(nativeText.contains("custom") && nativeText.contains("release"))
        assertFalse(nativeText.contains("leaked") || nativeText.contains("example") || nativeText.contains("https"))
        val nativeRanges = requireNotNull(native.phrases).flatMap { it.wordRanges }.map { prepared.substring(it) }
        assertTrue(nativeRanges.contains("snake_case") && nativeRanges.contains("R#1"))
        val plan = VoiceStreamingPhrasePlan(prepared, aliases, 1f)
        val phrases = generateSequence { plan.next(16) }.toList()
        val spoken = phrases.joinToString("") { it.text }
        assertEquals("See x  then custom and release.", spoken)
        val characters = phrases.flatMap { it.sourceCharacters }
        for ((original, replacement) in corrections) {
            val first = spoken.indexOf(replacement)
            val range = characters[first].first..characters[first + replacement.length - 1].last
            assertEquals(original, prepared.substring(range))
        }
    }

    @Test fun literalSpansCannotJumpOverInteriorReferenceSuppression() {
        for (candidate in listOf("x_0123456789abcdef0123456789abcdef", "x_01234567-89ab-cdef-0123-456789abcdef")) {
            assertTrue(VoicePronunciationOverrides.valid(candidate, "leaked"))
            assertInteriorFiltering(candidate, mapOf(candidate to "leaked"))
        }
    }

    @Test fun survivingCorrectionsKeepSourceRangesAcrossInteriorAddressRemoval() {
        val addresses = listOf(
            "x_https://example.test/private",
            "x_h&#116;tps://example.test/private",
            "R#www.example.test"
        )
        val aliases = corrections + mapOf("x_https" to "leaked", "x_h" to "leaked", "R#www.example.test" to "leaked")
        for (address in addresses) {
            val input = "Read snake_case. $address Then R#1."
            val prepared = requireNotNull(VoiceSpeechBuffer(preserveLiteralTerms = true).append(input, final = true)).joinToString("")
            assertFalse(prepared.contains("example"))
            val projection = VoicePronunciationOverrides.project(prepared, aliases)
            assertFalse(projection.text.contains("leaked"))
            val native = KokoroSpeechText.withPolicy(prepared, { true }, aliases, 16, timed = true)
            val nativeWords = requireNotNull(native.phrases).flatMap { it.wordRanges }.map { prepared.substring(it) }
            val plan = VoiceStreamingPhrasePlan(prepared, aliases, 1f)
            val phrases = generateSequence { plan.next(16) }.toList()
            val spoken = phrases.joinToString("") { it.text }
            val characters = phrases.flatMap { it.sourceCharacters }
            assertEquals(projection.text, spoken)
            assertEquals(spoken.length, characters.size)
            for ((original, replacement) in corrections) {
                val start = spoken.indexOf(replacement)
                assertTrue(start >= 0)
                val end = start + replacement.length
                assertEquals(original, prepared.substring(requireNotNull(projection.sourceRange(start, end))))
                assertEquals(original, prepared.substring(characters[start].first..characters[end - 1].last))
                assertTrue(original in nativeWords)
            }
        }
    }

    private fun assertInteriorFiltering(candidate: String, aliases: Map<String, String>) {
        val input = "Before $candidate After."
        val expected = VoiceSpeechText.project(input)
        val buffer = VoiceSpeechBuffer(preserveLiteralTerms = true)
        val chunks = mutableListOf<String>()
        for (end in 1..input.length) {
            val snapshot = input.take(end)
            assertEquals(VoiceSpeechText.project(snapshot, final = false), VoiceSpeechText.forPlayback(snapshot, final = false))
            chunks += requireNotNull(buffer.append(snapshot, final = false, earlyStart = true))
            val spoken = chunks.joinToString("") { VoicePronunciationOverrides.project(it, aliases).text }
            assertFalse(spoken.contains("leaked"))
            assertFalse(spoken.contains("release"))
            assertFalse(spoken.contains("example"))
            assertFalse(spoken.contains("http"))
        }
        chunks += requireNotNull(buffer.append(input, final = true, earlyStart = true))
        assertEquals(expected, chunks.joinToString(""))
        assertEquals(expected, VoiceSpeechText.forPlayback(input))
        assertEquals(expected, VoicePronunciationOverrides.project(chunks.joinToString(""), aliases).text)
        val prepared = chunks.joinToString("")
        val native = KokoroSpeechText.withPolicy(prepared, { true }, aliases, 16, timed = true)
        val nativeWithoutAliases = KokoroSpeechText.withPolicy(expected, { true }, emptyMap(), 16, timed = true)
        assertEquals(nativeWithoutAliases.parts, native.parts)
        val plan = VoiceStreamingPhrasePlan(prepared, aliases, 1f)
        val phrases = generateSequence { plan.next(16) }.toList()
        assertEquals(expected, phrases.joinToString("") { it.text })
        assertTrue(phrases.all { it.text.length == it.sourceCharacters.size })
    }
}
