package dev.chungjungsoo.gptmobile.data.accounting

import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.context.TextTokenEstimate

/** Shared admission estimate for the tool evidence that the next work round will replay. */
internal object InvocationReplayBudget {
    fun estimate(exchanges: List<AgentToolExchange>): Long = exchanges.sumOf { exchange ->
        exchange.calls.sumOf { TextTokenEstimate.estimate(it.arguments.toString()).toLong() } +
            exchange.results.sumOf { TextTokenEstimate.estimate(it.content.toString()).toLong() }
    }
}
