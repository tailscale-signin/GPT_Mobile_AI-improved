package dev.chungjungsoo.gptmobile.data.assist

import dev.chungjungsoo.gptmobile.data.context.ContextBudgetService

/** Count the entire task, including its complete goal and output/margin reservation. */
internal object AssistChunkPlanner {
    data class Plan<T>(val chunks: List<List<T>>, val unprocessed: List<T>, val reason: String?)

    fun <T> plan(items: List<T>, capacity: Int, outputTokens: Int, encode: (List<T>) -> String): Plan<T> {
        val allowance = capacity.toLong() - outputTokens - 256L
        if (allowance <= 0 || ContextBudgetService.estimate(encode(emptyList())) > allowance) return Plan(emptyList(), items, "TASK_CONTEXT_INSUFFICIENT")
        val chunks = mutableListOf<List<T>>()
        val unprocessed = mutableListOf<T>()
        var current = mutableListOf<T>()
        for (item in items) {
            if (ContextBudgetService.estimate(encode(current + item)) <= allowance) {
                current += item
            } else {
                if (current.isNotEmpty()) chunks += current.toList()
                current = mutableListOf()
                if (ContextBudgetService.estimate(encode(listOf(item))) <= allowance) current += item else unprocessed += item
            }
        }
        if (current.isNotEmpty()) chunks += current.toList()
        return Plan(chunks, unprocessed, if (unprocessed.isEmpty()) null else "PARTIAL_CONTEXT")
    }
}
