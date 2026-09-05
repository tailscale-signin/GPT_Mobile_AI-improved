package dev.chungjungsoo.gptmobile.ui.thinking

/**
 * Data class representing parsed content containing optional reasoning thoughts and the final response.
 */
data class ParsedReasoningContent(
    val thinking: String?,
    val content: String,
    val isThinking: Boolean = false
)

/**
 * Utility to parse DeepSeek-R1 style `<think>...</think>` tags from model streaming responses.
 */
object ThinkingParser {
    private val COMPLETE_THINK_REGEX = Regex("(?s)<think>(.*?)</think>")
    private val OPEN_THINK_REGEX = Regex("(?s)<think>(.*)")

    fun parse(rawText: String): ParsedReasoningContent {
        if (!rawText.contains("<think>")) {
            return ParsedReasoningContent(
                thinking = null,
                content = rawText,
                isThinking = false
            )
        }

        val completeMatch = COMPLETE_THINK_REGEX.find(rawText)
        if (completeMatch != null) {
            val thinkingText = completeMatch.groupValues[1].trim()
            val remainingContent = rawText.removeRange(completeMatch.range).trimStart()
            return ParsedReasoningContent(
                thinking = thinkingText.ifEmpty { null },
                content = remainingContent,
                isThinking = false
            )
        }

        val openMatch = OPEN_THINK_REGEX.find(rawText)
        if (openMatch != null) {
            val thinkingText = openMatch.groupValues[1].trim()
            return ParsedReasoningContent(
                thinking = thinkingText.ifEmpty { null },
                content = "",
                isThinking = true
            )
        }

        return ParsedReasoningContent(
            thinking = null,
            content = rawText,
            isThinking = false
        )
    }
}
