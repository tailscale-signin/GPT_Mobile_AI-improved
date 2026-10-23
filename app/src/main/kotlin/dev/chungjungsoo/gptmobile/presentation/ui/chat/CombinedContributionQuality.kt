package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus

internal data class CombinedContributionAssessment(
    val admitted: Boolean,
    val structuralUsability: Int,
    val reason: String? = null,
    val usablePartial: Boolean = false,
    val relevance: String = "NOT_CHECKED",
    val coherence: String = "NOT_CHECKED",
    val completeness: String = "NOT_CHECKED"
) {
    // Legacy storage compatibility only. This measure must never rank factual credibility.
    val qualityScore: Int get() = structuralUsability
}

/** Keep provider errors and unparsed action payloads out of the final editorial input. */
internal object CombinedContributionUsability {
    private val rawActionMarkup = listOf(
        Regex("(?is)<(?:tool_call|function_call|tool_result|invoke)\\b[^>]*>"),
        Regex("(?im)^\\s*assistant\\s+to=[A-Za-z0-9_.-]+\\s+(?:\\[|\\{)"),
        Regex("(?is)<\\|(?:tool_call|function_call|im_start)\\|>")
    )

    fun assess(status: String?, rawContent: String, allowPartial: Boolean = false, request: String? = null, demo: Boolean = false): CombinedContributionAssessment {
        val content = dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject.withoutMetadata(rawContent).trim()
        val terminalFailure = status in setOf(AgentRunStatus.FAILED, AgentRunStatus.CANCELED, AgentRunStatus.INTERRUPTED)
        if (terminalFailure && (!allowPartial || status == AgentRunStatus.CANCELED)) return CombinedContributionAssessment(false, 0, "terminal_provider_failure")
        if (content.isBlank()) return CombinedContributionAssessment(false, 0, "empty_contribution")
        if (content == "This model reached its response limit. Would you like me to continue?") return CombinedContributionAssessment(false, 0, "application_notice_only")
        if (dev.chungjungsoo.gptmobile.data.agent.DSML_CONTROL_MARKUP.containsMatchIn(content) || rawActionMarkup.any { it.containsMatchIn(content) }) {
            return CombinedContributionAssessment(false, 0, "unresolved_action_markup")
        }
        val usableWords = Regex("[\\p{L}\\p{N}]+").findAll(content).count()
        if (usableWords == 0) return CombinedContributionAssessment(false, 0, "no_substantive_answer")
        val task = request?.let(dev.chungjungsoo.gptmobile.data.agent.CombinedTaskContract::from)
        if (demo && task?.factual == true) return CombinedContributionAssessment(false, 0, "demo_ineligible_for_factual_synthesis", relevance = "UNSUITABLE")
        if (task?.violations(content)?.isNotEmpty() == true) return CombinedContributionAssessment(false, 0, "task_subject_mismatch", relevance = "MISMATCH")
        val sentences = dev.chungjungsoo.gptmobile.data.agent.CombinedContentCoverage.sentences(content)
        val normalized = sentences.map { it.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim() }.filter { it.length > 15 }
        val tokens = Regex("[\\p{L}\\p{N}]+").findAll(content.lowercase()).map { it.value }.toList()
        val repeated = normalized.size >= 4 && normalized.distinct().size * 2 <= normalized.size || tokens.size >= 40 && tokens.distinct().size * 5 < tokens.size
        if (repeated) return CombinedContributionAssessment(false, 0, "repetitive_content", coherence = "REPETITIVE")
        // Reject clear corruption, not dialect, creative style or unfamiliar vocabulary.
        val corrupt = content.count { it == '\uFFFD' || it.isISOControl() && !it.isWhitespace() } * 20 > content.length
        if (corrupt) return CombinedContributionAssessment(false, 0, "corrupt_content", coherence = "CORRUPT")
        if (task?.historicalSurvey == true && usableWords < 4) return CombinedContributionAssessment(false, 0, "no_substantive_answer")
        val score = when {
            content.length >= 500 && usableWords >= 60 -> 100
            content.length >= 160 && usableWords >= 24 -> 85
            content.length >= 48 && usableWords >= 8 -> 70
            else -> 45
        }
        return CombinedContributionAssessment(true, score, "usable_partial_contribution".takeIf { terminalFailure }, terminalFailure, if (task?.subjectConfidence == "EXPLICIT") "ANCHORED" else "UNCERTAIN", "NO_CLEAR_DEFECT_DETECTED", if (terminalFailure) "PARTIAL" else "RESPONSE_RECEIVED_UNVERIFIED")
    }
}

/** Compatibility facade for older call sites; new code names the actual structural measurement. */
internal object CombinedContributionQuality {
    fun sanitizeContribution(raw: String): String {
        val text = dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject.withoutMetadata(dev.chungjungsoo.gptmobile.util.stripAssistantErrorNote(raw))
        val clean = text.lines().filterNot { line ->
            line.trim().let { it == "This model reached its response limit. Would you like me to continue?" || it.startsWith("[PARTIAL_DELEGATE_OUTPUT:") || it.startsWith("[Provider error:") }
        }.joinToString("\n")
        return dev.chungjungsoo.gptmobile.data.agent.sanitizeAssistantText(clean).trim()
    }

    fun currentRequestContribution(currentRequest: String, content: String): String {
        val clean = sanitizeContribution(content)
        val intent = dev.chungjungsoo.gptmobile.data.agent.CurrentRequestContract.from(currentRequest).intent
        if (intent == "SUBSTANTIVE") return clean
        val greeting = Regex("(?i)^(?:(?:hi|hello|hey)(?: there| again)?|welcome(?: back)?|good (?:morning|afternoon|evening)|(?:thank you|thanks)(?: very much)?|you(?:'|’)re welcome)[!,. ]*$|^(?:how|what) can I (?:help|assist)(?: you)?(?: with)?(?: today)?[?!,. ]*$")
        val test = Regex("(?i)^(?:(?:test|testing|message|connection)(?: message| test)? (?:received|acknowledged|successful|works|working|is working|is active)|(?:I(?:'|’)m|I am) (?:here|ready)|ready|working|connected|received|test successful)[!,. ]*$")
        return clean.split(Regex("(?<=[.!?])\\s+|\\n+")).map(String::trim)
            .filter { it.length <= 240 && (greeting.matches(it) || intent == "TEST" && test.matches(it)) }
            .joinToString(" ").take(1200)
    }

    fun assess(status: String?, rawContent: String): CombinedContributionAssessment = CombinedContributionUsability.assess(status, rawContent)
}
