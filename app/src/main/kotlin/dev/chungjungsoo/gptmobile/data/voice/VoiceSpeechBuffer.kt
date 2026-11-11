package dev.chungjungsoo.gptmobile.data.voice

/** Only stable text is spoken. Repeated snapshots add no queue entries. */
internal class VoiceSpeechBuffer {
    private var previous = ""
    private var consumed = 0
    private var announcedLimit = false
    fun append(text: String, final: Boolean, earlyStart: Boolean = false): List<String>? {
        if (!text.startsWith(previous)) return null
        previous = text
        val end = minOf(text.length, 12000).let { if (it < text.length && it > 0 && text[it - 1].isHighSurrogate()) it - 1 else it }
        val visible = text.substring(0, end)
        val first = if (earlyStart && consumed == 0) {
            val clause = clauseEnd.find(visible)?.range?.last?.plus(1)
            val wordLimit = completeWord.findAll(visible).drop(7).firstOrNull()?.range?.last?.plus(1)
            listOfNotNull(clause, wordLimit).minOrNull()
        } else {
            null
        }
        val stableEnd = if (final) {
            end
        } else {
            maxOf(
                consumed,
                first ?: 0,
                sentenceEnd.findAll(visible).lastOrNull()?.range?.last?.plus(1) ?: 0
            )
        }
        val chunks = buildList {
            val firstEnd = first?.coerceAtMost(stableEnd) ?: consumed
            if (firstEnd > consumed) add(visible.substring(consumed, firstEnd))
            if (stableEnd > firstEnd) addAll(speechChunks(visible.substring(firstEnd, stableEnd)))
        }.filter(String::isNotBlank)
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
