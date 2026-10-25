package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.agent.TaskRecoveryAction

/** Original task is already restored in the current turn; earlier attempts stay in the saved reader. */
internal object LocalFailedStageContext {
    fun prepare(turns: List<ConversationTurn>, sourceMessageId: Int): List<ConversationTurn> {
        val source = turns.indexOfFirst { !it.isCurrentTurn && it.userMessage.id == sourceMessageId }
        if (source < 0) return turns
        return turns.filterIndexed { index, turn ->
            turn.isCurrentTurn || index < source || index > source && TaskRecoveryAction.fromText(turn.userMessage.content) == null
        }
    }
}
