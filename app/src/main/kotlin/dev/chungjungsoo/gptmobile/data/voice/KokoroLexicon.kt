package dev.chungjungsoo.gptmobile.data.voice

import java.text.Normalizer

/** Mirrors the text-key formats and Unicode normalization used by sherpa-onnx 2.0.1. */
internal object KokoroLexicon {
    private val whitespace = Regex("\\s+")
    private val requiredWords = setOf("here", "the", "you", "hello", "word", "voice", "answer", "this", "that", "read")

    fun words(lines: Sequence<String>): Set<String> = buildSet {
        for (line in lines) {
            if (line.isBlank() || line.trimStart().startsWith('#')) continue
            val separator = line.indexOf("||")
            val key: String
            val phones: String
            if (separator >= 0) {
                key = line.substring(0, separator).trim()
                phones = line.substring(separator + 2).trim()
            } else {
                val parts = line.trim().split(whitespace, limit = 2)
                if (parts.size != 2) continue
                key = parts[0]
                phones = parts[1].trim()
            }
            // A BOM is not part of a native lookup key; do not falsely mark its word speakable.
            // Verified model assets have no BOM. A malformed/replaced pack must fail the health gate.
            if (key.isNotEmpty() && '\uFEFF' !in key && phones.isNotEmpty()) add(normalize(key))
        }
    }

    fun missingRequiredWords(words: Set<String>): Int = requiredWords.count { it !in words }

    private fun normalize(key: String): String {
        val lowered = buildString {
            Normalizer.normalize(key, Normalizer.Form.NFC).codePoints().forEach { appendCodePoint(Character.toLowerCase(it)) }
        }
        return Normalizer.normalize(lowered, Normalizer.Form.NFC)
    }
}
