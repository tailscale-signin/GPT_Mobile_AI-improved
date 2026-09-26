package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.Serializable

@Serializable
data class TokenBudgetSettings(val contextTokens: Int = 32768, val outputTokens: Int = 2048, val totalRunTokens: Int = 65536, val profileContextCeilings: Map<String, Int> = emptyMap()) {
    fun normalized() = copy(contextTokens = contextTokens.coerceIn(256, 1048576), outputTokens = outputTokens.coerceIn(128, 32768), totalRunTokens = totalRunTokens.coerceIn(4096, 2097152))
}

data class ContextPlan(val turns: List<ConversationTurn>, val system: String, val tools: List<AgentToolDefinition>, val toolResultBytes: Int, val outputTokens: Int, val notice: String)

/** Conservative byte-based estimates. Provider-reported counts remain the accounting authority. */
object ContextBudgetService {
    fun estimate(text: String): Int = (text.toByteArray(Charsets.UTF_8).size + 2) / 3
    fun plan(turns: List<ConversationTurn>, system: String, tools: List<AgentToolDefinition>, settings: TokenBudgetSettings): ContextPlan {
        val config = settings.normalized()
        val output = config.outputTokens.coerceAtMost(config.contextTokens / 4)
        val resultReserve = (config.contextTokens / 8).coerceAtMost(8192)
        val promptLimit = config.contextTokens - output - resultReserve - 256
        fun cost(turn: ConversationTurn): Int = estimate(turn.userMessage.content) + estimate(turn.assistantMessage?.content.orEmpty()) +
            (turn.userMessage.attachments.size + (turn.assistantMessage?.attachments?.size ?: 0)) * 2048 + 16
        val current = turns.filter { it.isCurrentTurn }
        var used = estimate(system) + current.sumOf(::cost)
        require(used <= promptLimit) { "The current message and system context exceed the estimated context budget. Shorten the message or increase the context limit in Tool connections." }
        val selectedTools = tools.filter { tool ->
            val size = estimate(tool.name + tool.description + tool.inputSchema.toString()) + 16
            (used + size <= promptLimit).also { if (it) used += size }
        }
        val history = mutableListOf<ConversationTurn>()
        for (turn in turns.filterNot { it.isCurrentTurn }.asReversed()) {
            val size = cost(turn)
            if (used + size > promptLimit) break
            used += size
            history += turn
        }
        val omitted = turns.size - current.size - history.size
        return ContextPlan(
            history.asReversed() + current,
            system,
            selectedTools,
            resultReserve * 3,
            output,
            "Context estimate: $used prompt tokens, $output output reserved, $resultReserve tool-result tokens reserved. $omitted earlier turns and ${tools.size - selectedTools.size} tools omitted. Attachment costs are estimates; server limits may differ."
        )
    }
}
