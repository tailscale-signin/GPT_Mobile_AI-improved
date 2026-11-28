package dev.chungjungsoo.gptmobile.data.context

/** Optional repository context never displaces admitted user input or tool evidence. */
internal object PreparedContextBudget {
    data class RepositorySections(val memory: String, val source: String, val text: String)

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

    /** Capture current decisions/feedback, then spend the remaining optional space on pinned source. */
    suspend fun repositorySections(
        availableTokens: Int,
        prepareMemory: suspend (Int) -> String,
        prepareSource: suspend (Int) -> String
    ): RepositorySections {
        if (availableTokens < 256) return RepositorySections("", "", "")
        // Retain the minimum useful source window even when the residual is small.
        val memoryAllowance = minOf(availableTokens / 4, availableTokens - 256)
        fun admit(section: String, allowance: Int) = section.takeIf { it.isNotBlank() && ContextBudgetService.estimate("\n$it") <= allowance }.orEmpty()
        val memory = admit(prepareMemory(memoryAllowance), memoryAllowance)
        val used = if (memory.isEmpty()) 0 else ContextBudgetService.estimate("\n$memory")
        val sourceAllowance = availableTokens - used
        val source = admit(prepareSource(sourceAllowance), sourceAllowance)
        return RepositorySections(memory, source, sections(listOf(memory, source), availableTokens))
    }
}
