package dev.chungjungsoo.gptmobile.data.voice

import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

private const val KOKORO_CONTEXT_CHARS = 64

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

/** One ordered synthesis stream owns this short-lived context; never share it across replies. */
internal class KokoroSpeechContext {
    private var precedingText = ""

    fun project(input: String, isKnown: (String) -> Boolean, overrides: Map<String, String>, wordLimit: Int, timed: Boolean): KokoroSpeechProjection {
        val projection = KokoroSpeechText.withPolicy(input, isKnown, overrides, wordLimit, timed, contextBefore = precedingText)
        if (projection.phrases != null) {
            precedingText = (precedingText + input.takeLast(KOKORO_CONTEXT_CHARS)).takeLast(KOKORO_CONTEXT_CHARS)
                .let { if (it.firstOrNull()?.isLowSurrogate() == true) it.drop(1) else it }
        }
        return projection
    }
}

/** Projects English speech and preserves source ranges for dictionary and predicted pronunciations. */
internal object KokoroSpeechText {
    private val marks = Regex("""\p{M}+""")
    private val invisibleFormat = Regex("[\u00AD\u200B\u200C\u200D\u2060\uFEFF]")

    // Integer powers must end before decimal/scientific continuation, including during backtracking.
    // Other powers keep the caret separate so the complete numeric token owns the amount.
    private val token = Regex(
        """[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?(?:[ \t]?[aApP]\.?[mM]\.?)?|[$£€][+\-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\.[0-9]+)?|[vV][0-9]+(?:\.[0-9]+)+|(?<![a-zA-Z0-9])[+\-]?(?:[0-9]+(?:\.[0-9]+)?|\.[0-9]+)[eE][+\-]?[0-9]+(?:(?![a-zA-Z0-9]|\.[0-9])|(?=(?:ms|min|[ckm]?m|[µμ]?g|kg|[kKMGT]?[Bb]|[kMG]?Hz|s|h)(?![a-zA-Z0-9])))|(?<![a-zA-Z0-9./])[+\-]?(?:0|[1-9][0-9]*)/(?:0|[1-9][0-9]*)(?![a-zA-Z0-9/]|\.[a-zA-Z0-9])|[0-9]+(?i:st|nd|rd|th)(?![a-zA-Z0-9])|\^[+\-]?[0-9]+(?![0-9eE]|\.[0-9])|[+\-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+|\.[0-9]+)(?:\.[0-9]+)*|(?:[kKMGT]?[Bb]|[ckm]?m)/[smh](?:\^2(?![a-zA-Z0-9]|\.[0-9]))?(?![a-zA-Z])|[ckm]?m\^[23](?![a-zA-Z0-9]|\.[0-9])|[µμ]g(?![a-zA-Z])|°[ \t]?[CF](?![a-zA-Z])|(?<![a-zA-Z0-9])(?:Ms\.|(?i:e\.g\.?|i\.e\.?|etc\.|vs\.|dr\.|mr\.|mrs\.|prof\.|sr\.|jr\.|approx\.))(?![a-zA-Z])|(?<![a-zA-Z0-9])(?:[a-zA-Z]\.)+[a-zA-Z]\.?(?![a-zA-Z0-9])|(?<![a-zA-Z0-9])(?i:c\+\+|[cf]#|\.net)(?![a-zA-Z0-9])|(?<![a-zA-Z0-9])(?:H2O|CO2|O2|N2|NaCl)(?![a-zA-Z0-9])|[a-zA-Z]+(?:'[a-zA-Z]+)*|[.,!?;:\n%&#@/+=<>≤≥≠×÷*|°^\-–]"""
    )
    private val camelBoundary = Regex("""(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])""")
    private val initialisms = setOf("AI", "API", "APK", "AAR", "ABI", "CI", "CPU", "GPU", "HTML", "HTTP", "HTTPS", "LLM", "MCP", "PR", "SDK", "SHA", "SQL", "SSH", "TTS", "UI", "URL", "UX", "XML", "VR", "XR", "USB", "DNS", "VPN", "CLI", "IDE", "PDF", "NPU", "ASR", "JVM", "PCM", "IP", "USD", "CAD", "EUR", "GBP", "GPT", "GLM", "QNN", "ONNX", "JNI", "JDK", "JRE", "GL", "TLS", "SSL", "TCP", "UDP", "RPC", "SSE", "JWT", "BPE", "UTF", "LSTM", "CNN", "RNN", "SSD", "HDD", "DDR", "HDMI", "PCI", "PCIE", "FPS", "RGB", "RGBA", "FP", "NVFP", "GCC", "GDB", "RTX", "GTX")
    private val spokenAcronyms = mapOf("RAM" to "ram", "JSON" to "jason")

