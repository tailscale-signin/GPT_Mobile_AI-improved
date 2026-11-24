package dev.chungjungsoo.gptmobile.data.voice

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

internal data class VoicePronunciationProjection(val text: String, val sourceCharacters: List<IntRange>) {
    fun sourceRange(start: Int, end: Int): IntRange? =
        if (start in 0 until end && end <= sourceCharacters.size) sourceCharacters[start].first..sourceCharacters[end - 1].last else null
}

/** Bounded spoken-English substitutions; pronunciation never enters diagnostic output. */
internal object VoicePronunciationOverrides {
    private val word = Regex("\\.?[\\p{L}][\\p{L}\\p{M}\\p{N}'’._+#-]*")
    private val spoken = Regex("[a-zA-Z]+(?:'[a-zA-Z]+)*(?: [a-zA-Z]+(?:'[a-zA-Z]+)*)*")

    fun valid(original: String, pronunciation: String): Boolean =
        original.length in 1..48 &&
            word.matches(original) &&
            pronunciation.length in 1..96 &&
            spoken.matches(pronunciation) &&
            pronunciation.count { it == ' ' } < 8

    fun clean(overrides: Map<String, String>): Map<String, String> = overrides.entries.take(128).mapNotNull { (original, pronunciation) ->
        val originalKey = original.trim()
        val value = pronunciation.trim().replace(Regex("\\s+"), " ")
        if (valid(originalKey, value)) key(originalKey) to value.lowercase(Locale.ROOT) else null
    }.toMap()

    /** Canonically equivalent names use one saved correction, without removing their accents. */
    fun key(value: String): String = Normalizer.normalize(value.replace('’', '\''), Normalizer.Form.NFC).lowercase(Locale.ROOT).replace('ς', 'σ')

    /**
     * Apply literal whole-term corrections before any speech-engine tokenizer changes the text.
     * Substitutions are one pass and every spoken character points to the original display term.
     * No correction, matched term or generated pronunciation is logged or retained globally.
     */
    fun project(input: String, overrides: Map<String, String>): VoicePronunciationProjection {
        val entries = clean(overrides)
        if (entries.isEmpty()) return VoicePronunciationProjection(input, input.indices.map { it..it })
        val canonical = StringBuilder()
        val originalRanges = mutableListOf<IntRange>()
        val characters = BreakIterator.getCharacterInstance(Locale.ROOT)
        characters.setText(input)
        var start = characters.first()
        var end = characters.next()
        while (end != BreakIterator.DONE) {
            val normalized = key(input.substring(start, end))
            canonical.append(normalized)
            repeat(normalized.length) { originalRanges += start until end }
            start = end
            end = characters.next()
        }
        val normalized = canonical.toString()
        val candidates = entries.entries.sortedByDescending { it.key.length }.groupBy { it.key.first() }
        val spoken = StringBuilder()
        val positions = mutableListOf<IntRange>()
        var cursor = 0
        fun original(until: Int) {
            while (cursor < until) {
                spoken.append(input[cursor])
                positions += cursor..cursor
                cursor++
            }
        }
        var index = 0
        while (index < normalized.length) {
            val replacement = if (boundaryBefore(normalized, index)) {
                candidates[normalized[index]]?.firstOrNull { entry ->
                    normalized.startsWith(entry.key, index) && boundaryAfter(normalized, index + entry.key.length)
                }
            } else {
                null
            }
            if (replacement == null) {
                index++
                continue
            }
            val last = index + replacement.key.length - 1
            val source = originalRanges[index].first..originalRanges[last].last
            original(source.first)
            spoken.append(replacement.value)
            repeat(replacement.value.length) { positions += source }
            cursor = source.last + 1
            index = last + 1
        }
        original(input.length)
        return VoicePronunciationProjection(spoken.toString(), positions)
    }

    private fun boundaryBefore(text: String, index: Int): Boolean {
        if (index == 0) return true
        val previous = text.codePointBefore(index)
        if (termCharacter(previous)) return false
        return text[index - 1] !in ".-" || index < 2 || !termCharacter(text.codePointBefore(index - 1))
    }

    private fun boundaryAfter(text: String, index: Int): Boolean {
        if (index == text.length) return true
        val next = text.codePointAt(index)
        if (termCharacter(next)) return false
        return text[index] !in ".-" || index + 1 == text.length || !termCharacter(text.codePointAt(index + 1))
    }

    private fun termCharacter(value: Int): Boolean =
        Character.isLetterOrDigit(value) ||
            Character.getType(value) in markTypes ||
            value == '\''.code ||
            value == '_'.code ||
            value == '+'.code ||
            value == '#'.code

    private val markTypes = setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())
}
