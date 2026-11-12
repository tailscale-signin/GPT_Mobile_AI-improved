package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveTimeline
import dev.chungjungsoo.gptmobile.data.github.GitHubNativeApprovalProgress
import dev.chungjungsoo.gptmobile.presentation.ui.chat.generationActivityLabel

internal data class GitHubChatProgressProjection(
    val userMessageId: Int?,
    val taskRuns: List<AgentRun>,
    val toolEvents: List<ToolEvent>,
    val timeline: List<AssistantTimelineItem>,
    val generating: Boolean,
    val waiting: Boolean,
    val description: String
) {
    val active: Boolean get() = generating || waiting
}

/** Approval pauses are terminal attempts; native proof resolves their UI pause, not their saved status. */
internal fun GitHubProgrammerState.progressProjection(nativeProgress: GitHubNativeApprovalProgress? = null): GitHubChatProgressProjection {
    val native = nativeProgress?.takeIf { it.matches(context, chatId) }
    val tasks = native?.tasks.orEmpty().filter { it.chatId == null || it.chatId == chatId }
    val pendingTasks = tasks.filter { it.pending }
    val tasksById = tasks.associateBy { it.taskId }
    val unboundPending = pendingTasks.any { it.chatId == null || it.sourceMessageId == null }
    val pendingSources = pendingTasks.mapNotNullTo(hashSetOf()) { it.sourceMessageId }
    val resolvedSources = if (unboundPending) {
        emptySet()
    } else {
        native?.decisions.orEmpty().filter { receipt ->
            receipt.chatId == chatId && receipt.sourceMessageId !in pendingSources && tasksById[receipt.taskId]?.matches(receipt) == true
        }.mapTo(hashSetOf()) { it.sourceMessageId }
    }
    val scopedRuns = runs.filter { chatId == null || it.chatId == chatId }
    val executing = scopedRuns.filter { it.status !in AgentRunStatus.terminal }
    val approvalPauses = scopedRuns.filter { it.status == AgentRunStatus.WAITING_FOR_APPROVAL && it.userMessageId !in resolvedSources }
    val latestRun = compareBy<AgentRun>({ it.createdAt }, { it.userMessageId })
    // The current task owns the activity/timeline; an earlier approval pause cannot label a later attempt.
    val currentTask = (executing + approvalPauses + scopedRuns.filter { it.userMessageId in pendingSources }).maxWithOrNull(latestRun)?.userMessageId
        ?: pendingSources.maxOrNull()
        ?: scopedRuns.maxWithOrNull(latestRun)?.userMessageId
    val taskRuns = scopedRuns.filter { it.userMessageId == currentTask }
    val activeRuns = executing.filter { it.userMessageId == currentTask }
    val activeIds = activeRuns.mapTo(hashSetOf()) { it.runId }
    val taskIds = taskRuns.mapTo(hashSetOf()) { it.runId }
    val taskPending = pendingTasks.filter { it.sourceMessageId == null || it.sourceMessageId == currentTask }
    val approvalNeeded = approvals.any { it.runId in taskIds } || taskPending.any { it.state == "AWAITING_APPROVAL" }
    val waiting = approvalNeeded || taskPending.isNotEmpty() || approvalPauses.any { it.userMessageId == currentTask } || activeRuns.any { it.status.startsWith("WAITING_") }
    val generating = submitting || pending.any { !it.paused } || activeRuns.isNotEmpty()
    val toolEvents = events.filter { it.runId in activeIds }
    val timeline = messages.lastOrNull { it.currentRunId in activeIds }?.effectiveTimeline().orEmpty()
    val description = when {
        approvalNeeded -> "Needs your approval"
        waiting -> "Waiting"
        activeRuns.isNotEmpty() -> generationActivityLabel(toolEvents, timeline)
        submitting -> "Saving command"
        pending.isNotEmpty() -> if (pending.any { it.paused }) "Paused" else "Command queued"
        else -> when (taskRuns.maxWithOrNull(latestRun)?.status) {
            AgentRunStatus.PARTIAL -> "Work remains"
            AgentRunStatus.EVIDENCE_MISSING -> "Evidence needed"
            AgentRunStatus.BLOCKED -> "Work blocked"
            AgentRunStatus.FAILED, AgentRunStatus.INTERRUPTED -> "Needs attention"
            AgentRunStatus.CANCELED, "CANCELLED" -> "Stopped"
            AgentRunStatus.COMPLETED -> "Answer ready"
            else -> "Ready for a command"
        }
    }
    return GitHubChatProgressProjection(currentTask, taskRuns, toolEvents, timeline, generating, waiting, description)
}

internal fun GitHubProgrammerState.progressDescription(nativeProgress: GitHubNativeApprovalProgress? = null): String = progressProjection(nativeProgress).description