    // These keys are technical names even when an answer writes them in lowercase. Ordinary
    // words such as RAM, ARM, US and IT must never become automatic lowercase initialisms.
    private val technicalInitialisms = setOf("ai", "api", "apk", "aar", "abi", "cpu", "gpu", "npu", "html", "http", "https", "llm", "mcp", "sdk", "sql", "ssh", "tts", "url", "xml", "usb", "dns", "vpn", "cli", "ide", "pdf", "asr", "jvm", "pcm", "gpt", "glm", "qnn", "onnx", "jni", "jdk", "jre", "tls", "ssl", "tcp", "udp", "rpc", "sse", "jwt", "bpe", "lstm", "cnn", "rnn", "ssd", "hdd", "ddr", "hdmi", "fps", "rgb", "rgba", "nvfp", "gcc", "gdb", "utf", "rtx", "gtx")
    private val technicalNames = mapOf(
        "github" to "git hub", "gitlab" to "git lab", "chatgpt" to "chat GPT", "openai" to "open AI",
        "opengl" to "open GL", "webgpu" to "web GPU", "typescript" to "type script", "javascript" to "java script",
        "json" to "jason", "c++" to "c plus plus", "c#" to "c sharp", "f#" to "f sharp", ".net" to "dot net", "pcie" to "PCI e"
    )
    private val abbreviations = mapOf(
        "e.g." to "for example", "e.g" to "for example", "i.e." to "that is", "i.e" to "that is", "etc." to "et cetera", "vs." to "versus",
        "dr." to "doctor", "mr." to "mister", "mrs." to "missus", "ms." to "miz", "prof." to "professor",
        "sr." to "senior", "jr." to "junior", "approx." to "approximately"
    )
    private val formulaNames = mapOf("H2O" to "h two o", "CO2" to "c o two", "O2" to "o two", "N2" to "n two", "NaCl" to "sodium chloride")
    private val dottedInitialism = Regex("(?:[a-zA-Z]\\.)+[a-zA-Z]\\.?")
    private val scientificNumber = Regex("[+\\-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)[eE][+\\-]?[0-9]+")
    private val fractionNumber = Regex("[+\\-]?[0-9]+/[0-9]+")
    private val ordinal = Regex("([0-9]+)(st|nd|rd|th)", RegexOption.IGNORE_CASE)
    private val latinLetters = mapOf(
        'Æ' to "AE", 'æ' to "ae", 'Œ' to "OE", 'œ' to "oe", 'Ø' to "O", 'ø' to "o",
        'Ł' to "L", 'ł' to "l", 'Ð' to "D", 'ð' to "d", 'Đ' to "D", 'đ' to "d",
        'Þ' to "Th", 'þ' to "th", 'ß' to "ss", 'ẞ' to "SS", 'ı' to "i",
        'Ŧ' to "T", 'ŧ' to "t", 'Ħ' to "H", 'ħ' to "h", 'Ŋ' to "N", 'ŋ' to "n"
    )
    private val superscripts = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻"
    private val vulgarFractions = "¼½¾⅐⅑⅒⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞"
    private val contractions = mapOf("can't" to "cannot", "won't" to "will not", "don't" to "do not", "doesn't" to "does not", "didn't" to "did not", "isn't" to "is not", "aren't" to "are not", "wasn't" to "was not", "weren't" to "were not", "couldn't" to "could not", "wouldn't" to "would not", "shouldn't" to "should not", "i'm" to "i am", "we're" to "we are", "you're" to "you are", "they're" to "they are", "i've" to "i have", "we've" to "we have", "you've" to "you have", "they've" to "they have")
    private val digits = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    private val teens = listOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val scales = listOf("", "thousand", "million", "billion", "trillion")
    private val monthNames = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    private val precedingMonth = Regex("\\b(?:${monthNames.joinToString("|")})[ \\t]+$", RegexOption.IGNORE_CASE)
    private val precedingNumber = Regex("(?<![a-zA-Z0-9./])([+\\-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+|\\.[0-9]+)(?:\\.[0-9]+)?(?:[eE][+\\-]?[0-9]+|/[0-9]+)?)[ \\t]*$")
    private val clockSuffix = Regex("[ \\t]*([ap])\\.?m\\.?$", RegexOption.IGNORE_CASE)
    private val clockContext = Regex("\\b(?:at|by|before|after|around|time)[ \\t]+$", RegexOption.IGNORE_CASE)
    private val precedingClock = Regex("\\b[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?[ \\t]+$")
    private val meridiem = Regex("[ap]m", RegexOption.IGNORE_CASE)
    private val addressContext = Regex("\\b(?:ip|address|gateway|host|server)[ \\t:]+$", RegexOption.IGNORE_CASE)
    private val technicalNumberContext = Regex("\\b(?:gpt|glm|qwen|utf|sha|rtx|gtx)$", RegexOption.IGNORE_CASE)
    private val versionContext = Regex("\\b(?:version|release|build)[ \\t:]+$", RegexOption.IGNORE_CASE)
    private val units = mapOf(
        "ms" to "millisecond", "s" to "second", "min" to "minute", "h" to "hour",
        "mm" to "millimeter", "cm" to "centimeter", "m" to "meter", "km" to "kilometer",
        "mg" to "milligram", "g" to "gram", "kg" to "kilogram", "µg" to "microgram", "μg" to "microgram",
        "B" to "byte", "kB" to "kilobyte", "KB" to "kilobyte", "MB" to "megabyte", "GB" to "gigabyte", "TB" to "terabyte",
        "b" to "bit", "kb" to "kilobit", "Kb" to "kilobit", "Mb" to "megabit", "Gb" to "gigabit", "Tb" to "terabit"
    )
    private val frequencies = mapOf("Hz" to "hertz", "kHz" to "kilohertz", "MHz" to "megahertz", "GHz" to "gigahertz")
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

