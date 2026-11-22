package dev.chungjungsoo.gptmobile.data.voice

import java.text.Normalizer
import java.util.Locale

internal enum class KokoroPronunciationFailure { UNSUPPORTED_SCRIPT, UNKNOWN_WORD, MISSING_LETTER }

/** Contains counts and finite reasons only; never retains words, phonemes, or the source response. */
internal data class KokoroPronunciationObservation(
    val knownWords: Int,
    val initialisms: Int,
    val unknownWords: Int,
    val spelledLetters: Int,
    val removedFormatCharacters: Int,
    val failure: KokoroPronunciationFailure?,
    val unknownWordLength: Int? = null
)

internal data class KokoroPhrase(val speech: String, val wordRanges: List<IntRange>)

internal data class KokoroSpeechProjection(val phrases: List<KokoroPhrase>?, val observation: KokoroPronunciationObservation) {
    val parts: List<String>? get() = phrases?.map { it.speech }
}

/** Projects English speech and preserves source ranges for dictionary and predicted pronunciations. */
internal object KokoroSpeechText {
    private val marks = Regex("""\p{M}+""")
    private val invisibleFormat = Regex("[\u00AD\u200B\u200C\u200D\u2060\uFEFF]")
    private val token = Regex("""[vV][0-9]+(?:\.[0-9]+)+|[+\-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\.[0-9]+)*|[a-zA-Z]+(?:'[a-zA-Z]+)*|[.,!?;:\n%&#@/+]""")
    private val camelBoundary = Regex("""(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])""")
    private val initialisms = setOf("AI", "API", "APK", "AAR", "CI", "CPU", "GPU", "HTML", "HTTP", "HTTPS", "LLM", "MCP", "PR", "SDK", "SHA", "SQL", "SSH", "TTS", "UI", "URL", "UX", "XML", "VR", "XR", "USB", "DNS", "VPN", "CLI", "IDE", "PDF", "NPU", "ASR", "JVM", "PCM")
    private val spokenAcronyms = mapOf("RAM" to "ram", "JSON" to "jason")
    private val contractions = mapOf("can't" to "cannot", "won't" to "will not", "don't" to "do not", "doesn't" to "does not", "didn't" to "did not", "isn't" to "is not", "aren't" to "are not", "wasn't" to "was not", "weren't" to "were not", "couldn't" to "could not", "wouldn't" to "would not", "shouldn't" to "should not", "i'm" to "i am", "we're" to "we are", "you're" to "you are", "they're" to "they are", "i've" to "i have", "we've" to "we have", "you've" to "you have", "they've" to "they have")
    private val digits = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    private val teens = listOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val scales = listOf("", "thousand", "million", "billion", "trillion")
    private const val MAX_INPUT = 12_000
    private const val MAX_PART_CHARS = 128
    private const val MAX_PART_WORDS = 16
    private const val MAX_POLICY_WORDS = 24

    /** A null result requires a visible pronunciation failure, never silent engine fallback or omission. */
    fun parts(input: String, isKnown: (String) -> Boolean): List<String>? = project(input, markdown = true, isKnown).parts

    /** Stream chunks already contain readable speech; their start is not a new Markdown document. */
    fun projectedParts(input: String, isKnown: (String) -> Boolean): List<String>? = projected(input, isKnown).parts

    fun projected(input: String, isKnown: (String) -> Boolean): KokoroSpeechProjection = project(input, markdown = false, isKnown)

    fun timed(input: String, isKnown: (String) -> Boolean): KokoroSpeechProjection =
        project(input, markdown = false, isKnown, timed = true)

    fun withPolicy(input: String, isKnown: (String) -> Boolean, overrides: Map<String, String>, wordLimit: Int, timed: Boolean): KokoroSpeechProjection =
        project(input, markdown = false, isKnown, timed, overrides, wordLimit.coerceIn(4, MAX_POLICY_WORDS))

