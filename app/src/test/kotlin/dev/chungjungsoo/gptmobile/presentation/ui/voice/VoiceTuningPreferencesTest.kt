package dev.chungjungsoo.gptmobile.presentation.ui.voice

import dev.chungjungsoo.gptmobile.data.voice.VoicePronunciationOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceTuningPreferencesTest {
    @Test fun pronunciationEditorPreservesWordsAndRejectsAmbiguousOrExecutableInput() {
        assertEquals(mapOf("Melo" to "mellow", "Kokoro" to "koh koh roh"), parsePronunciationEditor("Melo = mellow\nKokoro = koh koh roh"))
        assertNull(parsePronunciationEditor("Melo=mellow\nMelo=me low"))
        assertNull(parsePronunciationEditor("Melo=<script>"))
        assertNull(parsePronunciationEditor("missing separator"))
        assertEquals(emptyMap<String, String>(), parsePronunciationEditor(""))
    }

    @Test fun editorSupportsAccentedNamesAndTechnicalTermsButRejectsCanonicalDuplicates() {
        assertEquals(mapOf("São" to "sow", "C++" to "see plus plus", "GPT-4" to "gee pee tee four"), parsePronunciationEditor("São = sow\nC++ = see plus plus\nGPT-4 = gee pee tee four"))
        assertNull(parsePronunciationEditor("São = sow\nSA\u0303O = another"))
        assertNull(parsePronunciationEditor("O'Connor = oh connor\nO’Connor = another"))
        assertEquals(512, parsePronunciationEditor((1..VoicePronunciationOverrides.MAX_ENTRIES).joinToString("\n") { "term$it = term" })?.size)
        assertNull(parsePronunciationEditor((1..VoicePronunciationOverrides.MAX_ENTRIES + 1).joinToString("\n") { "term$it = term" }))
    }

    @Test fun regionalDictionariesInheritSharedCorrectionsAndKeepOtherLanguagesSeparate() {
        val original = VoiceTuningPreferences(pronunciation = mapOf("GPU" to "gee pee you"), localizedPronunciations = mapOf("pt" to mapOf("São" to "são", "nome" to "nome")))
        assertEquals(mapOf("São" to "são", "nome" to "nome"), original.dictionary("pt-BR"))
        val regional = original.copy(pronunciationLocales = mapOf("pt" to "pt-PT-azores"))
            .withDictionary("pt", mapOf("SA\u0303O" to "sow", "Melo" to "mê lo"))
        assertEquals("pt-PT-azores", regional.pronunciationLanguage("pt-BR"))
        assertEquals(mapOf("nome" to "nome", "SA\u0303O" to "sow", "Melo" to "mê lo"), regional.dictionary("pt"))
        assertEquals(mapOf("SA\u0303O" to "sow", "Melo" to "mê lo"), regional.editableDictionary("pt"))
        assertEquals(mapOf("São" to "são", "nome" to "nome"), regional.inheritedDictionary("pt"))
        assertEquals(original.dictionary("pt"), regional.withDictionary("pt", emptyMap()).dictionary("pt"))
        assertEquals(original.pronunciation, regional.dictionary("en-GB"))
        assertEquals(emptyMap<String, String>(), regional.dictionary("fr-CA"))
    }

    @Test fun invalidSavedRegionCannotRedirectPronunciationsToAnotherLanguage() {
        val invalid = VoiceTuningPreferences(pronunciationLocales = mapOf("pt" to "fr-CA"))
        assertEquals("pt", invalid.pronunciationLanguage("pt-PT"))
        assertEquals("fr", invalid.pronunciationLanguage("fr-CA"))
    }
}