    fun withPolicy(input: String, isKnown: (String) -> Boolean, overrides: Map<String, String>, wordLimit: Int, timed: Boolean, contextBefore: String = ""): KokoroSpeechProjection =
        project(input, markdown = false, isKnown, timed, overrides, wordLimit.coerceIn(4, MAX_POLICY_WORDS), contextBefore)

    private fun project(input: String, markdown: Boolean, isKnown: (String) -> Boolean, timed: Boolean = false, overrides: Map<String, String> = emptyMap(), wordLimit: Int = MAX_PART_WORDS, contextBefore: String = ""): KokoroSpeechProjection {
        val clipped = if (input.length <= MAX_INPUT) {
            input
        } else {
            val end = if (input[MAX_INPUT - 1].isHighSurrogate()) MAX_INPUT - 1 else MAX_INPUT
            input.substring(0, end)
        }
        val projected = if (markdown) {
            VoiceSpeechText.project(input)
        } else {
            clipped + if (input.length > MAX_INPUT) " ${VoiceSpeechText.LIMIT_NOTICE}" else ""
        }
        val formatCharacters = invisibleFormat.findAll(projected).count()
        val pronunciation = VoicePronunciationOverrides.project(projected, overrides)
        val normalized = normalizeCharacters(pronunciation.text, pronunciation.sourceCharacters)
        val plain = normalized.first
        // Context informs only interpretation. It is never tokenized, emitted or included in
        // current-segment source offsets, and keeps the same normalization as the current text.
        val context = normalizeCharacters(contextBefore.takeLast(KOKORO_CONTEXT_CHARS)).first.takeLast(KOKORO_CONTEXT_CHARS)
        val wordRanges = mutableListOf<IntRange>()
        // Compose correction and Unicode origins. One displayed character can expand into
        // several spoken characters, but captions must still highlight its original term.
        val origins = normalized.second
        var sourceRange = IntRange.EMPTY
        var knownWords = 0
        var initialismWords = 0
        var unknownWords = 0
        var spelledLetters = 0
        fun outcome(parts: List<KokoroPhrase>?, failure: KokoroPronunciationFailure? = null, unknownWordLength: Int? = null) = KokoroSpeechProjection(
            parts,
            KokoroPronunciationObservation(knownWords, initialismWords, unknownWords, spelledLetters, formatCharacters, failure, unknownWordLength)
        )
        if (hasUnsupportedScript(plain)) return outcome(null, KokoroPronunciationFailure.UNSUPPORTED_SCRIPT)
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
                val original = origins[match.range.first].first..origins[match.range.last].last
                sourceRange = if (original.last < clipped.length) original else IntRange.EMPTY
            }
            val before = if (match.range.first >= KOKORO_CONTEXT_CHARS) {
                plain.substring(match.range.first - KOKORO_CONTEXT_CHARS, match.range.first)
            } else {
                (context + plain.substring(0, match.range.first)).takeLast(KOKORO_CONTEXT_CHARS)
            }
            val unit = spokenUnit(value, before)
            val key = value.lowercase(Locale.ROOT)
            val explicitInitialism = dottedInitialism.matches(value) && key !in abbreviations
            val expanded = when {
                key in abbreviations -> abbreviations.getValue(key)
                explicitInitialism -> value.filter(Char::isLetter).uppercase(Locale.ROOT)
                key in technicalNames -> technicalNames.getValue(key)
                key in technicalInitialisms -> value.uppercase(Locale.ROOT)
                key == "sha" && plain.getOrNull(match.range.last + 1) == '-' && plain.getOrNull(match.range.last + 2)?.isDigit() == true -> "SHA"
                value in formulaNames -> formulaNames.getValue(value)
                value.length == 10 && value[4] == '-' && value[7] == '-' -> spokenDate(value)
                value.first() in "$£€" -> spokenCurrency(value)
                ':' in value && value.first().isDigit() -> spokenTime(value, before)
                meridiem.matches(value) && precedingClock.containsMatchIn(before) -> "${value.first().lowercaseChar()} m"
                scientificNumber.matches(value) -> spokenScientificNumber(value)
                fractionNumber.matches(value) -> spokenFraction(value)
                ordinal.matches(value) -> spokenOrdinal(value)
                value.startsWith('^') && value.length > 1 -> "to the power of ${spokenNumber(value.drop(1))}"
                unit != null -> unit
                value.any(Char::isDigit) -> if (precedingMonth.containsMatchIn(before) && value.toIntOrNull() in 1..31) {
                    ordinalNumber(value.toInt())
                } else {
                    spokenNumber(value, before)
                }
                value == "%" -> "percent"
                value == "#" -> if (plain.getOrNull(match.range.last + 1)?.isDigit() == true) "number" else ""
                value == "&" -> "and"
                value == "@" -> "at"
                value == "/" -> "slash"
                value == "+" -> "plus"
                value == "-" -> if (match.range.first > 0 && plain[match.range.first - 1].isLetter() && plain.getOrNull(match.range.last + 1)?.isLetter() == true) "" else "minus"
                value == "–" -> "to"
                value == "=" -> "equals"
                value == "<" -> "less than"
                value == ">" -> "greater than"
                value == "≤" -> "less than or equal to"
                value == "≥" -> "greater than or equal to"
                value == "≠" -> "does not equal"
                value == "×" || value == "*" -> "times"
                value == "÷" -> "divided by"
                value == "|" -> "vertical bar"
                value == "°" -> "degrees"
                value == "^" -> "to the power of"
                else -> spokenAcronyms[value] ?: value
            }
            for (word in expanded.split(' ').filter(String::isNotEmpty)) {
                val lower = word.lowercase(Locale.ROOT)
                if (word !in initialisms && !explicitInitialism && isKnown(lower)) {
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
                        } else if (segment in initialisms || explicitInitialism) {
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

    private fun normalizeCharacters(text: String, sourceCharacters: List<IntRange> = text.indices.map { it..it }): Pair<String, List<IntRange>> {
        if (text.all { it.code < 128 }) return text to sourceCharacters
        val result = StringBuilder()
        val positions = mutableListOf<IntRange>()
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character.code < 128) {
                result.append(character)
                positions += sourceCharacters[index]
                index++
                continue
            }
            val end = index + Character.charCount(text.codePointAt(index))
            val source = text.substring(index, end)
            val previous = text.getOrNull(index - 1)
            val prefix = when {
                character in superscripts && previous != null && previous !in superscripts && (previous.isLetterOrDigit() || previous == ')') -> {
                    val singleLetter = previous.isLetter() && text.getOrNull(index - 2)?.isLetter() != true
                    val lengthUnit = index >= 2 && text.getOrNull(index - 3)?.isLetter() != true && text.substring(index - 2, index) in setOf("mm", "cm", "km")
                    if (previous.isDigit() || previous == ')' || singleLetter || lengthUnit) "^" else " "
                }
                character in vulgarFractions && previous?.isDigit() == true -> " and "
                else -> ""
            }
            val folded = latinLetters[character] ?: source
            val decomposed = Normalizer.normalize(folded.replace(invisibleFormat, ""), Normalizer.Form.NFKD)
                .replace(marks, "").replace('’', '\'').replace('−', '-').replace('⁄', '/')
            // A titlecase ligature must not manufacture an internal camel-case boundary:
            // Ægir becomes Aegir, while ÆGIR remains AEGIR.
            val titlecase = decomposed.length > 1 && decomposed.all { it in 'A'..'Z' } && text.getOrNull(end)?.isLowerCase() == true
            val normalized = prefix + if (titlecase) decomposed.take(1) + decomposed.drop(1).lowercase(Locale.ROOT) else decomposed
            val original = sourceCharacters[index].first..sourceCharacters[end - 1].last
            if (normalized.isEmpty() && marks.matches(source) && positions.isNotEmpty()) {
                positions[positions.lastIndex] = positions.last().first..original.last
            } else {
                result.append(normalized)
                repeat(normalized.length) { positions += original }
            }
            index = end
        }
        return result.toString() to positions
    }

