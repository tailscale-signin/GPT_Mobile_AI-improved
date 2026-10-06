package dev.chungjungsoo.gptmobile.data.conversation

/** A first-turn subject is UI metadata; the original response remains available for recovery. */
object ConversationSubject {
    private const val PREFIX = "<!-- chat_subject:"
    private const val MAX_CHARACTERS = 24
    const val INSTRUCTION = "\nFor this conversation's first response, choose its best specific subject in 1 to 4 words, at most 24 characters. " +
        "Put it at the very beginning as <!-- chat_subject: Short subject -->, then answer normally. " +
        "This comment is private UI metadata, not part of the answer. Omit it when the user requires an exact output format. " +
        "Use the actual topic; do not pad it with generic words like Conversation, Request, Discussion, or Details."

    fun extract(response: String): String? {
        val content = response.trimStart()
        if (!content.startsWith(PREFIX)) return null
        val end = content.indexOf("-->", PREFIX.length)
        if (end < 0) return null
        return clean(content.substring(PREFIX.length, end))
    }

    fun withoutMetadata(response: String): String {
        val content = response.trimStart()
        // Suppress the metadata while its leading comment is still streaming.
        if (content.isNotEmpty() && PREFIX.startsWith(content)) return ""
        if (!content.startsWith(PREFIX)) return response
        val end = content.indexOf("-->", PREFIX.length)
        if (end >= 0) return content.substring(end + 3).trimStart()
        // A malformed comment must not hide a completed answer on the following line.
        val nextLine = content.indexOf('\n', PREFIX.length)
        return if (nextLine < 0) "" else content.substring(nextLine + 1).trimStart()
    }

    fun clean(raw: String): String? {
        val words = raw.lineSequence().firstOrNull().orEmpty()
            .trim().removePrefix("Title:").removePrefix("title:")
            .trim(' ', '"', '\'', '`', '*', '#', '“', '”')
            .replace(Regex("[\\p{Cc}]"), " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() }.take(4)
        if (words.isEmpty()) return null
        val title = words.joinToString(" ")
        if (title.codePointCount(0, title.length) <= MAX_CHARACTERS) return title
        val wholeWords = words.wholeWordsWithinLimit()
        if (wholeWords.isNotEmpty()) return wholeWords.joinToString(" ")
        return title.substring(0, title.offsetByCodePoints(0, MAX_CHARACTERS)).trimEnd()
    }

    private fun List<String>.wholeWordsWithinLimit(): List<String> {
        val result = mutableListOf<String>()
        for (word in this) {
            val candidate = (result + word).joinToString(" ")
            if (candidate.codePointCount(0, candidate.length) > MAX_CHARACTERS) break
            result += word
        }
        return result
    }
}
