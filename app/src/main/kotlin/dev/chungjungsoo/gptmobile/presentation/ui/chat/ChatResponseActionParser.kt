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
    OPTION,
    CONFIRM,
    CANCEL
}

/**
 * Intelligent detector and parser for continue prompts and dynamic action options
 * from AI assistant responses.
 *
 * Extracts all options, questions, and alternatives offered by the prompt,
 * transforming them into clean, concise, short button labels suitable for mobile UI chips.
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
     * Generates clean, concise short button labels (1 to 4 words) and full action prompts.
     *
     * Supports:
     * 1. Numbered lists: "1. Search online", "1) Option", "1 - Option", "**1.** Option"
     * 2. Lettered lists: "A. Option", "A) Option", "**A:** Option"
     * 3. Bulleted option lists: "- Option 1", "* Option 2", "• Option 3"
     * 4. Bold / Labelled options: "**Option 1:** Details", "Option A: Details"
     * 5. Inline questions & choices: "Would you like to A, B, or C?", "Do you want to X or Y?"
     * 6. Binary / Direct choices: "Yes or No?", "Accept or Decline?"
     */
    fun extractDynamicActions(text: String, isLoading: Boolean): List<DynamicActionOption> {
        if (isLoading || text.isBlank()) return emptyList()

        // 1. Try extracting structured lists (numbered, lettered, bulleted, bold options)
        val structuredOptions = extractStructuredListOptions(text)
        if (structuredOptions.isNotEmpty() && structuredOptions.size in 2..6) {
            return structuredOptions
        }

        // 2. Try extracting inline question choices or alternative suggestions
        val inlineOptions = extractInlineOffers(text)
        if (inlineOptions.isNotEmpty() && inlineOptions.size in 2..6) {
            return inlineOptions
        }

        // 3. Try binary question choices (e.g. Yes / No, Agree / Disagree)
        val binaryOptions = extractBinaryChoices(text)
        if (binaryOptions.isNotEmpty()) {
            return binaryOptions
        }

        return emptyList()
    }

    private fun extractStructuredListOptions(text: String): List<DynamicActionOption> {
        val lines = text.trim().lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        // Look at the tail portion of the message (up to last 12 lines)
        val tailLines = lines.takeLast(12)

        val numberedRegex = Regex("""^(?:\*\*|\b)?(\d+)[\.\)\:\-]\s*(?:\*\*)?\s*(.+)""")
        val letteredRegex = Regex("""^(?:\*\*|\b)?([A-Da-d])[\.\)\:\-]\s*(?:\*\*)?\s*(.+)""")
        val bulletRegex = Regex("""^[-*•]\s+(?:\*\*)?(?:Option\s+[A-Za-z0-9]+:\s*)?(.+)""")
        val optionHeaderRegex = Regex("""^(?:\*\*)?Option\s+([A-Za-z0-9]+)[\:\-\.]\s*(?:\*\*)?\s*(.+)""", RegexOption.IGNORE_CASE)

        val numberedMatches = mutableListOf<Pair<String, String>>()
        val letteredMatches = mutableListOf<Pair<String, String>>()
        val bulletMatches = mutableListOf<String>()

        for (line in tailLines) {
            val optMatch = optionHeaderRegex.matchEntire(line)
            if (optMatch != null) {
                letteredMatches.add(optMatch.groupValues[1] to optMatch.groupValues[2].trim())
                continue
            }
            val numMatch = numberedRegex.matchEntire(line)
            if (numMatch != null) {
                numberedMatches.add(numMatch.groupValues[1] to numMatch.groupValues[2].trim())
                continue
            }
            val letMatch = letteredRegex.matchEntire(line)
            if (letMatch != null) {
                letteredMatches.add(letMatch.groupValues[1] to letMatch.groupValues[2].trim())
                continue
            }
            val bulMatch = bulletRegex.matchEntire(line)
            if (bulMatch != null) {
                bulletMatches.add(bulMatch.groupValues[1].trim())
            }
        }

        val candidates: List<Pair<String?, String>> = when {
            numberedMatches.size in 2..6 -> numberedMatches.map { it.first to it.second }
            letteredMatches.size in 2..6 -> letteredMatches.map { it.first to it.second }
            bulletMatches.size in 2..6 -> bulletMatches.map { null to it }
            else -> emptyList()
        }

        if (candidates.isEmpty()) return emptyList()

        return candidates.mapNotNull { (prefix, rawText) ->
            val cleanText = sanitizeOptionText(rawText)
            if (cleanText.isBlank()) return@mapNotNull null

            val shortLabel = generateShortLabel(cleanText, prefix)
            DynamicActionOption(
                label = shortLabel,
                actionPrompt = cleanText,
                isOption = true,
                iconType = classifyIconType(cleanText)
            )
        }.distinctBy { it.label.lowercase(Locale.ROOT) }
    }

    private fun extractInlineOffers(text: String): List<DynamicActionOption> {
        val trimmed = text.trim()
        val sentences = trimmed.split(Regex("""(?<=[.?!])\s+|\n\n+"""))
        val candidateSentence = sentences.takeLast(2).firstOrNull { it.contains("?") || it.contains(":") } ?: sentences.lastOrNull() ?: trimmed
        val cleanCandidate = candidateSentence.replace('\n', ' ').trim()

        val patterns = listOf(
            Regex("""(?i)\b(?:would you like|do you want|shall we|should we|can i|i can)\s+(?:me to\s+)?([^?]+)\?"""),
            Regex("""(?i)\b(?:options|choices)(?:\s+are|\s+include)?:\s*([^.?!]+)"""),
            Regex("""(?i)\bwhich(?:\s+one)?\s+(?:do you|would you)\s+prefer[:\s]+([^?]+)\?"""),
            Regex("""(?i)\bprefer[:\s]+([^?]+)\?""")
        )

        for (pattern in patterns) {
            val match = pattern.find(cleanCandidate)
            if (match != null) {
                val content = match.groupValues[1].trim()
                val rawOptions = splitAlternatives(content)
                if (rawOptions.size in 2..5) {
                    return rawOptions.mapNotNull { raw ->
                        val clean = sanitizeOptionText(raw)
                        if (clean.isBlank()) return@mapNotNull null
                        val shortLabel = generateShortLabel(clean, null)
                        DynamicActionOption(
                            label = shortLabel,
                            actionPrompt = clean,
                            isOption = false,
                            iconType = classifyIconType(clean)
                        )
                    }.distinctBy { it.label.lowercase(Locale.ROOT) }
                }
            }
        }

        return emptyList()
    }

    private fun extractBinaryChoices(text: String): List<DynamicActionOption> {
        val trimmed = text.trim()
        val lastSentence = trimmed.lines().lastOrNull { it.isNotBlank() }?.trim() ?: trimmed
        val lower = lastSentence.lowercase(Locale.ROOT)

        if (lower.contains("yes or no") || lower.endsWith("yes/no?") || lower.endsWith("(y/n)?")) {
            return listOf(
                DynamicActionOption("Yes", "Yes", isOption = true, iconType = ActionIconType.CONFIRM),
                DynamicActionOption("No", "No", isOption = true, iconType = ActionIconType.CANCEL)
            )
        }

        if (lower.contains("agree or disagree") || lower.contains("accept or decline")) {
            return listOf(
                DynamicActionOption("Accept", "Accept", isOption = true, iconType = ActionIconType.CONFIRM),
                DynamicActionOption("Decline", "Decline", isOption = true, iconType = ActionIconType.CANCEL)
            )
        }

        return emptyList()
    }

    private fun splitAlternatives(raw: String): List<String> {
        val cleaned = raw
            .replace(Regex("""(?i)\beither\s+"""), "")
            .replace(Regex("""(?i)\bto\s+"""), "")
        val parts = cleaned.split(Regex("""(?i),\s*(?:or|and)\s*|\s+or\s+|\s*,\s*"""))
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 1 && it.length < 80 }

        return parts
    }

    /**
     * Cleans raw text into readable text by stripping leading/trailing symbols, markdown syntax,
     * bullet icons, and redundant colon descriptions.
     */
    fun sanitizeOptionText(raw: String): String {
        var text = raw.trim()
            .replace(Regex("""^(\*+|_+|`+)"""), "")
            .replace(Regex("""(\*+|_+|`+)$"""), "")
            .replace(Regex("""[\.:;?!]+$"""), "")
            .trim()

        // Strip leading prefixes like "**Step 1:** ", "1. ", "Option 1: "
        text = text.replace(Regex("""^(?:Option\s+[A-Za-z0-9]+|Step\s+\d+|Part\s+\d+)[\:\-]\s*""", RegexOption.IGNORE_CASE), "")
        return text.trim()
    }

    /**
     * Produces a clean, concise, short label (1 to 4 words, max ~24 chars) suitable for a button pill.
     * E.g.: "Search online for official docs" -> "Search Online"
     * "1. Explain the architectural tradeoffs" -> "Explain Architecture"
     */
    fun generateShortLabel(text: String, indexPrefix: String?): String {
        val clean = sanitizeOptionText(text)
        // If bold heading exists (e.g. "**Search Online**: details"), use the heading
        val headingMatch = Regex("""^([A-Za-z0-9\s\-/]+)[\:\-]\s+""").find(clean)
        if (headingMatch != null) {
            val heading = headingMatch.groupValues[1].trim()
            if (heading.isNotBlank() && heading.length <= 22) {
                return heading.split(" ")
                    .take(3)
                    .joinToString(" ") { it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.ROOT) else c.toString() } }
            }
        }

        // Take the first 1-3 prominent words
        val words = clean.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.isEmpty()) return indexPrefix ?: "Option"

        val filteredWords = mutableListOf<String>()
        val stopWords = setOf("a", "an", "the", "to", "for", "in", "of", "and", "or", "me", "you", "i", "we")

        for (w in words) {
            val cleanWord = w.replace(Regex("""[^A-Za-z0-9\-]"""), "")
            if (cleanWord.isBlank()) continue
            if (filteredWords.isEmpty() && cleanWord.lowercase(Locale.ROOT) in stopWords) continue
            filteredWords.add(cleanWord.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() })
            if (filteredWords.size >= 3) break
        }

        val candidate = filteredWords.joinToString(" ")
        return if (candidate.isNotBlank() && candidate.length <= 24) {
            candidate
        } else if (clean.length <= 24) {
            clean
        } else {
            val truncated = clean.take(20).trimEnd()
            "$truncated..."
        }
    }

    private fun classifyIconType(text: String): ActionIconType {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("search") || lower.contains("find") || lower.contains("look up") || lower.contains("web") || lower.contains("browse") -> ActionIconType.SEARCH
            lower.contains("summar") || lower.contains("brief") || lower.contains("key point") || lower.contains("recap") -> ActionIconType.SUMMARIZE
            lower.contains("explain") || lower.contains("detail") || lower.contains("elaborate") || lower.contains("why") || lower.contains("how") -> ActionIconType.EXPLAIN
            lower.contains("yes") || lower.contains("agree") || lower.contains("accept") || lower.contains("confirm") -> ActionIconType.CONFIRM
            lower.contains("no") || lower.contains("cancel") || lower.contains("decline") -> ActionIconType.CANCEL
            else -> ActionIconType.OPTION
        }
    }

    /**
     * Formats a high-efficiency on-device prompt for Local QNN / LiteRT to generate
     * 2-4 clean, short response options for an assistant message when required.
     */
    fun buildLocalQuickReplyPrompt(assistantMessage: String): String {
        return """
            Extract or suggest 2 to 4 very short user replies (1-3 words each) for this assistant message.
            Format output strictly as a comma-separated list of short replies.
            Message: "${assistantMessage.takeLast(250).replace('"', '\'')}"
            Replies:
        """.trimIndent()
    }
}
