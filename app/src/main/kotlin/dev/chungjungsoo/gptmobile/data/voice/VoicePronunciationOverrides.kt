package dev.chungjungsoo.gptmobile.data.voice

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

internal data class VoicePronunciationProjection(val text: String, val sourceCharacters: List<IntRange>, val replacements: Set<IntRange> = emptySet()) {
    fun sourceRange(start: Int, end: Int): IntRange? =
        if (start in 0 until end && end <= sourceCharacters.size) sourceCharacters[start].first..sourceCharacters[end - 1].last else null
}

/** Indexed once per settings change. Each speech operation keeps its own immutable dictionary. */
internal class VoicePronunciationDictionary @JvmOverloads constructor(overrides: Map<String, String>, languageTag: String = "en") {
    private val language = Locale.forLanguageTag(languageTag).language
    private val readings = LocalizedVoiceReadings.forLanguage(language)
    private val personal = VoicePronunciationOverrides.clean(overrides)
    private fun index(entries: Map<String, String>) = entries.entries.sortedByDescending { it.key.length }.groupBy { it.key.first() }
    private val entries = readings + personal
    private val personalIndex = index(entries)
    private val continuations = buildList {
        for (term in entries.keys) {
            for (split in term.indices.filter { term[it] == ' ' }) add(term.substring(0, split) to term.substring(split + 1))
        }
    }.groupBy({ it.first }, { it.second })

    fun permitsSplit(text: String, end: Int, final: Boolean): Boolean {
        if (continuations.isEmpty() || end <= 0 || !text[end - 1].isWhitespace()) return true
        var left = end
        while (left > 0 && text[left - 1].isWhitespace()) left--
        val right = VoicePronunciationOverrides.key(text.substring(end, minOf(end + 96, text.length)).trimStart())
        val floor = (left - 96).coerceAtLeast(0)
        var start = left
        while (start > floor) {
            start--
            if (start > 0 && !text[start - 1].isWhitespace() && text[start - 1].isLetterOrDigit()) continue
            val suffixes = continuations[VoicePronunciationOverrides.key(text.substring(start, left))] ?: continue
            if (suffixes.any { right.startsWith(it) || !final && it.startsWith(right) }) return false
        }
        return true
    }

    // Alternative English text engines cannot consume Kokoro phoneme IDs. Share only
    // the unambiguous text readings, with saved whole-term corrections taking precedence.
    private val englishIndex by lazy { index(KokoroEnglishReadings.englishTextReadings + personal) }

    fun project(input: String, englishFallback: Boolean = false): VoicePronunciationProjection =
        VoicePronunciationOverrides.projectIndexed(input, if (englishFallback && language == "en") englishIndex else personalIndex, language)

    companion object {
        val EMPTY = VoicePronunciationDictionary(emptyMap())
    }
}

/** Whole words or phrases mapped to a spoken spelling in the selected language. */
internal object VoicePronunciationOverrides {
    private val word = Regex("\\.?[\\p{L}][\\p{L}\\p{M}\\p{N}'’._+#-]*")
    private val term = Regex("""\.?[\p{L}][\p{L}\p{M}\p{N}'’._+#-]*(?: \.?[\p{L}][\p{L}\p{M}\p{N}'’._+#-]*){0,5}""")
    private val spoken = Regex("""[\p{L}][\p{L}\p{M}]*(?:['’\- ][\p{L}][\p{L}\p{M}]*)*""")

    fun valid(original: String, pronunciation: String): Boolean =
        original.length in 1..48 &&
            term.matches(original) &&
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

    /** Preserve only whole literal identifiers whose default rendering would otherwise destroy a saved key. */
    fun literalTermAt(input: String, index: Int): IntRange? {
        if (!boundaryBefore(input, index)) return null
        val match = word.matchAt(input, index) ?: return null
        return match.range.takeIf {
            match.value.any { character -> character == '_' || character == '#' } && boundaryAfter(input, it.last + 1)
        }
    }

    /**
     * Apply literal whole-term corrections before any speech-engine tokenizer changes the text.
     * Substitutions are one pass; unmatched identifiers then receive their default rendering.
     * Every spoken character points to its original display character or corrected term.
     * No correction, matched term or generated pronunciation is logged or retained globally.
     */
    fun project(input: String, overrides: Map<String, String>): VoicePronunciationProjection = VoicePronunciationDictionary(overrides).project(input)

