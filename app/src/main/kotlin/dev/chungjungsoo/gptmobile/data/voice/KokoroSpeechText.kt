package dev.chungjungsoo.gptmobile.data.voice

import java.text.Normalizer
import java.util.Locale

/** Sherpa's lexicon drops unknown words. Admit only known words or their spelled-out letters. */
internal object KokoroSpeechText {
    private val marks = Regex("""\p{M}+""")
    private val token = Regex("""[vV][0-9]+(?:\.[0-9]+)+|[+\-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\.[0-9]+)*|[a-zA-Z]+(?:'[a-zA-Z]+)*|[.,!?;:\n%&#@/+]""")
    private val camelBoundary = Regex("""(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])""")
    private val initialisms = setOf("AI", "API", "APK", "AAR", "CI", "CPU", "GPU", "HTML", "HTTP", "HTTPS", "LLM", "MCP", "PR", "RAM", "SDK", "SHA", "SQL", "SSH", "TTS", "UI", "URL", "UX", "XML")
    private val digits = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    private val teens = listOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val scales = listOf("", "thousand", "million", "billion", "trillion")
    private const val MAX_INPUT = 12_000
    private const val MAX_PART_CHARS = 128
    private const val MAX_PART_WORDS = 16

    /** A null result asks the caller to use the installed Android voice for this input. */
    fun parts(input: String, isKnown: (String) -> Boolean): List<String>? = parts(input, project = true, isKnown)

    /** Stream chunks already contain readable speech; their start is not a new Markdown document. */
    fun projectedParts(input: String, isKnown: (String) -> Boolean): List<String>? = parts(input, project = false, isKnown)

    private fun parts(input: String, project: Boolean, isKnown: (String) -> Boolean): List<String>? {
        val clipped = if (input.length <= MAX_INPUT) {
            input
        } else {
            val end = if (input[MAX_INPUT - 1].isHighSurrogate()) MAX_INPUT - 1 else MAX_INPUT
            input.substring(0, end)
        }
        val projected = (if (project) VoiceSpeechText.project(input) else clipped) + if (input.length > MAX_INPUT) " The rest of this response is available in the chat." else ""
        val plain = Normalizer.normalize(projected, Normalizer.Form.NFKD)
            .replace(marks, "").replace('’', '\'').replace('−', '-')
        if (plain.codePoints().anyMatch { it > 127 && Character.isLetterOrDigit(it) }) return null
        val result = mutableListOf<String>()
        val part = StringBuilder()
        var words = 0
        fun flush() {
            if (words > 0) result += part.toString()
            part.clear()
            words = 0
        }
        fun appendWord(word: String) {
            if (words == MAX_PART_WORDS || part.length + word.length + 1 > MAX_PART_CHARS) flush()
            if (part.isNotEmpty()) part.append(' ')
            part.append(word)
            words++
        }
        for (match in token.findAll(plain)) {
            val value = match.value
            if (value.length == 1 && value[0] in ".,!?;:\n") {
                if (words > 0 && value != "\n" && part.length < MAX_PART_CHARS && part.last().isLetter()) part.append(value)
                if (value[0] in ".!?\n") flush()
                continue
            }
            val expanded = when {
                value.any(Char::isDigit) -> spokenNumber(value)
                value == "%" -> "percent"
                value == "#" -> if (plain.getOrNull(match.range.last + 1)?.isDigit() == true) "number" else ""
                value == "&" -> "and"
                value == "@" -> "at"
                value == "/" -> "slash"
                value == "+" -> "plus"
                else -> value
            }
            for (word in expanded.split(' ').filter(String::isNotEmpty)) {
                val lower = word.lowercase(Locale.ROOT)
                if (word !in initialisms && isKnown(lower)) {
                    appendWord(lower)
                } else {
                    for (segment in word.split(camelBoundary)) {
                        val key = segment.lowercase(Locale.ROOT)
                        if (segment !in initialisms && segment != word && isKnown(key)) {
                            appendWord(key)
                        } else {
                            for (letter in key.filter(Char::isLetter)) {
                                if (!isKnown(letter.toString())) return null
                                appendWord(letter.toString())
                            }
                        }
                    }
                }
            }
        }
        flush()
        return result
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