    private fun spokenScientificNumber(value: String): String {
        val components = value.split('e', 'E')
        return spokenNumber(components[0]) + " times ten to the power of " + spokenNumber(components[1])
    }

    private fun spokenFraction(value: String): String {
        val components = value.split('/')
        val numerator = components[0]
        val denominator = components[1]
        val magnitude = numerator.removePrefix("-").removePrefix("+")
        val denominatorValue = denominator.toIntOrNull()
        if (denominatorValue == null || denominatorValue !in 2..99) {
            return spokenNumber(numerator) + " over " + wholeNumber(denominator)
        }
        val singular = magnitude.toLongOrNull() == 1L
        val unit = when (denominatorValue) {
            2 -> if (singular) "half" else "halves"
            4 -> if (singular) "quarter" else "quarters"
            else -> ordinalNumber(denominatorValue) + if (singular) "" else "s"
        }
        return spokenNumber(numerator) + " " + unit
    }

    private fun spokenOrdinal(value: String): String {
        val match = requireNotNull(ordinal.matchEntire(value))
        val number = match.groupValues[1]
        val suffix = match.groupValues[2].lowercase(Locale.ROOT)
        val lastTwo = number.takeLast(2).toInt()
        val expected = when {
            lastTwo in 11..13 -> "th"
            number.last() == '1' -> "st"
            number.last() == '2' -> "nd"
            number.last() == '3' -> "rd"
            else -> "th"
        }
        // Preserve malformed or oversized labels literally rather than inventing an ordinal.
        if (suffix != expected || number.length > 15) return wholeNumber(number) + " " + suffix.toList().joinToString(" ")
        return ordinalWords(number)
    }