    private fun project(input: String, markdown: Boolean, isKnown: (String) -> Boolean, timed: Boolean = false, overrides: Map<String, String> = emptyMap(), wordLimit: Int = MAX_PART_WORDS): KokoroSpeechProjection {
        val clipped = if (input.length <= MAX_INPUT) {
            input
        } else {
            val end = if (input[MAX_INPUT - 1].isHighSurrogate()) MAX_INPUT - 1 else MAX_INPUT
            input.substring(0, end)
        }
        val projected = (if (markdown) VoiceSpeechText.project(input) else clipped) + if (input.length > MAX_INPUT) " The rest of this response is available in the chat." else ""
        val formatCharacters = invisibleFormat.findAll(projected).count()
        val plain = Normalizer.normalize(projected.replace(invisibleFormat, ""), Normalizer.Form.NFKD)
            .replace(marks, "").replace('’', '\'').replace('−', '-')
        val wordRanges = mutableListOf<IntRange>()
        // Offsets are UTF-16 in the displayed speech projection. Record the source of each
        // normalized code unit only when normalization actually changed the string.
        val origins = if (timed && plain != projected) {
            buildList<IntRange> {
                var index = 0
                while (index < projected.length) {
                    val end = index + Character.charCount(projected.codePointAt(index))
                    val source = projected.substring(index, end)
                    val normalized = Normalizer.normalize(source.replace(invisibleFormat, ""), Normalizer.Form.NFKD).replace(marks, "")
                    if (normalized.isEmpty() && marks.matches(source) && isNotEmpty()) {
                        this[lastIndex] = last().first until end
                    } else {
                        repeat(normalized.length) { add(index until end) }
                    }
                    index = end
                }
            }
        } else {
            null
        }
        var sourceRange = IntRange.EMPTY
        var knownWords = 0
        var initialismWords = 0
        var unknownWords = 0
        var spelledLetters = 0
        fun outcome(parts: List<KokoroPhrase>?, failure: KokoroPronunciationFailure? = null, unknownWordLength: Int? = null) = KokoroSpeechProjection(
            parts,
            KokoroPronunciationObservation(knownWords, initialismWords, unknownWords, spelledLetters, formatCharacters, failure, unknownWordLength)
        )
        if (plain.codePoints().anyMatch { it > 127 && Character.isLetterOrDigit(it) }) return outcome(null, KokoroPronunciationFailure.UNSUPPORTED_SCRIPT)
        val result = mutableListOf<KokoroPhrase>()
        val part = StringBuilder()
        var words = 0
        fun flush() {
            if (words > 0) {
                result += KokoroPhrase(part.toString(), wordRanges.toList())
                wordRanges.clear()
            }
            part.clear()
            words = 0
        }
        fun appendWord(word: String) {
            if (words == wordLimit || part.length + word.length + 1 > MAX_PART_CHARS) flush()
            if (part.isNotEmpty()) part.append(' ')
            part.append(word)
            if (timed) wordRanges += sourceRange
            words++
        }
        fun appendSpelling(key: String): Boolean {
            // Limit spelling to one unit's word budget. Check every letter before emitting any.
            if (key.length > MAX_PART_WORDS) return false
            val letters = key.filter(Char::isLetter).map(Char::toString)
            if (!letters.all(isKnown)) return false
            letters.forEach(::appendWord)
            spelledLetters += letters.size
            return true
        }
        for (match in token.findAll(plain)) {
            val value = match.value
            if (value.length == 1 && value[0] in ".,!?;:\n") {
                if (words > 0 && value != "\n" && part.length < MAX_PART_CHARS && part.last().isLetter()) part.append(value)
                if (value[0] in ".!?\n") flush()
                continue
            }
            if (timed) {
                val original = if (origins == null) match.range else origins[match.range.first].first..origins[match.range.last].last
                sourceRange = if (original.last < clipped.length) original else IntRange.EMPTY
            }
            val expanded = overrides[value.lowercase(Locale.ROOT)] ?: when {
                value.any(Char::isDigit) -> spokenNumber(value)
                value == "%" -> "percent"
                value == "#" -> if (plain.getOrNull(match.range.last + 1)?.isDigit() == true) "number" else ""
                value == "&" -> "and"
                value == "@" -> "at"
                value == "/" -> "slash"
                value == "+" -> "plus"
                else -> spokenAcronyms[value] ?: value
            }
            for (word in expanded.split(' ').filter(String::isNotEmpty)) {
                val lower = word.lowercase(Locale.ROOT)
                if (word !in initialisms && isKnown(lower)) {
                    appendWord(lower)
                    knownWords++
                } else if (lower in contractions && contractions.getValue(lower).split(' ').all(isKnown)) {
                    for (partWord in contractions.getValue(lower).split(' ')) {
                        appendWord(partWord)
                        knownWords++
                    }
                } else {
                    for (segment in word.split(camelBoundary)) {
                        val key = spokenAcronyms[segment] ?: segment.lowercase(Locale.ROOT)
                        if (segment !in initialisms && segment != word && isKnown(key)) {
                            appendWord(key)
                            knownWords++
                        } else if (segment in initialisms) {
                            initialismWords++
                            if (!appendSpelling(key)) return outcome(null, KokoroPronunciationFailure.MISSING_LETTER)
                        } else {
                            unknownWords++
                            if (key.length <= 48) {
                                appendWord(key)
                            } else {
                                // "word" is required by the pack health check, even if a letter is missing.
                                if (!isKnown("word")) return outcome(null, KokoroPronunciationFailure.UNKNOWN_WORD, key.length.coerceAtMost(256))
                                if (isKnown("unknown")) appendWord("unknown")
                                appendWord("word")
                            }
                        }
                    }
                }
            }
        }
        flush()
        return outcome(result)
    }

    private fun spokenNumber(value: String): String {
        val number = value.replace(",", "")
        val prefixedVersion = number.startsWith("v", ignoreCase = true)
        val prefix = when (number.first()) {
            '-' -> "minus "
            '+' -> "plus "
            'v', 'V' -> "version "
            else -> ""
        }
        val components = (if (prefix.isEmpty()) number else number.drop(1)).split('.')
        val version = prefixedVersion || components.size > 2
        return prefix + components.mapIndexed { index, component ->
            if (index == 0 || version) wholeNumber(component) else digitWords(component)
        }.joinToString(" point ")
    }

    private fun wholeNumber(value: String): String {
        if (value.length > 15 || value.length > 1 && value.startsWith('0')) return digitWords(value)
        if (value == "0") return digits[0]
        return value.reversed().chunked(3).mapIndexedNotNull { place, reversed ->
            val group = reversed.reversed().toInt()
            if (group == 0) null else listOf(underThousand(group), scales[place]).filter(String::isNotEmpty).joinToString(" ")
        }.asReversed().joinToString(" ")
    }

    private fun underThousand(value: Int): String = buildList {
        if (value >= 100) add(digits[value / 100] + " hundred")
        val remainder = value % 100
        if (remainder in 10..19) {
            add(teens[remainder - 10])
        } else {
            if (remainder >= 20) add(tens[remainder / 10])
            if (remainder % 10 > 0) add(digits[remainder % 10])
        }
    }.joinToString(" ")

    private fun digitWords(value: String) = value.map { digits[it.digitToInt()] }.joinToString(" ")
}
