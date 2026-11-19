package dev.chungjungsoo.gptmobile.data.context

/** Allocate reply and actionable evidence within the resolved route ceiling. */
internal object ReplayAllowance {
    data class Allocation(val output: Int, val replay: Int, val ceiling: Int, val input: Int, val safety: Int)

    /** A saved tool configuration alone is not evidence that a request exposes executable tools. */
    fun needsConservativeToolReserve(githubMode: Boolean, requireExecution: Boolean, availableTools: Int): Boolean =
        githubMode || (requireExecution && availableTools > 0)

    /** Unknown provider context is not a verified unlimited capacity. Plan tool work before history/schema selection. */
    fun conservativeToolPlanningBudget(settings: TokenBudgetSettings, toolEvidenceRequired: Boolean): TokenBudgetSettings {
        if (!toolEvidenceRequired || settings.contextTokens != Int.MAX_VALUE) return settings
        val output = settings.outputTokens.takeIf { it > 0 } ?: 768
        return settings.copy(contextTokens = 4096, outputTokens = minOf(output, 768))
    }
    fun allocate(context: Int, input: Int, output: Int, fallback: Int): Allocation {
        // Int.MAX_VALUE denotes unknown/unlimited, not a verified route capacity.
        val ceiling = if (context in 1 until Int.MAX_VALUE) context else fallback.coerceIn(0, 4096)
        val prompt = input.coerceAtLeast(0)
        val remaining = (RequestAdmission.maximumEstimatedPrompt(ceiling.toLong()) - prompt).coerceAtLeast(0).toInt()
        val evidence = minOf(4096, (remaining - 512).coerceAtLeast(0) / 2)
        val reply = minOf(output.coerceAtLeast(0), (remaining - evidence).coerceAtLeast(0))
        val replay = (RequestAdmission.maximumEstimatedPrompt(ceiling.toLong() - reply) - prompt).coerceIn(0, 24_000).toInt()
        return Allocation(reply, replay, ceiling, prompt, RequestAdmission.estimateSafety(prompt.toLong() + replay).toInt())
    }
    fun tokens(context: Int, input: Int, output: Int, fallback: Int): Int = allocate(context, input, output, fallback).replay
}