    private fun spokenNumber(value: String, before: String = ""): String {
        val number = value.replace(",", "").let {
            // A registered model/standard separator is part of its label, while x-2,
            // spaced subtraction and leading negative amounts retain the minus sign.
            if (it.startsWith('-') && technicalNumberContext.containsMatchIn(before)) it.drop(1) else it
        }
        val prefixedVersion = number.startsWith("v", ignoreCase = true)
        val prefix = when (number.first()) {
            '-' -> "minus "
            '+' -> "plus "
            'v', 'V' -> "version "
            else -> ""
        }
        val unsigned = if (prefix.isEmpty()) number else number.drop(1)
        val components = (if (unsigned.startsWith('.')) "0$unsigned" else unsigned).split('.')
        val address = !prefixedVersion &&
            prefix.isEmpty() &&
            components.size == 4 &&
            components.all { it.toIntOrNull() in 0..255 } &&
            (addressContext.containsMatchIn(before) || !versionContext.containsMatchIn(before) && components.first() != "0")
        if (address) return components.joinToString(" dot ", transform = ::digitWords)
        val version = prefixedVersion || components.size > 2
        return prefix + components.mapIndexed { index, component ->
            if (index == 0 || version) wholeNumber(component) else digitWords(component)
        }.joinToString(" point ")
    }

