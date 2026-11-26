package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.github.GitHubLookupRoute
import dev.chungjungsoo.gptmobile.data.github.GitHubTaskContinuity
import java.util.Locale

@kotlinx.serialization.Serializable
enum class TaskRecoveryAction {
    CONTINUE_RUN,
    RESUME_VERIFICATION,
    RETRY_FAILED_STAGE,
    RETRY_WRITING,
    CONTINUE_PRIMARY_ONLY;

    companion object {
        fun fromText(text: String): TaskRecoveryAction? = when (dev.chungjungsoo.gptmobile.data.github.GitHubTaskContinuity.request(text).lowercase(Locale.ROOT).trimEnd('.', '!', '?').trim().replace(Regex("\\s+"), " ").removePrefix("please ")) {
            "continue", "continue research", "continue run", "continue response", "resume", "proceed" -> CONTINUE_RUN
            "resume verification", "verify saved results" -> RESUME_VERIFICATION
            "retry writing", "resume writing" -> RETRY_WRITING
            "test again", "retest", "retry", "retry full", "retry failed stage", "try again" -> RETRY_FAILED_STAGE
            "proceed primary-only", "proceed primary only", "continue primary-only", "continue primary only", "primary-only" -> CONTINUE_PRIMARY_ONLY
            else -> if (dev.chungjungsoo.gptmobile.data.github.GitHubExecutionLimits.renewedDuration(text) != null || dev.chungjungsoo.gptmobile.data.github.GitHubNativeContinuation.matches(text)) CONTINUE_RUN else null
        }
    }
}

@kotlinx.serialization.Serializable
data class TaskResumeDescriptor(
    val rootWorkId: String,
    val sourceMessageId: Int,
    val generationId: String,
    val action: TaskRecoveryAction,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val continuationPrefixDigest: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val allowUndispatchedBudgetRetry: Boolean = false,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val allowNewToolBudget: Boolean = false
) {
    init {
        require(continuationPrefixDigest == null || continuationPrefixDigest.matches(Regex("[a-f0-9]{64}")))
    }
}

internal fun resolveTaskRecovery(messages: List<MessageV2>, descriptor: TaskResumeDescriptor): ResolvedTaskRecovery? {
    val latest = messages.lastOrNull() ?: return null
    val source = messages.firstOrNull { it.id == descriptor.sourceMessageId && it.chatId == latest.chatId && it.platformType == null } ?: return null
    if (descriptor.rootWorkId != "${source.chatId}:${source.id}" || TaskRecoveryAction.fromText(source.content) != null) return null
    return ResolvedTaskRecovery(
        descriptor.action,
        source.id,
        source.content,
        descriptor.action == TaskRecoveryAction.CONTINUE_PRIMARY_ONLY,
        retryUndispatchedBudgetFailures = descriptor.allowUndispatchedBudgetRetry && descriptor.action == TaskRecoveryAction.RETRY_FAILED_STAGE,
        renewsToolBudget = descriptor.allowNewToolBudget && descriptor.action !in setOf(TaskRecoveryAction.RETRY_WRITING, TaskRecoveryAction.RESUME_VERIFICATION)
    )
}

internal data class ResolvedTaskRecovery(val action: TaskRecoveryAction, val sourceMessageId: Int, val originalRequest: String, val primaryOnly: Boolean, val retryUndispatchedBudgetFailures: Boolean = false, val renewsToolBudget: Boolean = false) {
    fun prompt(): String = "Original task and constraints:\n$originalRequest\n\n" +
        "Recovery action: ${action.name}. Continue unfinished work using the saved partial answer and evidence. " +
        "Do not search for the recovery command itself. Do not repeat completed tool actions; verify unknown action outcomes before retrying. " +
        when {
            action == TaskRecoveryAction.RESUME_VERIFICATION -> "Verify only saved results and receipts. Do not reopen execution, refresh sources, or repeat tools or writes."
            primaryOnly -> "Use the primary model and its permitted tools. Delegation and independent reviewer selection are disabled for this recovery."
            else -> "Retry only unfinished or failed work; preserve completed results."
        }
}

