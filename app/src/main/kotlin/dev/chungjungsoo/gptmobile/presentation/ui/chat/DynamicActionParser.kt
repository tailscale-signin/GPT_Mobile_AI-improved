package dev.chungjungsoo.gptmobile.presentation.ui.chat

import java.util.Locale

/**
 * Type of dynamic action extracted from AI responses.
 */
enum class DynamicActionType {
    CONTINUE,
    OPTION,
    ACTION
}

/**
 * Specification for an action button generated dynamically from AI output.
 *
 * @property label The display text for the chip/button.
 * @property promptText The text sent back to the assistant when this button is clicked.
 * @property actionType The classification of this action.
 * @property isOption Whether this was extracted from an enumerated/bulleted list of options.
 */
data class DynamicActionButton(
    val label: String,
    val promptText: String = label,
    val actionType: DynamicActionType = DynamicActionType.ACTION,
    val isOption: Boolean = false
)

/**
 * Parser for analyzing AI response text and extracting dynamic action options,
 * continuation requests, and numbered/bulleted choices.
 */
object DynamicActionParser {

    private val CONTINUATION_PATTERNS = listOf(
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
        "reply with continue",
        "reply 'continue'",
        "reply \"continue\"",
        "say continue",
        "type continue",
        "would you like me to proceed",
        "shall i proceed",
        "should i proceed",
        "do you want me to proceed",
        "let me know if i should proceed",
        "would you like to proceed",
        "want me to continue",
        "want me to keep going",
        "would you like more details",
        "would you like to know more",
        "should i elaborate",
        "would you like me to elaborate",
        "do you want me to elaborate",
        "need more information",
        "would you like me to go on",
        "shall i keep going"
    )

    private val GENERAL_CONTINUATION_ENDINGS = listOf(
        "continue?",
        "proceed?",
        "keep going?",
        "go on?",
        "more?"
    )

    // Regex to match numbered items like "1. Option description" or "1) Option description"
    private val NUMBERED_OPTION_REGEX = Regex(
        "(?:^|\\n)\\s*([0-9]{1,2})[\\.)]\\s+([A-Za-z0-9_\\-\\s]{2,80})(?=\\n|$|\\.|:)",
        setOf(RegexOption.MULTILINE)
    )

    // Regex to match lettered items like "A. Option description" or "a) Option description"
    private val LETTERED_OPTION_REGEX = Regex(
        "(?:^|\\n)\\s*([A-Da-d])[\\.)]\\s+([A-Za-z0-9_\\-\\s]{2,80})(?=\\n|$|\\.|:)",
        setOf(RegexOption.MULTILINE)
    )

    // Regex for inline offers: "I can search online, summarize this, or provide examples"
    private val INLINE_OFFER_REGEX = Regex(
        "(?:i can|we can|you can choose to|feel free to)\\s+([a-zA-Z0-9_\\-\\s,]+?)(?:\\.|\\?|\\n|$)",
        RegexOption.IGNORE_CASE
    )

    // Regex for direct offers: "Would you like me to [action]?" or "Do you want me to [action]?"
    private val DIRECT_OFFER_REGEX = Regex(
        "(?:would you like me to|do you want me to|should i|can i)\\s+([^?.,;\\n]{3,60})\\?",
        RegexOption.IGNORE_CASE
    )

    /**
     * Determines whether a continue prompt chip/button should be shown.
     */
    fun shouldShowContinue(
        text: String,
        isLoading: Boolean,
        isLastMessage: Boolean = false
    ): Boolean {
        if (isLoading || text.isBlank()) return false
        val trimmed = text.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        if (isLastMessage) return true
        if (trimmed.endsWith("...") || trimmed.endsWith("…")) return true
        if (trimmed.count { it == '`' } % 2 != 0) return true // unclosed code block

        for (pattern in CONTINUATION_PATTERNS) {
            if (lower.contains(pattern)) return true
        }

        for (ending in GENERAL_CONTINUATION_ENDINGS) {
            if (lower.endsWith(ending)) return true
        }

        if (lower.endsWith("continue") || lower.endsWith("proceed")) return true

        return false
    }

