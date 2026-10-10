package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import java.util.Locale

enum class TaskRecoveryAction {
    CONTINUE_RUN,
    RETRY_FAILED_STAGE,
    CONTINUE_PRIMARY_ONLY;

    companion object {
        fun fromText(text: String): TaskRecoveryAction? = when (text.trim().lowercase(Locale.ROOT).trimEnd('.', '!', '?').trim().replace(Regex("\\s+"), " ")) {
            "continue", "continue run", "continue response", "resume", "proceed" -> CONTINUE_RUN
            "retry", "retry full", "retry failed stage", "try again" -> RETRY_FAILED_STAGE
            "proceed primary-only", "proceed primary only", "continue primary-only", "continue primary only", "primary-only" -> CONTINUE_PRIMARY_ONLY
            else -> null
        }
    }
}

internal data class ResolvedTaskRecovery(val action: TaskRecoveryAction, val sourceMessageId: Int, val originalRequest: String, val primaryOnly: Boolean) {
    fun prompt(): String = "Original task and constraints:\n$originalRequest\n\n" +
        "Recovery action: ${action.name}. Continue unfinished work using the saved partial answer and evidence. " +
        "Do not search for the recovery command itself. Do not repeat completed tool actions; verify unknown action outcomes before retrying. " +
        if (primaryOnly) "Use the primary model and its permitted tools. Delegation and independent reviewer selection are disabled for this recovery." else "Retry only unfinished or failed work; preserve completed results."
}

/** Reconstruct from persisted user turns, including chains of controls after process restart. */
internal fun resolveTaskRecovery(messages: List<MessageV2>): ResolvedTaskRecovery? {
    val action = TaskRecoveryAction.fromText(messages.lastOrNull()?.content.orEmpty()) ?: return null
    val previous = messages.dropLast(1)
    val sourceIndex = previous.indexOfLast { it.content.isNotBlank() && TaskRecoveryAction.fromText(it.content) == null }
    if (sourceIndex < 0) return null
    val source = previous[sourceIndex]
    val primaryOnly = action == TaskRecoveryAction.CONTINUE_PRIMARY_ONLY || previous.drop(sourceIndex + 1).any {
        TaskRecoveryAction.fromText(it.content) == TaskRecoveryAction.CONTINUE_PRIMARY_ONLY
    }
    return ResolvedTaskRecovery(action, source.id, source.content, primaryOnly)
}
