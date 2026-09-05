package dev.chungjungsoo.gptmobile.data.parser

object ThinkingParser {
    private val THINK_REGEX = Regex("<think>([\\s\\S]*?)</think>", RegexOption.IGNORE_CASE)

    /**
     * Parses the content and extracts <think>...</think> tags.
     * Returns a Pair where:
     * - first: extracted thinking content trimmed, or null if no tag was found
     * - second: message content with the <think>...</think> tags removed and trimmed
     */
    fun parseThinking(content: String): Pair<String?, String> {
        val match = THINK_REGEX.find(content) ?: return Pair(null, content)
        val thinking = match.groupValues[1].trim()
        val cleanContent = content.replace(THINK_REGEX, "").trim()
        return Pair(thinking.ifEmpty { null }, cleanContent)
    }
}