    /**
     * Extracts dynamic action buttons and options from the AI response.
     * Guaranteed to return an empty list if loading or text is blank.
     * Maximum number of dynamic action buttons returned is limited (e.g. 5) to prevent clutter.
     */
    fun extractActionButtons(
        text: String,
        isLoading: Boolean = false,
        maxButtons: Int = 5
    ): List<DynamicActionButton> {
        if (isLoading || text.isBlank()) return emptyList()

        val buttons = mutableListOf<DynamicActionButton>()
        val seenLabels = mutableSetOf<String>()

        fun addButton(label: String, prompt: String, type: DynamicActionType, isOption: Boolean) {
            val cleanedLabel = cleanLabel(label)
            if (cleanedLabel.length in 2..35 && seenLabels.add(cleanedLabel.lowercase(Locale.ROOT))) {
                buttons.add(
                    DynamicActionButton(
                        label = cleanedLabel,
                        promptText = prompt.trim(),
                        actionType = type,
                        isOption = isOption
                    )
                )
            }
        }

        // 1. Direct question offers: "Would you like me to search online?" -> "Search online"
        DIRECT_OFFER_REGEX.findAll(text).forEach { match ->
            val action = match.groupValues[1].trim()
            val lowerAction = action.lowercase(Locale.ROOT)
            if (!lowerAction.startsWith("continue") && !lowerAction.startsWith("proceed")) {
                val formattedLabel = formatActionVerbLabel(action)
                addButton(formattedLabel, action, DynamicActionType.ACTION, isOption = false)
            }
        }

        // 2. Numbered options (e.g., "1. Search online\n2. Summarize the text")
        val numberedMatches = NUMBERED_OPTION_REGEX.findAll(text).toList()
        if (numberedMatches.size in 2..6) {
            numberedMatches.forEach { match ->
                val num = match.groupValues[1]
                val desc = match.groupValues[2].trim()
                val label = "$num. ${cleanOptionSnippet(desc)}"
                addButton(label, desc, DynamicActionType.OPTION, isOption = true)
            }
        }

        // 3. Lettered options (e.g., "A. Search online\nB. Summarize the text")
        val letteredMatches = LETTERED_OPTION_REGEX.findAll(text).toList()
        if (letteredMatches.size in 2..6 && buttons.isEmpty()) {
            letteredMatches.forEach { match ->
                val letter = match.groupValues[1].uppercase(Locale.ROOT)
                val desc = match.groupValues[2].trim()
                val label = "$letter. ${cleanOptionSnippet(desc)}"
                addButton(label, desc, DynamicActionType.OPTION, isOption = true)
            }
        }

        // 4. Inline offers if no options yet: "I can search online, summarize this, or provide examples"
        if (buttons.isEmpty()) {
            INLINE_OFFER_REGEX.find(text)?.let { match ->
                val clause = match.groupValues[1]
                val parts = splitConjunctions(clause)
                if (parts.size in 2..4) {
                    parts.forEach { part ->
                        val trimmed = part.trim()
                        if (trimmed.isNotBlank() && trimmed.length in 3..40) {
                            val label = formatActionVerbLabel(trimmed)
                            addButton(label, trimmed, DynamicActionType.ACTION, isOption = false)
                        }
                    }
                }
            }
        }

        return buttons.take(maxButtons)
    }

    private fun cleanLabel(label: String): String {
        return label
            .replace(Regex("[*_`#]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    private fun cleanOptionSnippet(snippet: String): String {
        val firstClause = snippet.split(Regex("[,:;\\n]")).firstOrNull()?.trim() ?: snippet
        return firstClause.take(28).trim()
    }

    private fun formatActionVerbLabel(action: String): String {
        var clean = action
            .replace(Regex("^(also\\s+|either\\s+)", RegexOption.IGNORE_CASE), "")
            .trim()
        return clean.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    private fun splitConjunctions(clause: String): List<String> {
        val commaSplit = clause.split(Regex(",|\\bor\\b|\\band\\b", RegexOption.IGNORE_CASE))
        return commaSplit
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("either", ignoreCase = true) }
    }
}
