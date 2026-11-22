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
}
