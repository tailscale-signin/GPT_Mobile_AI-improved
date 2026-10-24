package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2

internal data class CombinedContributionSelection(val message: MessageV2, val latestAttemptId: String?, val bestUsableAttemptId: String?)

/** Inspection alone does not replace a successful latest reply; a failed retry cannot erase it. */
internal fun selectCombinedContribution(message: MessageV2, runs: Map<String, AgentRun>, request: String?): CombinedContributionSelection {
    if (message.isCombinedSynthesis()) return CombinedContributionSelection(message, message.currentRunId, message.currentRunId)
    fun assessment(candidate: MessageV2): CombinedContributionAssessment {
        val generation = candidate.timeline.lastOrNull { it.generation != null }?.generation
        val status = runs[candidate.currentRunId]?.status ?: if (generation?.finishReason in setOf("stop", "end_turn", "STOP")) AgentRunStatus.COMPLETED else AgentRunStatus.INTERRUPTED
        return CombinedContributionUsability.assess(status, CombinedContributionQuality.sanitizeContribution(candidate.content), allowPartial = true, request = request, finishReason = generation?.finishReason, outputTokens = generation?.outputTokens, outputAllowance = generation?.outputAllowance)
    }
    val latest = assessment(message)
    if (latest.admitted && !latest.usablePartial && runs[message.currentRunId]?.status == AgentRunStatus.COMPLETED) return CombinedContributionSelection(message, message.currentRunId, message.currentRunId)
    val historical = message.revisions.mapIndexedNotNull { index, revision ->
        if (revision.runId?.startsWith("combined-synthesis:") == true || revision.content.isBlank()) return@mapIndexedNotNull null
        val candidate = message.copy(content = revision.content, thoughts = revision.thoughts, timeline = revision.timeline, currentRunId = revision.runId, createdAt = revision.createdAt, combinedSources = revision.combinedSources, activeRevisionIndex = ACTIVE_REVISION_LATEST)
        val assessed = assessment(candidate)
        if (!assessed.admitted) null else Triple(index, candidate, assessed)
    }
    val complete = historical.filter { !it.third.usablePartial }
    val preferred = complete.firstOrNull { it.first == message.activeRevisionIndex } ?: complete.lastOrNull()
    val chosen = preferred?.second ?: if (latest.admitted) message else historical.lastOrNull()?.second ?: message
    return CombinedContributionSelection(chosen, message.currentRunId, chosen.currentRunId.takeIf { assessment(chosen).admitted })
}
