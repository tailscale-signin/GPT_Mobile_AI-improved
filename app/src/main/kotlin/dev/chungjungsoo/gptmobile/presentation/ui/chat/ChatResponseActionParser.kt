package dev.chungjungsoo.gptmobile.presentation.ui.chat

import java.util.Locale

/**
 * Represents a parsed dynamic action or choice extracted from an assistant message.
 */
data class DynamicActionOption(
    val label: String,
    val actionPrompt: String,
    val isOption: Boolean = false,
    val iconType: ActionIconType = ActionIconType.DEFAULT
)

enum class ActionIconType {
    DEFAULT,
    SEARCH,
    SUMMARIZE,
    EXPLAIN,
    OPTION
}

/**
 * Intelligent detector and parser for continue prompts and dynamic action options
 * from AI assistant responses.
 */
object ChatResponseActionParser {

    private val DIRECT_CONTINUATION_PATTERNS = listOf(
        "would you like me to continue",
        "would you like to continue",
        "shall i continue",
        "should i continue",
        "do you want me to continue",
        "do you want me to keep going",
        "let me know if you want me to continue",
        "let me know if you'd like me to continue",
        "let me know if you want me to keep going",
        "let me know if i should continue",
        "would you like to see more",
        "would you like me to keep going",
        "want me to keep going",
        "want me to continue",
        "reply with continue",
        "reply 'continue'",
        "reply \"continue\"",
        "say continue",
        "type continue",
        "reply with 'continue'",
        "reply with \"continue\"",
        "let me know to continue",
        "click continue",
        "press continue",
        "continue to see",
        "ready to continue"
    )

    private val GENERIC_QUESTION_CONTINUE_PATTERNS = listOf(
        "would you like me to proceed",
        "should i proceed",
        "shall i proceed",
        "do you want me to proceed",
        "would you like more details",
        "would you like more information",
        "need more information",
        "shall we move on",
        "ready for the next part",
        "ready for part",
        "ready for step"
    )

    /**
     * Determines whether the Continue button / prompt should appear for this message.
     */
    fun shouldShowContinuePrompt(
        text: String,
        isLoading: Boolean,
        isLastMessage: Boolean = false
    ): Boolean {
        if (isLoading || text.isBlank()) return false
        val trimmed = text.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        // Truncated code block or ellipsis ending
        if (trimmed.endsWith("...") || trimmed.endsWith("…") || (trimmed.count { it == '`' } % 2 != 0)) {
            return true
        }

        // Direct ending words
        if (lower.endsWith("continue?") || lower.endsWith("continue") || lower.endsWith("proceed?") || lower.endsWith("proceed")) {
            return true
        }

        // Check continuation phrase patterns anywhere in text
        if (DIRECT_CONTINUATION_PATTERNS.any { lower.contains(it) }) {
            return true
        }

        // Check if the closing sentence / question prompts for continuation
        val lastLineOrSentence = trimmed.lines().lastOrNull { it.isNotBlank() }?.trim() ?: trimmed
        val lastLower = lastLineOrSentence.lowercase(Locale.ROOT)

        if (GENERIC_QUESTION_CONTINUE_PATTERNS.any { lastLower.contains(it) }) {
            return true
        }

        // Patterns like "Would you like to continue to [something]?" or "Should I continue with...?"
        val regexContinueQuestion = Regex("""(?i)\b(would you like|do you want|shall|should)\s+(me\s+to|i|we\s+to)?\s*(continue|proceed|go on|keep going)""")
        if (regexContinueQuestion.containsMatchIn(lower)) {
            return true
        }

        return isLastMessage && (
            lastLower.endsWith("continue") ||
            lastLower.endsWith("continue?") ||
            lastLower.endsWith("next?") ||
            lastLower.contains("let me know if you would like me to") ||
            lastLower.contains("let me know how you would like to proceed")
        )
    }

    /**
     * Extracts dynamic actionable options/buttons proposed by the assistant in natural language.
     * E.g.
     * - Numbered list: "1. Search online\n2. Summarize key points"
     * - Lettered list: "A) Explain in detail\nB) Show code example"
     * - Inline options: "I can search online, summarize this, or provide examples."
     * - Direct question offer: "Would you like me to search online or summarize this?"
     */
    fun extractDynamicActions(text: String, isLoading: Boolean): List<DynamicActionOption> {
        if (isLoading || text.isBlank()) return emptyList()

        val results = mutableListOf<DynamicActionOption>()

        // 1. Try parsing tail end numbered list (e.g. 1. ... 2. ... 3. ...)
        val numberedOptions = extractNumberedOrLetteredOptions(text)
        if (numberedOptions.isNotEmpty() && numberedOptions.size <= 5) {
            return numberedOptions
        }

        // 2. Try parsing inline offers ("I can X, Y, or Z" or "Would you like me to X or Y?")
        val inlineOptions = extractInlineOffers(text)
        if (inlineOptions.isNotEmpty() && inlineOptions.size <= 5) {
            return inlineOptions
        }

        return results
    }

