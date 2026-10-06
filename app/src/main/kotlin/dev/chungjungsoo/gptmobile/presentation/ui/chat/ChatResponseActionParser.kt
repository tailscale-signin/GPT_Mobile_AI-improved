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

        return isLastMessage &&
            (
                lastLower.endsWith("continue") ||
                    lastLower.endsWith("continue?") ||
                    lastLower.endsWith("next?") ||
                    lastLower.contains("let me know if you would like me to") ||
                    lastLower.contains("let me know how you would like to proceed")
                )
    }

    /** Short labels are presentation only; tapping a chip sends its complete question. */
    fun extractDynamicActions(text: String, isLoading: Boolean): List<DynamicActionOption> {
        if (isLoading || text.isBlank()) return emptyList()
        val lines = proseLines(text)
        val heading = Regex("""(?i)^(?:#{1,6}\s*)?(?:next questions|suggested questions|follow[- ]?up questions|quick replies|questions to explore)\s*:?\s*$""")
        val offeredQuestions = lines.indices.filter { heading.matches(lines[it].replace("**", "")) }.flatMap { section ->
            lines.drop(section + 1).takeWhile { listItem(it) != null }.mapNotNull { line ->
                listItem(line)?.let { action(it, isOption = false, requireQuestion = true) }
            }
        }
        val tail = lines.takeLast(12).joinToString("\n")
        val offers = extractOffers(tail)
        val structured = extractOfferedList(lines)
        return (offeredQuestions + offers + structured)
            .distinctBy { it.actionPrompt.lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ") }
    }

    private fun proseLines(text: String): List<String> {
        var fence: String? = null
        return text.lines().mapNotNull { raw ->
            val line = raw.trim()
            val marker = Regex("""^(`{3,}|~{3,})""").find(line)?.value
            if (marker != null) {
                if (fence == null) {
                    fence = marker.take(3)
                } else if (marker.startsWith(fence)) {
                    fence = null
                }
                null
            } else {
                line.takeIf { fence == null && it.isNotBlank() && !it.startsWith(">") }
            }
        }
    }

    private fun listItem(line: String): String? = Regex("""^(?:[-*•]\s+|(?:\*\*)?(?:\d+|[A-Za-z])[.):\-]\s*(?:\*\*)?)(.+)$""")
        .matchEntire(line)?.groupValues?.get(1)

    private fun extractOfferedList(lines: List<String>): List<DynamicActionOption> {
        val start = lines.indexOfLast {
            Regex("""(?i)\b(options?|choices|ways to proceed|you can take|would you like|which .*prefer|explore next|suggested questions|next questions|follow[- ]?up questions)\b""").containsMatchIn(it) && listItem(it) == null
        }
        if (start < 0) return emptyList()
        val exclusive = Regex("""(?i)\b(choose|prefer|which|option|choice)""").containsMatchIn(lines[start])
        return lines.drop(start + 1).takeWhile { listItem(it) != null }
            .mapNotNull { listItem(it)?.let { raw -> action(raw, isOption = exclusive) } }
    }

    private fun extractOffers(text: String): List<DynamicActionOption> {
        val binaryOffer = Regex("""(?i)(?:would you like|do you want)\s+(?:me\s+to\s+)?([^?]+)\?\s*\(?yes\s*(?:or|/)\s*no\??\)?""").find(text)
        if (binaryOffer != null) {
            val body = binaryOffer.groupValues[1].trim()
            val request = action(body, isOption = true) ?: return emptyList()
            return listOf(
                request.copy(actionPrompt = "Yes, please $body.", iconType = ActionIconType.CONFIRM),
                DynamicActionOption("No thanks", "No, please don't $body.", true, ActionIconType.CANCEL)
            )
        }
        val questions = Regex("""[^\n.!?]+\?""").findAll(text).map { it.value.trim() }.toList()
        return questions.flatMap { question ->
            val offered = Regex("""(?i)^(?:would you like|do you want)\s+(?:me\s+to\s+|to\s+)?(.+?)\?$|^(?:shall i|should i|can i)\s+(.+?)\?$""").find(question)
            val content = offered?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
                ?: return@flatMap emptyList()
            val binary = Regex("""(?i)\s*\(?yes\s*(?:or|/)\s*no\)?\s*\??$""").replace(content, "").trim()
            if (binary != content) {
                val request = action(binary, isOption = true) ?: return@flatMap emptyList()
                return@flatMap listOf(
                    request.copy(label = request.label, actionPrompt = "Yes, please ${binary.replaceFirstChar { it.lowercase(Locale.ROOT) }}.", iconType = ActionIconType.CONFIRM),
                    DynamicActionOption("No thanks", "No, please don't ${binary.replaceFirstChar { it.lowercase(Locale.ROOT) }}.", true, ActionIconType.CANCEL)
                )
            }
            val alternatives = content.replace(Regex("""(?i)^either\s+"""), "")
                .split(Regex("""(?i),\s*(?:or\s+)?|\s+or\s+"""))
                .map { it.trim().removePrefix("to ") }.filter(String::isNotBlank)
            alternatives.mapNotNull { action(it, isOption = alternatives.size > 1) }
        }
    }

    private fun action(raw: String, isOption: Boolean, requireQuestion: Boolean = false): DynamicActionOption? {
        val heading = Regex("""^\*\*([^*]+)\*\*\s*[:—–-]?\s*(.+)$""").matchEntire(raw.trim())
        val topic = heading?.groupValues?.get(1)?.trim()?.trimEnd(':')
            ?.takeUnless { Regex("""(?i)^option\s+[A-Za-z0-9]+$""").matches(it) }
        val body = (heading?.groupValues?.get(2) ?: raw).trim()
        val cleaned = sanitizeOptionText(body)
        if (cleaned.isBlank() || (requireQuestion && !body.endsWith("?"))) return null
        if (Regex("""(?i)^(?:continue|proceed|more(?: details| information)?|tell me more|learn more|examples|explain further|keep going)$""").matches(cleaned)) return null
        // Do not turn the assistant's questions about unknown user details into invented answers.
        if (Regex("""(?i)^(?:what|which|where|when|how)\b.*\b(?:are you|do you|is your|your (?:name|budget|location|version))\b""").containsMatchIn(cleaned)) return null
        val prompt = if (body.endsWith("?")) cleaned + "?" else cleaned
        return DynamicActionOption(
            label = generateShortLabel(topic ?: cleaned, null),
            actionPrompt = prompt,
            isOption = isOption,
            iconType = classifyIconType(prompt)
        )
    }

    fun sanitizeOptionText(raw: String): String = raw.trim()
        .replace(Regex("""^(?:\*\*)?Option\s+[A-Za-z0-9]+\s*[:.\-]\s*(?:\*\*)?""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\[([^]]+)]\([^)]+\)"""), "$1")
        .replace("**", "").replace("`", "").trim().trimEnd('.', ':', ';', '?', '!').trim()

    /** Keep distinctive topic words, including Unicode, rather than generic sentence openings. */
    fun generateShortLabel(text: String, indexPrefix: String?): String {
        val clean = sanitizeOptionText(text)
        val stopWords = setOf("a", "an", "the", "to", "for", "in", "into", "of", "and", "or", "me", "you", "i", "we", "how", "what", "does", "do", "can", "could", "would", "should", "is", "are", "be", "please", "provide", "tell", "about", "this", "these", "more", "details", "up")
        val words = Regex("""[\p{L}\p{N}]+(?:[-'][\p{L}\p{N}]+)*""").findAll(clean)
            .map { it.value }.filter { it.lowercase(Locale.ROOT) !in stopWords }.toList()
        if (words.isEmpty()) return indexPrefix ?: clean.take(32)
        val selected = (if (words.size <= 4) words else words.take(2) + words.takeLast(2)).toMutableList()
        while (selected.joinToString(" ").length > 32 && selected.size > 2) selected.removeAt(1)
        val label = selected.joinToString(" ") { it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString() } }
        return if (label.length <= 32) label else label.take(31).trimEnd() + "…"
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

    fun buildLocalQuickReplyPrompt(assistantMessage: String): String = """
        Suggest 2 to 4 specific follow-up questions grounded in this assistant response.
        Prioritize questions or actions the assistant explicitly offers. Never invent user preferences or answers to personal questions.
        Return a JSON array of objects with "label" (2-4 topic words) and "question" (the complete, relevant follow-up question).
        Avoid vague labels such as More details, Continue, or Learn more. Keep each label under 32 characters.
        Response: ${assistantMessage.takeLast(4000)}
    """.trimIndent()
}