private fun substantive(message: MessageV2) = message.content.isNotBlank() && TaskRecoveryAction.fromText(message.content) == null && !GitHubTaskContinuity.continuation(message.content)

/** Reconstruct from persisted user turns, including chains of controls after process restart. */
internal fun resolveTaskRecovery(messages: List<MessageV2>): ResolvedTaskRecovery? = resolveTaskRecovery(messages) { prior, latest ->
    GitHubTaskContinuity.sharesLookupWorkspace(prior.content, latest.content)
}

internal inline fun resolveTaskRecovery(messages: List<MessageV2>, sharesLookupWorkspace: (MessageV2, MessageV2) -> Boolean?): ResolvedTaskRecovery? {
    val latest = messages.lastOrNull() ?: return null
    val current = latest.content
    val previous = messages.dropLast(1).filter { it.chatId == latest.chatId && it.platformType == null }
    var sourceIndex = previous.indexOfLast { substantive(it) }
    if (sourceIndex < 0) return null
    val priorTask = previous[sourceIndex]
    val explicitAction = TaskRecoveryAction.fromText(current)
    val lookupReport = if ((GitHubTaskContinuity.reportOnly(current) || GitHubTaskContinuity.diagnostic(current)) && GitHubLookupRoute.from(priorTask.content) != null) {
        when (sharesLookupWorkspace(priorTask, latest)) {
            true -> true
            false -> return null
            null -> if (explicitAction == null) return null else false
        }
    } else {
        false
    }
    val equivalent = dev.chungjungsoo.gptmobile.data.github.GitHubTestChecklist.integration(current) && dev.chungjungsoo.gptmobile.data.github.GitHubTaskContinuity.reuse(current, priorTask.content)
    val repositoryFollowUp = requiresNativeGitHubAcceptance(priorTask.content, true) &&
        dev.chungjungsoo.gptmobile.data.github.GitHubTaskContinuity.continuation(current)
    val action = explicitAction ?: if (lookupReport || equivalent || repositoryFollowUp) TaskRecoveryAction.CONTINUE_RUN else return null
    // Walk the persisted intent chain, skipping controls and diagnostic questions. Never
    // cross an unrelated substantive request or derive identity from matching text alone.
    if (dev.chungjungsoo.gptmobile.data.github.GitHubTestChecklist.integration(priorTask.content)) {
        while (sourceIndex > 0) {
            val older = previous.take(sourceIndex).indexOfLast { substantive(it) }
            if (older < 0 || !dev.chungjungsoo.gptmobile.data.github.GitHubTaskContinuity.reuse(previous[sourceIndex].content, previous[older].content)) break
            sourceIndex = older
        }
    }
    val source = previous[sourceIndex]
    val primaryOnly = action == TaskRecoveryAction.CONTINUE_PRIMARY_ONLY || previous.drop(sourceIndex + 1).any { TaskRecoveryAction.fromText(it.content) == TaskRecoveryAction.CONTINUE_PRIMARY_ONLY }
    return ResolvedTaskRecovery(action, source.id, source.content, primaryOnly, retryUndispatchedBudgetFailures = action == TaskRecoveryAction.RETRY_FAILED_STAGE)
}

/** Identity is derived only from this chat's persisted original message, never task text. */
internal fun rootWorkId(messages: List<MessageV2>, fallbackRunId: String): String {
    val latest = messages.lastOrNull() ?: return fallbackRunId
    if (latest.id <= 0) return fallbackRunId
    val sameChat = messages.filter { it.chatId == latest.chatId }
    return "${latest.chatId}:${resolveTaskRecovery(sameChat)?.sourceMessageId ?: latest.id}"
}
