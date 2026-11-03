package dev.chungjungsoo.gptmobile.data.voice

/** Only append-only completed sentences are spoken; repeated streaming snapshots add no queue entries. */
internal class VoiceSpeechBuffer {
    private var previous = ""
    private var consumed = 0
    private var announcedLimit = false
    fun append(text: String, final: Boolean): List<String>? {
        if (!text.startsWith(previous)) return null
        previous = text
        val end = minOf(text.length, 12000).let { if (it < text.length && it > 0 && text[it - 1].isHighSurrogate()) it - 1 else it }
        val visible = text.substring(0, end)
        val stableEnd = if (final) end else Regex("[.!?]\\s+").findAll(visible).lastOrNull()?.range?.last?.plus(1) ?: consumed
        val chunks = if (stableEnd > consumed) speechChunks(visible.substring(consumed, stableEnd)).filter(String::isNotBlank) else emptyList()
        consumed = stableEnd
        if (final && text.length > 12000 && !announcedLimit) {
            announcedLimit = true
            return chunks + "The rest of this response is available in the chat."
        }
        return chunks
    }
}
