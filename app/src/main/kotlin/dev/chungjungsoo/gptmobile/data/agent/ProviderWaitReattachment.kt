package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Scheduling an existing wait never creates a request or changes its accepted authority. */
internal object ProviderWaitReattachment {
    suspend fun reattach(
        message: MessageV2,
        expected: AgentRun?,
        loadMessage: suspend (Int) -> MessageV2?,
        loadRun: suspend (String) -> AgentRun?,
        loadWait: suspend (String) -> ProviderWait?,
        project: suspend (ProviderWait) -> ProviderWaitDeadline,
        enqueue: (String, Long) -> Unit
    ): ProviderWaitDeadline? {
        val current = loadMessage(message.id) ?: return null
        if (current.chatId != message.chatId || current.platformType != message.platformType) return null
        val runId = current.currentRunId ?: return null
        val row = loadWait(runId) ?: return null
        val run = loadRun(runId) ?: return null
        if (!owns(current, run, row) || expected != null && !sameOwner(expected, run)) return null
        val deadline = project(row)
        if (deadline.runId != row.runId || deadline.fence != row.fence || deadline.retryAt != row.retryAt || deadline.effectiveRetryAt < row.retryAt) return null
        // Loading configuration may suspend. Recheck the complete durable wait before scheduling.
        val latest = loadMessage(message.id) ?: return null
        val latestRun = loadRun(runId) ?: return null
        if (loadWait(runId) != row || latest.platformType != current.platformType || !owns(latest, latestRun, row) || expected != null && !sameOwner(expected, latestRun)) return null
        currentCoroutineContext().ensureActive()
        if (latestRun.status != "RUNNING") enqueue(row.scope, deadline.effectiveRetryAt)
        return deadline
    }

    private fun owns(message: MessageV2, run: AgentRun, row: ProviderWait): Boolean =
        message.id == run.assistantMessageId &&
            message.chatId == run.chatId &&
            message.currentRunId == row.runId &&
            run.runId == row.runId &&
            message.ownershipFence == row.fence &&
            run.ownershipFence == row.fence &&
            run.status in setOf("WAITING_PROVIDER_LIMIT", "QUEUED", "RUNNING")

    private fun sameOwner(expected: AgentRun, current: AgentRun): Boolean =
        expected.runId == current.runId &&
            expected.chatId == current.chatId &&
            expected.userMessageId == current.userMessageId &&
            expected.assistantMessageId == current.assistantMessageId &&
            expected.profileUid == current.profileUid &&
            expected.providerSnapshot == current.providerSnapshot &&
            expected.modelSnapshot == current.modelSnapshot &&
            expected.ownershipFence == current.ownershipFence &&
            expected.status == current.status &&
            expected.terminalError == current.terminalError
}
