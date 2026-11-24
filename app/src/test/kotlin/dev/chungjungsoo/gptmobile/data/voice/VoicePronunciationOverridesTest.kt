package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePronunciationOverridesTest {
    @Test fun accentedNamesAndLiteralTechnicalTermsCanBeCorrected() {
        val corrections = mapOf("São" to "sow", "C++" to "see plus plus", "C#" to "see sharp", ".NET" to "dot net", "GPT-4" to "gee pee tee four")
        val input = "São uses C++, C#, .NET and GPT-4."
        val projected = VoicePronunciationOverrides.project(input, corrections)
        assertEquals("sow uses see plus plus, see sharp, dot net and gee pee tee four.", projected.text)
        assertEquals(projected.text.length, projected.sourceCharacters.size)
        for ((original, replacement) in corrections) {
            val start = projected.text.indexOf(replacement)
            val source = requireNotNull(projected.sourceRange(start, start + replacement.length))
            assertEquals(original, input.substring(source))
        }
    }

    @Test fun canonicalUnicodeAndCurlyApostrophesMatchWithoutChangingDisplayedOffsets() {
        val input = "🙂 SA\u0303O O’Connor"
        val projected = VoicePronunciationOverrides.project(input, mapOf("São" to "sow", "O'Connor" to "oh connor"))
        assertEquals("🙂 sow oh connor", projected.text)
        assertEquals("SA\u0303O", input.substring(requireNotNull(projected.sourceRange(3, 6))))
        assertEquals("O’Connor", input.substring(requireNotNull(projected.sourceRange(7, 16))))
    }

    @Test fun onlyWholeTermsMatchAndLongerCorrectionsWin() {
        val input = "Melo, melanoma, C++, C#, C, GPT-4, GPT-40, .NET, internet."
        val projected = VoicePronunciationOverrides.project(input, mapOf("Melo" to "mellow", "C" to "see", "C++" to "cpp", "GPT-4" to "four", ".NET" to "dot net"))
        assertEquals("mellow, melanoma, cpp, C#, see, four, GPT-40, dot net, internet.", projected.text)
    }

    @Test fun replacementsAreNotInterpretedAsMoreDictionaryEntries() {
        val projected = VoicePronunciationOverrides.project("Melo Kokoro", mapOf("Melo" to "kokoro", "Kokoro" to "voice"))
        assertEquals("kokoro voice", projected.text)
        assertEquals(0..3, projected.sourceCharacters.first())
        assertEquals(5..10, projected.sourceCharacters.last())
    }

    @Test fun caseFoldingMatchesTheSavedTermEvenWhenUnicodeLowercaseUsesContext() {
        assertEquals("oh s oh s", VoicePronunciationOverrides.project("ΟΣ ος", mapOf("ΟΣ" to "oh s")).text)
    }

    @Test fun invalidOrExcessiveCorrectionsNeverEnterSpeech() {
        val invalid = mapOf("<secret>" to "exposed", "name" to "<script>", "two words" to "phrase", "ok" to "a ".repeat(9).trim(), "x".repeat(49) to "long")
        assertTrue(VoicePronunciationOverrides.clean(invalid).isEmpty())
        val input = "<secret> name two words ok"
        assertEquals(input, VoicePronunciationOverrides.project(input, invalid).text)
        assertFalse(VoicePronunciationOverrides.valid("", "empty"))
        assertFalse(VoicePronunciationOverrides.valid("name", "x".repeat(97)))
        assertTrue(VoicePronunciationOverrides.valid("François", "fran swah"))
    }

    @Test fun unchangedTextAndInvalidCallbackRangesArePreservedSafely() {
        val input = "Unknown Zoë 🙂 text"
        val projected = VoicePronunciationOverrides.project(input, emptyMap())
        assertEquals(input, projected.text)
        assertEquals(input.indices.map { it..it }, projected.sourceCharacters)
        assertNull(projected.sourceRange(-1, 2))
        assertNull(projected.sourceRange(2, 2))
        assertNull(projected.sourceRange(0, input.length + 1))
        assertEquals(input.indices, projected.sourceRange(0, input.length))
    }
}
