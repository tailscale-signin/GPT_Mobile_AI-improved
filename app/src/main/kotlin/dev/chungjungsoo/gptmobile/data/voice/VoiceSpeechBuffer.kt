package dev.chungjungsoo.gptmobile.data.voice

/** Only stable text is spoken. Repeated snapshots add no queue entries. */
internal class VoiceSpeechBuffer {
    private var previous = ""
    private var consumed = 0
    private var announcedLimit = false
    fun append(text: String, final: Boolean, earlyStart: Boolean = false): List<String>? {
        // Persistence may trim the trailing whitespace of the terminal message.
        // It does not revise any spoken word and must not cancel buffered audio.
        // A change to actual content still invalidates the stream immediately.
        if (!text.startsWith(previous)) {
            if (!final || text.trimEnd() != previous.trimEnd()) return null
            consumed = consumed.coerceAtMost(text.length)
        }
        previous = text
        val end = minOf(text.length, 12000).let { if (it < text.length && it > 0 && text[it - 1].isHighSurrogate()) it - 1 else it }
        val visible = text.substring(0, end)
        if (earlyStart) {
            val chunks = mutableListOf<String>()
            while (consumed < end) {
                val tail = visible.substring(consumed)
                val wordLimit = if (consumed == 0) 8 else 12
                val words = completeWord.findAll(tail).take(wordLimit).toList()
                val wordEnd = words.takeIf { it.size == wordLimit }?.last()?.range?.last?.plus(1)
                val clause = clauseEnd.find(tail)?.range?.last?.plus(1)
                val bounded = words.lastOrNull { it.range.last < 240 }?.range?.last?.plus(1)?.takeIf { tail.length >= 240 }
                val boundary = listOfNotNull(clause, wordEnd, bounded).minOrNull()?.takeIf { it <= 240 } ?: break
                chunks += tail.substring(0, boundary)
                consumed += boundary
            }
            if (final && consumed < end) {
                chunks += speechChunks(visible.substring(consumed))
                consumed = end
            }
            if (final && text.length > 12000 && !announcedLimit) {
                announcedLimit = true
                chunks += "The rest of this response is available in the chat."
            }
            return chunks.filter(String::isNotBlank)
        }
        val stableEnd = if (final) {
            end
        } else {
            maxOf(
                consumed,
                sentenceEnd.findAll(visible).lastOrNull()?.range?.last?.plus(1) ?: 0
            )
        }
        val chunks = speechChunks(visible.substring(consumed, stableEnd)).filter(String::isNotBlank)
        consumed = stableEnd
        if (final && text.length > 12000 && !announcedLimit) {
            announcedLimit = true
            return chunks + "The rest of this response is available in the chat."
        }
        return chunks
    }

    private companion object {
        val sentenceEnd = Regex("[.!?]\\s+")
        val clauseEnd = Regex("[.!?,;:]\\s+|\\n")
        val completeWord = Regex("\\S+\\s+")
    }
}
