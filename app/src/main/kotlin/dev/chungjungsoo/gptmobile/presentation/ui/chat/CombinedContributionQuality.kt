package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus

internal data class CombinedContributionAssessment(
    val admitted: Boolean,
    val structuralUsability: Int,
    val reason: String? = null,
    val usablePartial: Boolean = false
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

    fun assess(status: String?, rawContent: String, allowPartial: Boolean = false): CombinedContributionAssessment {
        val content = rawContent.trim()
        val terminalFailure = status in setOf(AgentRunStatus.FAILED, AgentRunStatus.CANCELED, AgentRunStatus.INTERRUPTED)
        if (terminalFailure && (!allowPartial || status == AgentRunStatus.CANCELED)) return CombinedContributionAssessment(false, 0, "terminal_provider_failure")
        if (content.isBlank()) return CombinedContributionAssessment(false, 0, "empty_contribution")
        if (content == "This model reached its response limit. Would you like me to continue?") return CombinedContributionAssessment(false, 0, "application_notice_only")
        if (rawActionMarkup.any { it.containsMatchIn(content) }) {
            return CombinedContributionAssessment(false, 0, "unresolved_action_markup")
        }
        val usableWords = Regex("[\\p{L}\\p{N}]+").findAll(content).count()
        val score = when {
            content.length >= 500 && usableWords >= 60 -> 100
            content.length >= 160 && usableWords >= 24 -> 85
            content.length >= 48 && usableWords >= 8 -> 70
            else -> 45
        }
        return CombinedContributionAssessment(true, score, "usable_partial_contribution".takeIf { terminalFailure }, terminalFailure)
    }
}

/** Compatibility facade for older call sites; new code names the actual structural measurement. */
internal object CombinedContributionQuality {
    fun assess(status: String?, rawContent: String): CombinedContributionAssessment = CombinedContributionUsability.assess(status, rawContent)
}
