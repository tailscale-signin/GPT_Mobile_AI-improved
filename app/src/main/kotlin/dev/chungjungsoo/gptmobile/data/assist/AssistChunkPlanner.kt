package dev.chungjungsoo.gptmobile.data.assist

/** Count the entire task, including its complete goal and output/margin reservation. */
internal object AssistChunkPlanner {
    data class Plan<T>(val chunks: List<List<T>>, val unprocessed: List<T>, val reason: String?)

    internal fun estimate(text: String): Long = (text.toByteArray(Charsets.UTF_8).size.toLong() + 1) / 2

    fun <T> plan(items: List<T>, capacity: Int, outputTokens: Int, encode: (List<T>) -> String): Plan<T> {
        // Match LocalContextPlanner: UTF-8/2 plus worker system, capability contract and chat template.
        val allowance = capacity.toLong() - outputTokens - 768L
        if (allowance <= 0 || estimate(encode(emptyList())) > allowance) return Plan(emptyList(), items, "TASK_CONTEXT_INSUFFICIENT")
        val chunks = mutableListOf<List<T>>()
        val unprocessed = mutableListOf<T>()
        var current = mutableListOf<T>()
        for (item in items) {
            if (estimate(encode(current + item)) <= allowance) {
                current += item
            } else {
                if (current.isNotEmpty()) chunks += current.toList()
                current = mutableListOf()
                if (estimate(encode(listOf(item))) <= allowance) current += item else unprocessed += item
            }
        }
        if (current.isNotEmpty()) chunks += current.toList()
        return Plan(chunks, unprocessed, if (unprocessed.isEmpty()) null else "PARTIAL_CONTEXT")
    }
}