    private fun extractNumberedOrLetteredOptions(text: String): List<DynamicActionOption> {
        val lines = text.trim().lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        // Look at the tail of the message (up to last 8 lines)
        val tailLines = lines.takeLast(8)

        val numberedRegex = Regex("""^(\d+)[\.\)]\s+(.+)""")
        val letteredRegex = Regex("""^([A-Da-d])[\.\)]\s+(.+)""")

        val numberedMatches = mutableListOf<Pair<String, String>>()
        val letteredMatches = mutableListOf<Pair<String, String>>()

        for (line in tailLines) {
            val numMatch = numberedRegex.matchEntire(line)
            if (numMatch != null) {
                numberedMatches.add(numMatch.groupValues[1] to numMatch.groupValues[2].trim())
                continue
            }
            val letMatch = letteredRegex.matchEntire(line)
            if (letMatch != null) {
                letteredMatches.add(letMatch.groupValues[1] to letMatch.groupValues[2].trim())
            }
        }

        val candidates = if (numberedMatches.size in 2..5) {
            numberedMatches
        } else if (letteredMatches.size in 2..5) {
            letteredMatches
        } else {
            emptyList()
        }

        if (candidates.isEmpty()) return emptyList()

        return candidates.map { (indexStr, rawText) ->
            // Clean markdown bold/italics and trailing punct
            val cleanText = sanitizeOptionText(rawText)
            val label = if (cleanText.length <= 32) cleanText else "$indexStr. ${cleanText.take(28)}..."
            DynamicActionOption(
                label = label,
                actionPrompt = cleanText,
                isOption = true,
                iconType = classifyIconType(cleanText)
            )
        }.distinctBy { it.label.lowercase(Locale.ROOT) }
    }

    private fun extractInlineOffers(text: String): List<DynamicActionOption> {
        val trimmed = text.trim()
        val lastSentence = trimmed.split("\n\n").lastOrNull()?.replace('\n', ' ')?.trim() ?: trimmed

        // Match "I can [action1], [action2], or [action3]"
        val iCanRegex = Regex("""(?i)\bi can\s+([^.?!]+)\?""")
        val wouldYouLikeRegex = Regex("""(?i)\bwould you like (?:me to\s+)?([^?]+)\?""")
        val doYouWantRegex = Regex("""(?i)\bdo you want (?:me to\s+)?([^?]+)\?""")

        val match = wouldYouLikeRegex.find(lastSentence)
            ?: doYouWantRegex.find(lastSentence)
            ?: iCanRegex.find(lastSentence)

        if (match != null) {
            val content = match.groupValues[1].trim()
            val rawOptions = splitAlternatives(content)
            if (rawOptions.size in 2..4) {
                return rawOptions.map { raw ->
                    val clean = sanitizeOptionText(raw)
                    val label = clean.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                    DynamicActionOption(
                        label = label.take(30),
                        actionPrompt = clean,
                        isOption = false,
                        iconType = classifyIconType(clean)
                    )
                }.distinctBy { it.label.lowercase(Locale.ROOT) }
            }
        }

        return emptyList()
    }

    private fun splitAlternatives(raw: String): List<String> {
        // Split by ", or ", " or ", ", "
        val cleaned = raw.replace(Regex("""(?i)\beither\s+"""), "")
        val parts = cleaned.split(Regex("""(?i),\s*(?:or|and)\s*|\s+or\s+|\s*,\s*"""))
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 2 && it.length < 60 }

        return parts
    }

    private fun sanitizeOptionText(raw: String): String {
        return raw.trim()
            .replace(Regex("""^\*+|\*+$"""), "")
            .replace(Regex("""^_+|_+$"""), "")
            .replace(Regex("""^`+|`+$"""), "")
            .replace(Regex("""[\.:;?!]+$"""), "")
            .trim()
    }

    private fun classifyIconType(text: String): ActionIconType {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("search") || lower.contains("find") || lower.contains("look up") || lower.contains("web") -> ActionIconType.SEARCH
            lower.contains("summar") || lower.contains("brief") || lower.contains("key point") -> ActionIconType.SUMMARIZE
            lower.contains("explain") || lower.contains("detail") || lower.contains("elaborate") -> ActionIconType.EXPLAIN
            else -> ActionIconType.DEFAULT
        }
    }
}
