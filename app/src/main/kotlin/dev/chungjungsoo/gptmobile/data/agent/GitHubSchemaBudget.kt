package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.ContextCostBreakdown
import dev.chungjungsoo.gptmobile.data.context.RequestAdmission

internal data class GitHubSchemaBudget(val capacity: Int, val input: Long, val output: Int, val safety: Int, val toolResults: Int? = null, val promptCosts: ContextCostBreakdown? = null, val wireSafety: Boolean = false) {
    private val promptCeiling: Long get() = if (wireSafety) RequestAdmission.maximumEstimatedPrompt(capacity.toLong() - output) else capacity.toLong() - output - safety
    val available: Long get() = (promptCeiling - input - (toolResults ?: 0)).coerceAtLeast(0)
    val reservedSafety: Int get() = if (wireSafety) (capacity.toLong() - output - promptCeiling).coerceAtLeast(0).toInt() else safety
    val baseOverrun: Long get() = (input + (toolResults ?: 0) - promptCeiling).coerceAtLeast(0)

    fun withReserves(reply: Int, results: Int?): GitHubSchemaBudget =
        copy(output = reply, toolResults = results).let { it.copy(safety = it.reservedSafety) }

    /** Share the bounded prompt window between required schemas and the next tool result. */
    fun fitInitialSchemas(required: Long, allowOutputReduction: Boolean): GitHubSchemaBudget {
        if (required <= available) return this
        val minimumOutput = if (allowOutputReduction) minOf(output, 512) else output
        val reserve = requireNotNull(toolResults)
        val minimumResults = minOf(reserve, 512)
        val remaining = if (wireSafety) {
            RequestAdmission.maximumEstimatedPrompt(capacity.toLong() - minimumOutput) - input - required
        } else {
            capacity.toLong() - input - safety - required - minimumOutput
        }
        if (remaining < minimumResults) {
            val smallest = copy(output = minimumOutput, toolResults = minimumResults)
            throw GitHubToolContextBudgetException(required, smallest.available, smallest)
        }
        val resultTokens = minOf(reserve.toLong(), remaining).toInt()
        val fittedSafety = if (wireSafety) RequestAdmission.estimateSafety(input + required + resultTokens).toInt() else safety
        val fittedOutput = minOf(output.toLong(), capacity.toLong() - input - required - resultTokens - fittedSafety).toInt()
        return withReserves(fittedOutput, resultTokens)
    }
}
