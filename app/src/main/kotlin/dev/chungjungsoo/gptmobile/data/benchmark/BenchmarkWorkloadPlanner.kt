package dev.chungjungsoo.gptmobile.data.benchmark

/** Stable context tiers keep differently sized fixtures out of the same scoring cohort. */
internal object BenchmarkWorkloadPlanner {
    fun contextTier(tokens: Int): Int = Integer.highestOneBit(tokens.coerceIn(256, 32768))

    fun adapt(suite: List<BenchmarkCase>, contextTokens: Int?, reserveTokens: Int = 1024): List<BenchmarkCase> {
        if (contextTokens == null) return suite
        // Match LocalContextPlanner's UTF-8 bytes / 2 estimate. Leave room for
        // output, chat template, system/capability instructions and slack. Historical suites retain their original reserve.
        val promptBudget = (contextTier(contextTokens) - reserveTokens).coerceAtLeast(128)
        return suite.map { test ->
            val budget = when (test.workload) {
                "short" -> maxOf(128, promptBudget / 4)
                "medium" -> maxOf(128, promptBudget / 2)
                else -> promptBudget
            }
            val padding = when {
                test.category == "speed" -> "Synthetic weather record. "
                test.workload == "recall" -> "Irrelevant record. "
                else -> return@map test
            }
            // Remove synthetic padding only. Never truncate instructions, expected
            // evidence, history or the final task to make a benchmark appear to pass.
            val excessBytes = test.prompt.toByteArray(Charsets.UTF_8).size - budget * 2
            if (excessBytes <= 0) return@map test
            val parts = test.prompt.split(padding)
            val paddingBytes = padding.toByteArray(Charsets.UTF_8).size
            val removeCount = ((excessBytes + paddingBytes - 1) / paddingBytes).coerceAtMost(parts.lastIndex)
            // Rebuild once rather than repeatedly copying a long prompt on every
            // saved sample and score-table recalculation.
            val prompt = buildString {
                parts.forEachIndexed { index, part ->
                    if (index > removeCount) append(padding)
                    append(part)
                }
            }
            test.copy(prompt = prompt)
        }
    }
}
