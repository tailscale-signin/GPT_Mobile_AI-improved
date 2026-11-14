package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.context.ReasoningMode

/** Preflight checks the modes the accepted fixture plan will actually request. */
internal object BenchmarkLlamaReasoning {
    fun forRequest(policy: BenchmarkExecutionPolicy, saved: ReasoningMode, category: String?): ReasoningMode =
        if (policy == BenchmarkExecutionPolicy.PROFILE_SETTINGS_V1 || category?.startsWith(IntelligenceBenchmark.CATEGORY) == true) {
            saved
        } else {
            ReasoningMode.OFF
        }

    fun forPlan(policy: BenchmarkExecutionPolicy, saved: ReasoningMode, categories: Collection<String>): List<ReasoningMode> =
        categories.map { forRequest(policy, saved, it) }.distinct().ifEmpty { listOf(forRequest(policy, saved, null)) }
}
