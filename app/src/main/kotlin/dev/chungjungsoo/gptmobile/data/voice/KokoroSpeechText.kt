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
    private val token = Regex(
        """[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?(?:[ \t]?[aApP]\.?[mM]\.?)?|[$£€][+\-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\.[0-9]+)?|[vV][0-9]+(?:\.[0-9]+)+|[+\-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\.[0-9]+)*|(?:[kKMGT]?[Bb]|[ckm]?m)/[smh](?![a-zA-Z])|[µμ]g(?![a-zA-Z])|°[ \t]?[CF](?![a-zA-Z])|[a-zA-Z]+(?:'[a-zA-Z]+)*|[.,!?;:\n%&#@/+=<>≤≥≠×÷*|°\-–]"""
    )
    private val camelBoundary = Regex("""(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])""")
    private val initialisms = setOf("AI", "API", "APK", "AAR", "CI", "CPU", "GPU", "HTML", "HTTP", "HTTPS", "LLM", "MCP", "PR", "SDK", "SHA", "SQL", "SSH", "TTS", "UI", "URL", "UX", "XML", "VR", "XR", "USB", "DNS", "VPN", "CLI", "IDE", "PDF", "NPU", "ASR", "JVM", "PCM", "IP", "USD", "CAD")
    private val spokenAcronyms = mapOf("RAM" to "ram", "JSON" to "jason")
    private val contractions = mapOf("can't" to "cannot", "won't" to "will not", "don't" to "do not", "doesn't" to "does not", "didn't" to "did not", "isn't" to "is not", "aren't" to "are not", "wasn't" to "was not", "weren't" to "were not", "couldn't" to "could not", "wouldn't" to "would not", "shouldn't" to "should not", "i'm" to "i am", "we're" to "we are", "you're" to "you are", "they're" to "they are", "i've" to "i have", "we've" to "we have", "you've" to "you have", "they've" to "they have")
    private val digits = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    private val teens = listOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val scales = listOf("", "thousand", "million", "billion", "trillion")
    private val monthNames = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    private val precedingMonth = Regex("\\b(?:${monthNames.joinToString("|")})[ \\t]+$", RegexOption.IGNORE_CASE)
    private val precedingNumber = Regex("([+\\-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?)[ \\t]*$")
    private val clockSuffix = Regex("[ \\t]*([ap])\\.?m\\.?$", RegexOption.IGNORE_CASE)
    private val clockContext = Regex("\\b(?:at|by|before|after|around|time)[ \\t]+$", RegexOption.IGNORE_CASE)
    private val precedingClock = Regex("\\b[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?[ \\t]+$")
    private val meridiem = Regex("[ap]m", RegexOption.IGNORE_CASE)
    private val addressContext = Regex("\\b(?:ip|address|gateway|host|server)[ \\t:]+$", RegexOption.IGNORE_CASE)
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
        val plain = Normalizer.normalize(projected.replace(invisibleFormat, ""), Normalizer.Form.NFKD)
            .replace(marks, "").replace('’', '\'').replace('−', '-')
        // Context informs only interpretation. It is never tokenized, emitted or included in
        // current-segment source offsets, and keeps the same normalization as the current text.
        val context = Normalizer.normalize(contextBefore.takeLast(KOKORO_CONTEXT_CHARS).replace(invisibleFormat, ""), Normalizer.Form.NFKD)
            .replace(marks, "").replace('’', '\'').replace('−', '-').takeLast(KOKORO_CONTEXT_CHARS)
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
                val original = if (origins == null) match.range else origins[match.range.first].first..origins[match.range.last].last
                sourceRange = if (original.last < clipped.length) original else IntRange.EMPTY
            }
            val before = if (match.range.first >= KOKORO_CONTEXT_CHARS) {
                plain.substring(match.range.first - KOKORO_CONTEXT_CHARS, match.range.first)
            } else {
                (context + plain.substring(0, match.range.first)).takeLast(KOKORO_CONTEXT_CHARS)
            }
            val unit = spokenUnit(value, before)
            val expanded = overrides[value.lowercase(Locale.ROOT)] ?: when {
                value.length == 10 && value[4] == '-' && value[7] == '-' -> spokenDate(value)
                value.first() in "$£€" -> spokenCurrency(value)
                ':' in value && value.first().isDigit() -> spokenTime(value, before)
                meridiem.matches(value) && precedingClock.containsMatchIn(before) -> "${value.first().lowercaseChar()} m"
                value.any(Char::isDigit) -> if (precedingMonth.containsMatchIn(before) && value.toIntOrNull() in 1..31) {
                    ordinalNumber(value.toInt())
                } else {
                    spokenNumber(value, before)
                }
                unit != null -> unit
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

    private fun spokenNumber(value: String, before: String = ""): String {
        val number = value.replace(",", "")
        val prefixedVersion = number.startsWith("v", ignoreCase = true)
        val prefix = when (number.first()) {
            '-' -> "minus "
            '+' -> "plus "
            'v', 'V' -> "version "
            else -> ""
        }
        val components = (if (prefix.isEmpty()) number else number.drop(1)).split('.')
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

    private fun ordinalNumber(value: Int): String {
        val special = mapOf(1 to "first", 2 to "second", 3 to "third", 5 to "fifth", 8 to "eighth", 9 to "ninth", 12 to "twelfth", 20 to "twentieth", 30 to "thirtieth")
        return special[value] ?: if (value > 20 && value % 10 != 0) {
            tens[value / 10] + " " + ordinalNumber(value % 10)
        } else {
            wholeNumber(value.toString()) + "th"
        }
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
        val singular = number?.replace(",", "")?.toDoubleOrNull()?.let { kotlin.math.abs(it) == 1.0 } == true
        val compact = value.replace(" ", "").replace("\t", "")
        if (compact == "°C" || compact == "°F") return (if (singular) "degree " else "degrees ") + if (compact == "°C") "celsius" else "fahrenheit"
        frequencies[compact]?.let { return it }
        val parts = compact.split('/')
        // Explicit rates/degree/microgram notation is unambiguous even without an amount.
        // Never pass a slash-containing unit as one unknown native pronunciation word.
        if (number == null && parts.size == 1 && compact !in setOf("µg", "μg")) return null
        val unit = units[parts[0]] ?: return null
        val denominator = parts.getOrNull(1)?.let { units[it] ?: return null }
        return unit + (if (singular) "" else "s") + denominator?.let { " per $it" }.orEmpty()
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
