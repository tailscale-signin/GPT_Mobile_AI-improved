package dev.chungjungsoo.gptmobile.data.voice

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePronunciationTransferTest {
    @Test fun roundTripRetainsAccentsPhrasesAndLocaleWithoutApplyingEntries() {
        val entries = mapOf("São Miguel" to "são miguel", "GPT-4" to "gê pê tê quatro", "Élodie" to "é lo die")
        val encoded = VoicePronunciationTransfer.encode("pt-PT-azores", entries)
        assertEquals(entries, VoicePronunciationTransfer.decode(encoded, "pt-PT-azores"))
        assertEquals(encoded, ByteArrayInputStream(encoded.toByteArray()).use(VoicePronunciationTransfer::read))
        assertThrows(IllegalArgumentException::class.java) { VoicePronunciationTransfer.decode(encoded, "pt-BR") }
        assertThrows(IllegalArgumentException::class.java) { VoicePronunciationTransfer.decode(encoded.replace("v1", "v2"), "pt-PT-azores") }
        assertEquals(mapOf("AI" to "aa ie"), VoicePronunciationTransfer.decode(VoicePronunciationTransfer.encode("nl", mapOf("AI" to "aa ie")), "nl"))
    }

    @Test fun canonicalMergeReplacesOnlyTheIntendedCorrection() {
        val current = mapOf("São" to "sow", "CPU" to "see pee you")
        val incoming = mapOf("SA\u0303O" to "são", "C++" to "see plus plus")
        val merged = VoicePronunciationTransfer.merge(current, incoming)
        assertEquals(mapOf("CPU" to "see pee you", "SA\u0303O" to "são", "C++" to "see plus plus"), merged)
        assertEquals(mapOf("São" to "sow", "CPU" to "see pee you"), current)
    }

    @Test fun capacityIsExpandedButOversizedMalformedAndDuplicateImportsAreRejected() {
        val entries = (1..VoicePronunciationOverrides.MAX_ENTRIES).associate { "term$it" to "term" }
        assertEquals(entries, VoicePronunciationTransfer.parseEntries(VoicePronunciationTransfer.editor(entries)))
        assertNull(VoicePronunciationTransfer.parseEntries(VoicePronunciationTransfer.editor(entries + ("extra" to "term"))))
        assertNull(VoicePronunciationTransfer.parseEntries("São = sow\nSA\u0303O = another"))
        assertNull(VoicePronunciationTransfer.parseEntries("term = <script>"))
        assertNull(VoicePronunciationTransfer.parseEntries("x".repeat(VoicePronunciationOverrides.MAX_EDITOR_CHARS + 1)))
        assertThrows(IllegalArgumentException::class.java) { VoicePronunciationTransfer.merge(entries, mapOf("extra" to "term")) }
        assertEquals(entries.size, VoicePronunciationTransfer.merge(entries, mapOf("TERM512" to "new term")).size)
        assertThrows(IllegalArgumentException::class.java) { VoicePronunciationTransfer.read(ByteArrayInputStream(ByteArray(VoicePronunciationTransfer.MAX_DOCUMENT_BYTES + 1))) }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { VoicePronunciationTransfer.read(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28))) }
    }

    @Test fun technicalReadingsStayBoundToTheirOriginalWordsAndNeverRewriteOtherLanguages() {
        val input = "Use GGUF, CUDA, ONNX, FP16 and BF16."
        val projected = VoicePronunciationDictionary(emptyMap()).project(input, englishFallback = true)
        for ((term, spoken) in mapOf("GGUF" to "G G U F", "CUDA" to "koo dah", "ONNX" to "O N N X", "FP16" to "F P sixteen", "BF16" to "B F sixteen")) {
            val start = projected.text.indexOf(spoken)
            assertTrue("Missing $term", start >= 0)
            assertEquals(term, input.substring(requireNotNull(projected.sourceRange(start, start + spoken.length))))
        }
        assertEquals("Use ggufTool FP160.", VoicePronunciationDictionary(emptyMap()).project("Use ggufTool FP160.", englishFallback = true).text)
        assertEquals("GPU FP16 CUDA", VoicePronunciationDictionary(emptyMap(), "fr-CA").project("GPU FP16 CUDA", englishFallback = true).text)
        assertEquals("unité", VoicePronunciationDictionary(mapOf("GPU" to "unité"), "fr-CA").project("GPU", englishFallback = true).text)
    }

    @Test fun regionalReadingsPreserveOrdinaryWordsAndPersonalOverridesWin() {
        assertEquals("mes amis me parlent", VoicePronunciationDictionary(emptyMap(), "fr-CA").project("mes amis me parlent").text)
        assertEquals("eu ia aos Açores", VoicePronunciationDictionary(emptyMap(), "pt-PT-azores").project("eu ia aos Açores").text)
        assertEquals("região autónoma dos Açores", VoicePronunciationDictionary(emptyMap(), "pt-PT-azores").project("R.A.A.").text)
        assertEquals("R.A.A.", VoicePronunciationDictionary(emptyMap(), "pt-BR").project("R.A.A.").text)
        assertEquals("minha região", VoicePronunciationDictionary(mapOf("R.A.A." to "minha região"), "pt-PT-azores").project("R.A.A.").text)
        assertEquals("madame Élodie, boulevard Saint-Laurent", VoicePronunciationDictionary(emptyMap(), "fr-CA").project("Mme Élodie, boul. Saint-Laurent").text)
        assertEquals("Dra.", VoicePronunciationDictionary(emptyMap(), "ja").project("Dra.").text)
    }
}