    internal fun projectIndexed(input: String, candidates: Map<Char, List<Map.Entry<String, String>>>, language: String): VoicePronunciationProjection {
        if (candidates.isEmpty() && '_' !in input && '#' !in input) return VoicePronunciationProjection(input, input.indices.map { it..it })
        // ASCII case folding preserves UTF-16 offsets. Unicode terms need per-grapheme
        // origins because a canonical key may occupy a different number of characters.
        val originalRanges = mutableListOf<IntRange>()
        val normalized = if (input.all { it.code < 128 }) {
            input.lowercase(Locale.ROOT)
        } else {
            val characters = BreakIterator.getCharacterInstance(Locale.ROOT)
            characters.setText(input)
            buildString {
                var start = characters.first()
                var end = characters.next()
                while (end != BreakIterator.DONE) {
                    val folded = key(input.substring(start, end))
                    append(folded)
                    repeat(folded.length) { originalRanges += start until end }
                    start = end
                    end = characters.next()
                }
            }
        }
        val replacements = mutableSetOf<IntRange>()
        val spoken = StringBuilder()
        val positions = mutableListOf<IntRange>()
        var cursor = 0
        fun original(until: Int) {
            while (cursor < until) {
                when {
                    input[cursor] == '_' -> {
                        val start = cursor
                        while (cursor < until && input[cursor] == '_') cursor++
                        if (input.getOrNull(start - 1)?.isLetterOrDigit() == true && input.getOrNull(cursor)?.isLetterOrDigit() == true) {
                            spoken.append(' ')
                            positions += start until cursor
                        }
                    }
                    input[cursor] == '#' && input.getOrNull(cursor + 1)?.isDigit() == true -> {
                        val rendering = LocalizedVoiceReadings.numberSign(language)
                        spoken.append(rendering)
                        repeat(rendering.length) { positions += cursor..cursor }
                        cursor++
                    }
                    else -> {
                        spoken.append(input[cursor])
                        positions += cursor..cursor
                        cursor++
                    }
                }
            }
        }
        var index = 0
        while (index < normalized.length) {
            val unspaced = language == "zh" || language == "ja"
            val replacement = if (boundaryBefore(normalized, index) || language == "fr" && afterFrenchElision(normalized, index) || unspaced && cjkBoundary(normalized, index)) {
                candidates[normalized[index]]?.firstOrNull { entry ->
                    val end = index + entry.key.length
                    normalized.startsWith(entry.key, index) && (boundaryAfter(normalized, end) || unspaced && cjkBoundary(normalized, end))
                }
            } else {
                null
            }
            if (replacement == null) {
                index++
                continue
            }
            val last = index + replacement.key.length - 1
            val source = if (originalRanges.isEmpty()) index..last else originalRanges[index].first..originalRanges[last].last
            original(source.first)
            replacements += source
            spoken.append(replacement.value)
            repeat(replacement.value.length) { positions += source }
            cursor = source.last + 1
            index = last + 1
        }
        original(input.length)
        return VoicePronunciationProjection(spoken.toString(), positions, replacements)
    }

    private fun cjkBoundary(text: String, index: Int): Boolean {
        if (index == 0 || index == text.length) return true
        fun unspaced(codePoint: Int): Boolean = Character.isIdeographic(codePoint) || codePoint in 0x3040..0x30ff
        return unspaced(text.codePointBefore(index)) || unspaced(text.codePointAt(index))
    }

    private val frenchElisions = setOf("c", "d", "j", "l", "m", "n", "s", "t", "qu", "jusqu", "lorsqu", "puisqu")
    private fun afterFrenchElision(text: String, index: Int): Boolean {
        if (index < 2 || text[index - 1] != '\'') return false
        var start = index - 2
        while (start > 0 && text[start - 1].isLetter()) start--
        return text.substring(start, index - 1) in frenchElisions
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

/** English readings shared with the vocabulary-checked native Kokoro frontend. */
internal object KokoroEnglishReadings {
    val initialisms = setOf("AI", "API", "APK", "AAR", "ABI", "CI", "CPU", "GPU", "HTML", "HTTP", "HTTPS", "LLM", "MCP", "PR", "SDK", "SHA", "SQL", "SSH", "TTS", "UI", "URL", "UX", "XML", "VR", "XR", "USB", "DNS", "VPN", "CLI", "IDE", "PDF", "NPU", "ASR", "JVM", "PCM", "IP", "USD", "CAD", "EUR", "GBP", "GPT", "GLM", "QNN", "ONNX", "JNI", "JDK", "JRE", "GL", "TLS", "SSL", "TCP", "UDP", "RPC", "SSE", "JWT", "BPE", "UTF", "LSTM", "CNN", "RNN", "SSD", "HDD", "DDR", "HDMI", "PCI", "PCIE", "FPS", "RGB", "RGBA", "FP", "NVFP", "GCC", "GDB", "RTX", "GTX")

    // These keys are technical names even when an answer writes them in lowercase. Ordinary
    // words such as RAM, ARM, US and IT must never become automatic lowercase initialisms.
    val technicalInitialisms = setOf("ai", "api", "apk", "aar", "abi", "cpu", "gpu", "npu", "html", "http", "https", "llm", "mcp", "sdk", "sql", "ssh", "tts", "url", "xml", "usb", "dns", "vpn", "cli", "ide", "pdf", "asr", "jvm", "pcm", "gpt", "glm", "qnn", "onnx", "jni", "jdk", "jre", "tls", "ssl", "tcp", "udp", "rpc", "sse", "jwt", "bpe", "lstm", "cnn", "rnn", "ssd", "hdd", "ddr", "hdmi", "fps", "rgb", "rgba", "nvfp", "gcc", "gdb", "utf", "rtx", "gtx")
    val technicalNames = mapOf(
        "github" to "git hub", "gitlab" to "git lab", "chatgpt" to "chat GPT", "openai" to "open AI",
        "opengl" to "open GL", "webgpu" to "web GPU", "typescript" to "type script", "javascript" to "java script",
        "json" to "jason", "c++" to "c plus plus", "c#" to "c sharp", "f#" to "f sharp", ".net" to "dot net", "pcie" to "PCI e"
    )
    val abbreviations = mapOf(
        "e.g." to "for example", "e.g" to "for example", "i.e." to "that is", "i.e" to "that is", "etc." to "et cetera", "vs." to "versus",
        "dr." to "doctor", "mr." to "mister", "mrs." to "missus", "ms." to "miz", "prof." to "professor",
        "sr." to "senior", "jr." to "junior", "approx." to "approximately"
    )
    val englishTextReadings: Map<String, String> by lazy {
        fun spokenInitialisms(text: String) = text.split(' ').joinToString(" ") { word ->
            if (word in initialisms) word.toList().joinToString(" ") else word
        }
        abbreviations + technicalInitialisms.associateWith { spokenInitialisms(it.uppercase(Locale.ROOT)) } +
            technicalNames.mapValues { (_, text) -> spokenInitialisms(text) }
    }
}
