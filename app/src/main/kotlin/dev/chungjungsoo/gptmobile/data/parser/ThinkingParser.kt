package dev.chungjungsoo.gptmobile.data.parser

import dev.chungjungsoo.gptmobile.presentation.ui.thinking.ParsedReasoningContent

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

    /**
     * Interoperability helper delegating to [dev.chungjungsoo.gptmobile.presentation.ui.thinking.ThinkingParser.parse].
     */
    fun extractThinking(content: String): ParsedReasoningContent =
        dev.chungjungsoo.gptmobile.presentation.ui.thinking.ThinkingParser.parse(content)
}
