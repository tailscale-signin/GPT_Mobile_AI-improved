package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent

internal data class LocalContextPlan(
    val priorTurns: List<ConversationTurn>,
    val tools: List<AgentToolDefinition>,
    val toolResultBytes: Int,
    val estimatedPromptTokens: Long,
    val omittedTurns: Int,
    val omittedTools: Int
)

/**
 * A local engine's KV capacity covers the system prompt, chat template, tools,
 * tool replies AND generation. Output preferences are independent of this reservation.
 * Estimates are deliberately conservative; only the native tokenizer is authoritative.
 */
internal object LocalContextPlanner {
    fun plan(
        priorTurns: List<ConversationTurn>,
        currentUserPrompt: String,
        systemPrompt: String?,
        tools: List<AgentToolDefinition>,
        contextTokens: Int,
        outputLimit: Int?,
        imageCount: Int = 0,
        historyImageCount: (ConversationTurn) -> Int = { 0 }
    ): LocalContextPlan {
        require(contextTokens > 0)
        val outputReserve = minOf(outputLimit?.takeIf { it > 0 } ?: 1024, maxOf(1, contextTokens / 4))
        // Tool definitions are charged below at their measured cost. Reserving another
        // tool block here double-counted the same tokens and caused local conversations
        // to silently receive zero tools on small-context models.
        val toolResultReserve = if (tools.isEmpty()) 0 else maxOf(128, contextTokens / 4)
        val templateReserve = minOf(256, contextTokens / 8)
        // Reserve explicit result headroom for small local contexts; larger engines have
        // provider-level response budgeting and should not lose tools unnecessarily.
        val promptLimit = contextTokens.toLong() - outputReserve - templateReserve -
            toolResultReserve
        var used = estimate(systemPrompt.orEmpty()) + estimate(currentUserPrompt) + imageCount.toLong() * IMAGE_TOKEN_ESTIMATE
        require(used < promptLimit) {
            "This message and system instructions exceed this local model's $contextTokens-token context. " +
                "Shorten the message or system instructions, remove attachments, or choose a model with a larger context. " +
                "Max Output Tokens controls reply length; it does not increase model context."
        }

        val requiredNames = requiredToolNames(currentUserPrompt, tools)
        val rankedTools = tools.sortedWith(compareByDescending<AgentToolDefinition> { it.name in requiredNames }.thenByDescending { relevance(currentUserPrompt, it) }.thenBy { it.name })
        val selectedTools = rankedTools.filter { tool ->
            val cost = estimate(tool.name) + estimate(tool.description) + estimate(tool.inputSchema.toString()) + 32
            (used + cost < promptLimit).also { fits -> if (fits) used += cost }
        }
        require(selectedTools.map { it.name }.containsAll(requiredNames)) { "Required tool schemas do not fit this local context. Select fewer tools or a larger-context model; the app cannot complete this tool-dependent request without them." }
        fun cost(turn: ConversationTurn): Long = estimate(turn.userMessage.effectiveContent()) +
            estimate(turn.assistantMessage?.effectiveContent().orEmpty()) + 32 +
            historyImageCount(turn).toLong() * IMAGE_TOKEN_ESTIMATE

        // Preserve the initial goal when it fits, but never keep an oversized anchor.
        val anchor = priorTurns.firstOrNull()?.takeIf { used + cost(it) < promptLimit }
        if (anchor != null) used += cost(anchor)
        val recent = mutableListOf<ConversationTurn>()
        val candidates = if (anchor != null) priorTurns.drop(1) else priorTurns
        for (turn in candidates.asReversed()) {
            val tokens = cost(turn)
            if (used + tokens >= promptLimit) break
            used += tokens
            recent += turn
        }
        val retained = listOfNotNull(anchor) + recent.asReversed()
        return LocalContextPlan(
            retained,
            selectedTools,
            toolResultReserve * 2,
            used,
            priorTurns.size - retained.size,
            tools.size - selectedTools.size
        )
    }

    internal fun requiredToolNames(prompt: String, tools: List<AgentToolDefinition>): Set<String> {
        val lower = prompt.lowercase()
        val required = mutableSetOf<String>()
        fun select(word: String) {
            tools.firstOrNull { it.name == word }?.let { required += it.name }
        }
        if (Regex("nearby|nearest|closest|my location|current location").containsMatchIn(lower)) select("device_location")
        if ("airbnb" in lower) select("airbnb")
        if (Regex("read.*https?://|open.*https?://|crawl|read the page").containsMatchIn(lower)) select("read_url")
        if (Regex("search|research|look up|latest|browse|find online").containsMatchIn(lower)) select("web_search")
        return required
    }

    private fun relevance(prompt: String, tool: AgentToolDefinition): Int {
        val words = Regex("[a-z]{3,}").findAll(prompt.lowercase()).map { it.value }.toSet()
        val descriptor = (tool.name + " " + tool.description).lowercase()
        return words.count { it in descriptor }
    }

    // JSON/tool templates and multilingual input cost more than English prose.
    private fun estimate(text: String): Long = (text.toByteArray(Charsets.UTF_8).size.toLong() + 1) / 2
    private const val IMAGE_TOKEN_ESTIMATE = 256
}
