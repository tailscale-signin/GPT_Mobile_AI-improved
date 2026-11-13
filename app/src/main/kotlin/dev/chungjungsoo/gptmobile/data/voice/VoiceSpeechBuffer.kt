package dev.chungjungsoo.gptmobile.data.voice

/** Only stable text is spoken. Repeated snapshots add no queue entries. */
internal class VoiceSpeechBuffer {
    private var previous = ""
    private var previousSpeech = ""
    private var consumed = 0
    private var startedSpeech = false
    private var announcedLimit = false
    fun append(text: String, final: Boolean, earlyStart: Boolean = false): List<String>? {
        val end = minOf(text.length, 12000).let { if (it > 0 && text[it - 1].isHighSurrogate()) it - 1 else it }
        val boundedText = text.substring(0, end)
        // Persistence may trim the trailing whitespace of the terminal message.
        // It does not revise any spoken word and must not cancel buffered audio.
        // A change to actual content still invalidates the stream immediately.
        if (!boundedText.startsWith(previous)) {
            if (!final || boundedText.trimEnd() != previous.trimEnd()) return null
        }
        if (!final && boundedText == previous) return emptyList()
        previous = boundedText
        // Beyond the speech cap, only captions grow. Keep a cut-off word/link pending.
        val visible = VoiceSpeechText.project(text, final = final)
        if (!visible.startsWith(previousSpeech)) {
            if (!final || visible.trimEnd() != previousSpeech.trimEnd()) return null
            consumed = consumed.coerceAtMost(visible.length)
        }
        previousSpeech = visible
        val speechEnd = visible.length
        if (earlyStart) {
            val chunks = mutableListOf<String>()
            while (consumed < speechEnd) {
                val tail = visible.substring(consumed)
                val wordLimit = if (startedSpeech) 12 else 4
                val words = completeWord.findAll(tail).take(wordLimit).toList()
                val wordEnd = words.takeIf { it.size == wordLimit }?.last()?.range?.last?.plus(1)
                val clause = clauseEnd.find(tail)?.range?.last?.plus(1)
                val bounded = words.lastOrNull { it.range.last < 240 }?.range?.last?.plus(1)?.takeIf { tail.length >= 240 }
                val boundary = clause?.takeIf { it <= 96 } ?: listOfNotNull(clause, wordEnd, bounded).minOrNull()?.takeIf { it <= 240 } ?: break
                val chunk = tail.substring(0, boundary)
                chunks += chunk
                if (chunk.isNotBlank()) startedSpeech = true
                consumed += boundary
            }
            if (final && consumed < speechEnd) {
                chunks += speechChunks(visible.substring(consumed))
                consumed = speechEnd
            }
            if (final && text.length > 12000 && !announcedLimit) {
                announcedLimit = true
                chunks += "The rest of this response is available in the chat."
            }
            return chunks.filter(String::isNotBlank)
        }
        val stableEnd = if (final) {
            speechEnd
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
