package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.ContextCostBreakdown
import dev.chungjungsoo.gptmobile.data.context.RequestAdmission

internal data class GitHubSchemaBudget(val capacity: Int, val input: Long, val output: Int, val safety: Int, val toolResults: Int? = null, val promptCosts: ContextCostBreakdown? = null, val wireSafety: Boolean = false) {
    val available: Long get() = (capacity.toLong() - input - output - safety - (toolResults ?: 0)).coerceAtLeast(0)

    fun withReserves(reply: Int, results: Int?): GitHubSchemaBudget {
        val headroom = (capacity.toLong() - reply).coerceAtLeast(0)
        val margin = if (wireSafety) {
            (headroom - RequestAdmission.maximumEstimatedPrompt(headroom)).coerceAtLeast(256).toInt()
        } else {
            safety
        }
        return copy(output = reply, toolResults = results, safety = margin)
    }

    /** Share the bounded prompt window between required schemas and the next tool result. */
    fun fitInitialSchemas(required: Long, allowOutputReduction: Boolean): GitHubSchemaBudget {
        if (required <= available) return this
        val minimumOutput = if (allowOutputReduction) minOf(output, 512) else output
        val reserve = requireNotNull(toolResults)
        val minimumResults = minOf(reserve, 512)
        val smallest = withReserves(minimumOutput, minimumResults)
        if (required > smallest.available) throw GitHubToolContextBudgetException(required, smallest.available, smallest)
        val resultTokens = minOf(reserve.toLong(), minimumResults + smallest.available - required).toInt()
        val chargedInput = input + required + resultTokens
        val margin = if (wireSafety) RequestAdmission.estimateSafety(chargedInput) else safety.toLong()
        return withReserves(minOf(output.toLong(), capacity - chargedInput - margin).toInt(), resultTokens)
    }
}
