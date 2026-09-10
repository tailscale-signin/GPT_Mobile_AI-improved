package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.localruntime.LocalHistoryMessage
import dev.chungjungsoo.gptmobile.data.localruntime.LocalHistoryRole

/**
 * Truncates and compacts long conversation histories into bounded context limits
 * while preserving foundational context (system prompt / initial user-assistant anchor turn)
 * and the most recent rolling turn window.
 *
 * This prevents context exhaustion errors on on-device models with fixed KV-cache limits
 * (e.g. NPU clamps at 1,280 tokens or GPU context limits) while keeping prompt prefill fast
 * and avoiding drops of the core conversational intent.
 */
object RollingContextWindowCompactor {

    // Conservative approximation: ~4 characters per token
    const val CHARS_PER_TOKEN_ESTIMATE = 4

    /**
     * Compacts [priorTurns] so that total estimated tokens (including anchor turn, system prompt,
     * and new user prompt) do not exceed [maxContextTokens].
     *
     * Invariants:
     * 1. If history already fits within the token ceiling, returns [priorTurns] unmodified.
     * 2. When history exceeds the token ceiling, preserves the anchor turn (turn 0) and
     *    takes a rolling window of recent turns backwards until the budget is saturated.
     */
    fun compactPriorTurns(
        priorTurns: List<ConversationTurn>,
        maxContextTokens: Int,
        systemPrompt: String? = null,
        currentUserPrompt: String = ""
    ): List<ConversationTurn> {
        if (priorTurns.size <= 1 || maxContextTokens <= 0) return priorTurns

        val systemPromptChars = systemPrompt?.length ?: 0
        val currentUserChars = currentUserPrompt.length
        val totalFixedChars = systemPromptChars + currentUserChars

        val totalBudgetChars = maxContextTokens * CHARS_PER_TOKEN_ESTIMATE
        val availableHistoryChars = maxOf(0, totalBudgetChars - totalFixedChars)

        val totalPriorChars = priorTurns.sumOf { it.charCount() }
        if (totalPriorChars <= availableHistoryChars) {
            return priorTurns
        }

        // Anchor turn is turn 0 (first user goal and early setup instructions)
        val anchorTurn = priorTurns.first()
        val anchorChars = anchorTurn.charCount()

        // If anchor turn alone exceeds budget, keep anchor turn as the single prior turn
        if (anchorChars >= availableHistoryChars) {
            return listOf(anchorTurn)
        }

        val remainingBudget = availableHistoryChars - anchorChars
        val candidates = priorTurns.subList(1, priorTurns.size)
        var accumulatedChars = 0
        var startIndex = candidates.size

        for (i in candidates.indices.reversed()) {
            val turn = candidates[i]
            val turnChars = turn.charCount()
            if (accumulatedChars + turnChars > remainingBudget && startIndex < candidates.size) {
                break
            }
            accumulatedChars += turnChars
            startIndex = i
        }

        val rollingRecentTurns = candidates.subList(startIndex, candidates.size)
        return listOf(anchorTurn) + rollingRecentTurns
    }

    /**
     * Compacts local history messages preserving Turn 0 (anchor user + model turn)
     * and a rolling window of recent messages.
     */
    fun compactLocalHistoryMessages(
        messages: List<LocalHistoryMessage>,
        maxContextTokens: Int,
        systemPrompt: String? = null,
        currentUserPrompt: String = ""
    ): List<LocalHistoryMessage> {
        if (messages.size <= 2 || maxContextTokens <= 0) return messages

        val systemPromptChars = systemPrompt?.length ?: 0
        val currentUserChars = currentUserPrompt.length
        val totalFixedChars = systemPromptChars + currentUserChars

        val totalBudgetChars = maxContextTokens * CHARS_PER_TOKEN_ESTIMATE
        val availableHistoryChars = maxOf(0, totalBudgetChars - totalFixedChars)

        val totalHistoryChars = messages.sumOf { it.text.length }
        if (totalHistoryChars <= availableHistoryChars) {
            return messages
        }

        // Determine anchor messages (first user and first assistant/model message if present)
        val anchorCount = if (messages.size >= 2 && messages[1].role == LocalHistoryRole.MODEL) 2 else 1
        val anchorMessages = messages.take(anchorCount)
        val anchorChars = anchorMessages.sumOf { it.text.length }

        if (anchorChars >= availableHistoryChars) {
            return anchorMessages
        }

        val remainingBudget = availableHistoryChars - anchorChars
        val candidates = messages.drop(anchorCount)
        var accumulatedChars = 0
        var startIndex = candidates.size

        for (i in candidates.indices.reversed()) {
            val msg = candidates[i]
            val chars = msg.text.length
            if (accumulatedChars + chars > remainingBudget && startIndex < candidates.size) {
                break
            }
            accumulatedChars += chars
            startIndex = i
        }

        return anchorMessages + candidates.subList(startIndex, candidates.size)
    }

    private fun ConversationTurn.charCount(): Int =
        userMessage.content.length + (assistantMessage?.content?.length ?: 0)
}
