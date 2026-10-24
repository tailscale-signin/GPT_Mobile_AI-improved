package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.agent.CombinedProgressSnapshot
import dev.chungjungsoo.gptmobile.data.chat.ReplyDisplayRole
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.research.ResearchSession
import dev.chungjungsoo.gptmobile.data.research.readableWorkCount

data class ConversationWorkProgress(val fraction: Float, val label: String, val running: Boolean, val userMessageId: Int? = null)

internal val conversationAttemptOrder = compareBy<AgentRun> { it.ownershipFence }.thenBy { it.createdAt }.thenBy { it.startedAt ?: it.createdAt }

internal fun AgentRun.isCombinedFinal() = displayRole == ReplyDisplayRole.COMBINED || synthesisKey != null || runId.startsWith("combined-synthesis:")

/** Measured stage milestones, never elapsed-time guesses or generated-token quotas. */
internal fun conversationWorkProgress(
    research: Boolean,
    combined: Boolean,
    participants: Set<String>,
    runs: List<AgentRun>,
    sessions: List<ResearchSession>,
    budget: CombinedProgressSnapshot?,
    active: Boolean,
    reportAccepted: Boolean,
    finalTextDelivered: Boolean = false
): ConversationWorkProgress? {
    val latestTurnId = runs.maxOfOrNull { it.userMessageId }
    val turnRuns = runs.filter { it.userMessageId == latestTurnId }
    val contributors = turnRuns.filterNot { it.isCombinedFinal() }.groupBy { it.profileUid }
        .mapValues { (_, attempts) -> attempts.maxWith(conversationAttemptOrder) }
    val uids = budget?.participants?.toSet()?.takeIf { it.isNotEmpty() } ?: participants
    // A running retry supersedes an older completion, including an older budget milestone.
    val completedProfiles = uids.count { uid ->
        contributors[uid]?.let { it.status == AgentRunStatus.COMPLETED } ?: (budget?.completedProfiles?.contains(uid) == true)
    }
    val final = turnRuns.filter { it.isCombinedFinal() }.maxWithOrNull(conversationAttemptOrder)
    val stopped = setOf(AgentRunStatus.FAILED, AgentRunStatus.CANCELED, AgentRunStatus.INTERRUPTED)
    val contributorsRunning = contributors.values.any { it.status in setOf(AgentRunStatus.QUEUED, AgentRunStatus.RUNNING) }
    // Contributor success alone cannot complete Combined. Require the current final text,
    // final run, and delivery receipt; failed/excluded contributors need not all succeed.
    val delivered = finalTextDelivered &&
        if (combined) {
            !contributorsRunning && budget?.delivered == true && final?.status == AgentRunStatus.COMPLETED
        } else {
            uids.isNotEmpty() && completedProfiles == uids.size
        }
    if (delivered && (!research || reportAccepted)) return ConversationWorkProgress(1f, "Complete", running = false, userMessageId = latestTurnId)
    // Retained milestones are not active work. The view only flashes a completion after
    // displaying this turn's activity; reopening a terminal conversation shows no bar.
    if (!active) return null
    if (combined && final?.status in stopped && !contributorsRunning) return null
    if (!combined && uids.isNotEmpty() && uids.all { contributors[it]?.status in stopped + AgentRunStatus.COMPLETED }) return null
    val sourceFraction = sessions.maxOfOrNull { (it.snapshot.sources.readableWorkCount().toFloat() / it.snapshot.targetSources.coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 0f
    val writing = final?.status == AgentRunStatus.RUNNING || !combined && sessions.isNotEmpty() && sessions.all { it.snapshot.complete }
    val contributorFraction = if (research) maxOf(completedProfiles.toFloat() / uids.size.coerceAtLeast(1), sourceFraction * 0.65f) else completedProfiles.toFloat() / uids.size.coerceAtLeast(1)
    val fraction = (0.7f * contributorFraction + if (writing) 0.15f else 0f).coerceIn(0f, 0.95f)
    val phase = when {
        writing -> "Writing and validating"
        research -> "Researching"
        combined && completedProfiles == uids.size && uids.isNotEmpty() -> "Preparing combined answer"
        uids.size > 1 -> "Replies ready · $completedProfiles/${uids.size}"
        else -> "Generating answer"
    }
    return ConversationWorkProgress(fraction, phase, running = true, userMessageId = latestTurnId)
}
