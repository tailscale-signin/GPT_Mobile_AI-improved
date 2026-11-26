package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePronunciationOverridesTest {
    @Test fun savedPhrasesSurviveAProviderPauseAndUnspacedTextKeepsItsLanguage() {
        val french = VoicePronunciationDictionary(mapOf("Saint-Jean sur Richelieu" to "sain jean"), "fr")
        assertFalse(french.permitsSplit("Voici Saint-Jean ", 17, final = false))
        assertTrue(french.permitsSplit("Voici Saint-Jean ", 17, final = true))
        assertFalse(french.permitsSplit("Voici Saint-Jean sur Richelieu", 21, final = true))
        val japanese = VoicePronunciationDictionary(mapOf("東京" to "とうきょう"), "ja")
        assertEquals("私はとうきょうにいます。", japanese.project("私は東京にいます。").text)
        val buffer = VoiceSpeechBuffer()
        assertEquals(listOf("こんにちは。"), buffer.append("こんにちは。次は", final = false))
        assertEquals(listOf("次はこちらです。"), buffer.append("こんにちは。次はこちらです。", final = true))
    }

    @Test fun frenchReadingsAndPersonalPhrasesKeepOriginalCaptionOffsets() {
        val input = "Mme Élise attend l’ONU à Saint-Jean sur Richelieu, dossier #42."
        val dictionary = VoicePronunciationDictionary(mapOf("ONU" to "onu", "Saint-Jean sur Richelieu" to "sain jean"), "fr-CA")
        val projection = dictionary.project(VoiceSpeechText.forPlayback(input))
        assertEquals("madame Élise attend l’onu à sain jean, dossier numéro 42.", projection.text)
        val start = projection.text.indexOf("sain jean")
        assertEquals(input.indexOf("Saint-Jean") until input.indexOf(','), projection.sourceRange(start, start + 9))
        assertEquals("Mme", VoicePronunciationDictionary(emptyMap(), "es").project("Mme").text)
        assertEquals("madame", dictionary.project("Mme", englishFallback = true).text)
    }

    @Test fun nonLatinAndAccentedSpokenSpellingsAreValidAndPersonalReadingsWin() {
        val terms = mapOf("Mme" to "ma chère amie", "École du Nord" to "é-kol", "दिल्ली" to "दिल्ली", "東京" to "とうきょう")
        assertEquals(4, VoicePronunciationOverrides.clean(terms).size)
        assertEquals("ma chère amie", VoicePronunciationDictionary(terms, "fr").project("Mme").text)
        assertEquals("por ejemplo", VoicePronunciationDictionary(emptyMap(), "es").project("p. ej.").text)
    }

    @Test fun streamingKokoroReceivesNaturalTextWhileEnglishAlternativesShareItsExplicitReadings() {
        val original = "Élodie uses JSON, a GPU and MyTool."
        val configured = mutableMapOf("MyTool" to "json")
        val dictionary = VoicePronunciationDictionary(configured)
        configured["MyTool"] = "new tool"
        val natural = dictionary.project(original)
        val fallback = dictionary.project(original, englishFallback = true)
        assertEquals("Élodie uses JSON, a GPU and json.", natural.text)
        assertEquals("Élodie uses jason, a G P U and json.", fallback.text)
        for ((rendering, source) in listOf("jason" to "JSON", "G P U" to "GPU", "json" to "MyTool")) {
            val start = fallback.text.indexOf(rendering)
            val mapped = requireNotNull(fallback.sourceRange(start, start + rendering.length))
            assertEquals(source, original.substring(mapped))
        }
        val plan = VoiceStreamingPhrasePlan(original, dictionary, 1f, englishFallback = true)
        val chunks = generateSequence { plan.next(4) }.toList()
        assertEquals(fallback.text, chunks.joinToString("") { it.text })
        assertEquals(fallback.sourceCharacters, chunks.flatMap { it.sourceCharacters })
    }

    @Test fun nativeAndTextOnlyRoutesApplyExplicitReplacementsOnlyOnce() {
        val original = "MyTool uses JSON."
        val overrides = mapOf("MyTool" to "json", "JSON" to "jay son")
        val native = KokoroSpeechText.withPolicy(original, { true }, overrides, 16, timed = true)
        assertEquals(listOf("json uses jay son."), native.parts)
        assertEquals(listOf("MyTool", "uses", "JSON", "JSON"), native.phrases!!.flatMap { phrase -> phrase.wordRanges.map(original::substring) })
        assertEquals("json uses jay son.", VoicePronunciationDictionary(overrides).project(original, englishFallback = true).text)
    }

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
        val invalid = mapOf("<secret>" to "exposed", "name" to "<script>", "one two three four five six seven" to "phrase", "ok" to "a ".repeat(9).trim(), "x".repeat(49) to "long")
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