    private fun spokenDate(value: String): String {
        val date = runCatching { LocalDate.parse(value) }.getOrNull()
            ?: return value.split('-').joinToString(" dash ", transform = ::wholeNumber)
        return "${monthNames[date.monthValue - 1]} ${ordinalNumber(date.dayOfMonth)} ${wholeNumber(date.year.toString())}"
    }

    private fun ordinalNumber(value: Int): String = ordinalWords(value.toString())

    private fun ordinalWords(value: String): String {
        val cardinal = wholeNumber(value)
        val last = cardinal.substringAfterLast(' ')
        val replacement = when (last) {
            "one" -> "first"
            "two" -> "second"
            "three" -> "third"
            "five" -> "fifth"
            "eight" -> "eighth"
            "nine" -> "ninth"
            "twelve" -> "twelfth"
            else -> if (last.endsWith('y')) last.dropLast(1) + "ieth" else last + "th"
        }
        return cardinal.dropLast(last.length) + replacement
    }

    private fun spokenCurrency(value: String): String {
        val (currency, subunit) = when (value.first()) {
            '£' -> "pound" to "penny"
            '€' -> "euro" to "cent"
            else -> "dollar" to "cent"
        }
        val number = value.drop(1).replace(",", "")
        val sign = when (number.firstOrNull()) {
            '-' -> "minus "
            '+' -> "plus "
            else -> ""
        }
        val unsigned = if (sign.isEmpty()) number else number.drop(1)
        val parts = unsigned.split('.')
        val amount = parts[0]
        val fraction = parts.getOrNull(1)
        if (fraction != null && fraction.length > 2) return sign + spokenNumber(unsigned) + " ${currency}s"
        val whole = wholeNumber(amount) + " " + if (amount.toLongOrNull() == 1L) currency else "${currency}s"
        val cents = fraction?.padEnd(2, '0')?.toIntOrNull() ?: 0
        if (cents == 0) return sign + whole
        val minor = if (cents == 1) {
            subunit
        } else if (subunit == "penny") {
            "pence"
        } else {
            "${subunit}s"
        }
        return sign + whole + " and " + wholeNumber(cents.toString()) + " " + minor
    }

