package dev.chungjungsoo.gptmobile.presentation.ui.voice

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
        assertNull(parsePronunciationEditor((1..101).joinToString("\n") { "term$it = term" }))
    }
}
