package dev.chungjungsoo.gptmobile.data.context

/** Optional repository context never displaces admitted user input or tool evidence. */
internal object PreparedContextBudget {
    fun optionalTokens(plan: ContextPlan, contextTokens: Int, requestedOutput: Int): Int {
        if (contextTokens !in 1 until Int.MAX_VALUE) return 0
        val output = plan.outputTokens ?: requestedOutput.takeIf { it > 0 } ?: minOf(4096, contextTokens / 4)
        val available = RequestAdmission.maximumEstimatedPrompt(contextTokens.toLong() - output) - plan.toolResultBytes / 3 - plan.promptTokens
        // The first optional section adds a heading and separators to the prompt.
        return (available - 64).coerceIn(0, 4000).toInt()
    }

    /** Keep whole bounded evidence sections and their revision/coverage metadata. */
    fun sections(candidates: List<String>, availableTokens: Int): String {
        var remaining = availableTokens.coerceAtLeast(0)
        return buildString {
            for (section in candidates) {
                if (section.isBlank()) continue
                val text = "\n$section"
                val cost = ContextBudgetService.estimate(text)
                if (cost > remaining) continue
                append(text)
                remaining -= cost
            }
        }
    }
}
