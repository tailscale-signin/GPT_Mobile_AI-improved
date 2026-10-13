package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.context.ConversationTurn

private const val REMOTE_SYNTHESIS_CONTEXT_TOKENS = 8_000
private const val REMOTE_SYNTHESIS_CURRENT_TURN_TOKENS = 5_000
private const val REMOTE_SYNTHESIS_FIRST_TURN_TOKENS = 800
private const val REMOTE_SYNTHESIS_RECENT_TURN_TOKENS = 1_100
private const val REMOTE_SYNTHESIS_RECENT_TURNS = 2

/** Optional delivery-only compression. Current goals and stored history remain intact. */
internal fun prepareResearchSynthesisHistory(turns: List<ConversationTurn>, compress: Boolean): List<ConversationTurn> {
    if (!compress) return turns
    if (turns.isEmpty()) return turns
    val indexes = buildList {
        add(0)
        addAll((turns.size - REMOTE_SYNTHESIS_RECENT_TURNS).coerceAtLeast(0) until turns.size)
    }.distinct().sorted()

    val compacted = indexes.map { index ->
        val turn = turns[index]
        val turnBudget = when {
            turn.isCurrentTurn -> REMOTE_SYNTHESIS_CURRENT_TURN_TOKENS
            index == 0 -> REMOTE_SYNTHESIS_FIRST_TURN_TOKENS
            else -> REMOTE_SYNTHESIS_RECENT_TURN_TOKENS
        }
        val userBudget = if (turn.isCurrentTurn) turnBudget else maxOf(256, turnBudget / 2)
        val assistantBudget = (turnBudget - userBudget).coerceAtLeast(0)
        turn.copy(
            userMessage = if (turn.isCurrentTurn) turn.userMessage else turn.userMessage.copy(content = truncateSynthesisText(turn.userMessage.content, userBudget)),
            assistantMessage = turn.assistantMessage?.copy(
                content = truncateSynthesisText(turn.assistantMessage.content, assistantBudget)
            )
        )
    }

    val originalTokens = turns.sumOf {
        dev.chungjungsoo.gptmobile.data.context.ContextBudgetService.estimate(
            it.userMessage.content + it.assistantMessage?.content.orEmpty()
        )
    }
    val compactTokens = compacted.sumOf {
        dev.chungjungsoo.gptmobile.data.context.ContextBudgetService.estimate(
            it.userMessage.content + it.assistantMessage?.content.orEmpty()
        )
    }
    if (originalTokens > compactTokens) {
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "Delegation",
            "Remote synthesis history compacted · inputTokens=$originalTokens · keptTokens=$compactTokens · savedTokens=${originalTokens - compactTokens} · target=$REMOTE_SYNTHESIS_CONTEXT_TOKENS"
        )
    }
    return compacted
}

private fun truncateSynthesisText(text: String, maxTokens: Int): String {
    if (text.isBlank() || maxTokens <= 0) return ""
    if (dev.chungjungsoo.gptmobile.data.context.ContextBudgetService.estimate(text) <= maxTokens) return text
    val maxBytes = maxTokens * 3
    val marker = "\n[… older context trimmed for remote synthesis …]\n"
    val markerBytes = marker.toByteArray().size
    if (maxBytes <= markerBytes + 32) {
        return dev.chungjungsoo.gptmobile.data.agent.truncateUtf8(text, maxBytes)
    }
    val headBytes = (maxBytes - markerBytes) * 2 / 3
    val tailBytes = maxBytes - markerBytes - headBytes
    val head = dev.chungjungsoo.gptmobile.data.agent.truncateUtf8(text, headBytes)
    val tail = dev.chungjungsoo.gptmobile.data.agent.truncateUtf8(
        text.takeLast(minOf(text.length, tailBytes * 2)),
        tailBytes
    )
    return head + marker + tail
}
