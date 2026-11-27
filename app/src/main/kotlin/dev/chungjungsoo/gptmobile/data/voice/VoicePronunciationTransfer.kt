package dev.chungjungsoo.gptmobile.data.voice

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** A bounded, editable exchange format. Import is staged; only the caller's Save action persists it. */
internal object VoicePronunciationTransfer {
    const val HEADER = "# GPT Mobile pronunciations v1"
    const val MAX_DOCUMENT_BYTES = 512_000

    fun parseEntries(text: String): Map<String, String>? {
        if (text.length > VoicePronunciationOverrides.MAX_EDITOR_CHARS) return null
        val result = linkedMapOf<String, String>()
        val seen = mutableSetOf<String>()
        for (line in text.lineSequence().filter(String::isNotBlank)) {
            if (result.size == VoicePronunciationOverrides.MAX_ENTRIES) return null
            val separator = line.indexOf('=')
            if (separator < 1) return null
            val word = line.substring(0, separator).trim()
            val replacement = line.substring(separator + 1).trim()
            if (!VoicePronunciationOverrides.valid(word, replacement) || !seen.add(VoicePronunciationOverrides.key(word))) return null
            result[word] = replacement
        }
        return result
    }

    fun editor(entries: Map<String, String>): String = entries.entries.joinToString("\n") { "${it.key} = ${it.value}" }

    fun encode(language: String, entries: Map<String, String>): String {
        require(VoicePronunciationLocales.valid(language)) { "Choose a supported pronunciation language." }
        val ordered = entries.entries.sortedBy { VoicePronunciationOverrides.key(it.key) }.associate { it.toPair() }
        val text = editor(ordered)
        require(parseEntries(text) != null) { "Correct the pronunciation entries before exporting." }
        return "$HEADER\n# Language: $language\n$text\n"
    }

    fun decode(text: String, language: String): Map<String, String> {
        require(VoicePronunciationLocales.valid(language)) { "Choose a supported pronunciation language." }
        require(text.length <= VoicePronunciationOverrides.MAX_EDITOR_CHARS + 128) { "The pronunciation file is too large." }
        val lines = text.removePrefix("\uFEFF").lineSequence().iterator()
        require(lines.hasNext() && lines.next() == HEADER) { "Choose a GPT Mobile pronunciation file." }
        require(lines.hasNext() && lines.next() == "# Language: $language") { "This file belongs to another pronunciation language. Select that language before importing." }
        return requireNotNull(parseEntries(lines.asSequence().joinToString("\n"))) { "The file contains invalid, duplicate or excessive pronunciation entries." }
    }

    /** The imported spelling replaces its canonical equivalent, never a second ambiguous key. */
    fun merge(current: Map<String, String>, incoming: Map<String, String>): Map<String, String> {
        require(parseEntries(editor(current)) != null && parseEntries(editor(incoming)) != null) { "Correct the pronunciation entries before importing." }
        val replaced = incoming.keys.mapTo(mutableSetOf(), VoicePronunciationOverrides::key)
        val merged = current.filterKeys { VoicePronunciationOverrides.key(it) !in replaced } + incoming
        require(merged.size <= VoicePronunciationOverrides.MAX_ENTRIES) { "The combined dictionary exceeds ${VoicePronunciationOverrides.MAX_ENTRIES} entries." }
        return merged
    }

    /** Reads no more than the exchange limit; a malformed UTF-8 document cannot create a changed name. */
    fun read(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_DOCUMENT_BYTES + 1 - bytes.size()))
            if (count < 0) break
            require(count > 0) { "The pronunciation file could not be read." }
            bytes.write(buffer, 0, count)
            require(bytes.size() <= MAX_DOCUMENT_BYTES) { "The pronunciation file is too large." }
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }
}
