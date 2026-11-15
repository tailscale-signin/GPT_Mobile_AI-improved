package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroLexiconTest {
    @Test fun parsesNativeWhitespaceAndPhraseFormatsWithoutInventingComponentEntries() {
        val words = KokoroLexicon.words(sequenceOf("\tHERE\th ˈ ɪ ɹ", "  Hello   h ə l ˈ O  ", "New York || n u j ɔ ɹ k", "", "# comment", "noPhones", "empty || "))
        assertEquals(setOf("here", "hello", "new york"), words)
        assertFalse("new" in words)
        assertFalse("york" in words)
    }

    @Test fun keysUseNativeCompatibleNfcAndSimpleLowercase() {
        val words = KokoroLexicon.words(sequenceOf("E\u0301COLE e k ɔ l", "İ i"))
        assertTrue("école" in words)
        assertTrue("i" in words)
    }

    @Test fun malformedBomEntryIsNotClaimedSpeakableByTheNativeLookup() {
        val words = KokoroLexicon.words(sequenceOf("\uFEFFhere h ˈ ɪ ɹ", "hello h ə l ˈ O"))
        assertFalse("here" in words)
        assertEquals(9, KokoroLexicon.missingRequiredWords(words))
    }

    @Test fun pinnedUsAndGbCommonWordsRemainWordsInEveryCapitalization() {
        for (dialect in listOf("us", "gb")) {
            val words = requireNotNull(javaClass.getResourceAsStream("/voice/kokoro/lexicon-$dialect-en.fixture.txt")).bufferedReader().useLines(KokoroLexicon::words)
            assertEquals(0, KokoroLexicon.missingRequiredWords(words))
            for (text in listOf("here.", "Here.", "HERE.", "h\u200Bere.")) {
                val projected = KokoroSpeechText.projected(text, words::contains)
                assertEquals("$dialect: $text", listOf("here."), projected.parts)
                assertEquals(0, projected.observation.spelledLetters)
            }
        }
    }
}