    private fun spokenTime(value: String, before: String): String {
        val suffix = clockSuffix.find(value)
        val components = value.substring(0, suffix?.range?.first ?: value.length).split(':')
        val hour = components[0].toInt()
        val minutes = components[1].toInt()
        val seconds = components.getOrNull(2)?.toInt()
        val valid = minutes < 60 && (seconds == null || seconds < 60) && if (suffix == null) hour < 24 else hour in 1..12
        if (!valid || suffix == null && !clockContext.containsMatchIn(before)) {
            return components.joinToString(" colon ", transform = ::wholeNumber) + suffix?.let { " ${it.groupValues[1].lowercase(Locale.ROOT)} m" }.orEmpty()
        }
        val minuteWords = when {
            minutes == 0 -> "o'clock"
            minutes < 10 -> "oh ${wholeNumber(minutes.toString())}"
            else -> wholeNumber(minutes.toString())
        }
        return wholeNumber(hour.toString()) + " " + minuteWords +
            seconds?.let { " and ${wholeNumber(it.toString())} ${if (it == 1) "second" else "seconds"}" }.orEmpty() +
            suffix?.let { " ${it.groupValues[1].lowercase(Locale.ROOT)} m" }.orEmpty()
    }

    private fun spokenUnit(value: String, before: String): String? {
        val number = precedingNumber.find(before)?.groupValues?.get(1)
        val magnitude = number?.replace(",", "")?.let { amount ->
            if ('/' in amount) {
                val fraction = amount.split('/')
                val numerator = fraction[0].toDoubleOrNull()
                val denominator = fraction[1].toDoubleOrNull()
                if (numerator == null || denominator == null || denominator == 0.0) null else numerator / denominator
            } else {
                amount.toDoubleOrNull()
            }
        }
        val singular = magnitude?.let { kotlin.math.abs(it) == 1.0 } == true
        val compact = value.replace(" ", "").replace("\t", "")
        if (compact == "°C" || compact == "°F") return (if (singular) "degree " else "degrees ") + if (compact == "°C") "celsius" else "fahrenheit"
        frequencies[compact]?.let { return it }
        val parts = compact.split('/')
        // Explicit rates/degree/microgram notation is unambiguous even without an amount.
        // Never pass a slash-containing unit as one unknown native pronunciation word.
        if (number == null && parts.size == 1 && compact !in setOf("µg", "μg") && '^' !in compact) return null
        val power = parts[0].substringAfter('^', "")
        val unit = units[parts[0].substringBefore('^')] ?: return null
        val dimension = when (power) {
            "2" -> "square "
            "3" -> "cubic "
            else -> ""
        }
        val denominator = parts.getOrNull(1)?.let {
            val name = units[it.substringBefore('^')] ?: return null
            name + if (it.endsWith("^2")) " squared" else ""
        }
        return dimension + unit + (if (singular) "" else "s") + denominator?.let { " per $it" }.orEmpty()
    }

    private fun hasUnsupportedScript(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (codePoint > 127 && Character.isLetterOrDigit(codePoint)) {
                val micrograms = codePoint in listOf('µ'.code, 'μ'.code) &&
                    text.getOrNull(index + 1) == 'g' &&
                    text.getOrNull(index - 1)?.isLetter() != true &&
                    text.getOrNull(index + 2)?.isLetter() != true
                if (!micrograms) return true
            }
            index += Character.charCount(codePoint)
        }
        return false
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
