package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.agent.CombinedProgressSnapshot
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.research.ResearchSession
import dev.chungjungsoo.gptmobile.data.research.readableWorkCount

data class ConversationWorkProgress(val fraction: Float, val label: String, val running: Boolean)

/** Measured stage milestones, never elapsed-time guesses or generated-token quotas. */
internal fun conversationWorkProgress(
    research: Boolean,
    combined: Boolean,
    participants: Set<String>,
    runs: List<AgentRun>,
    sessions: List<ResearchSession>,
    budget: CombinedProgressSnapshot?,
    active: Boolean,
    reportAccepted: Boolean
): ConversationWorkProgress? {
    if (!research && !combined) return null
    val contributors = runs.filter { it.synthesisKey == null && !it.runId.startsWith("combined-synthesis:") }.groupBy { it.profileUid }.mapValues { (_, attempts) -> attempts.maxBy { it.createdAt } }
    val uids = budget?.participants?.toSet()?.takeIf { it.isNotEmpty() } ?: participants
    val completedProfiles = uids.count { uid -> budget?.completedProfiles?.contains(uid) == true || contributors[uid]?.status == AgentRunStatus.COMPLETED }
    val final = runs.filter { it.synthesisKey != null || it.runId.startsWith("combined-synthesis:") }.maxByOrNull { it.createdAt }
    val sourceFraction = sessions.maxOfOrNull { (it.snapshot.sources.readableWorkCount().toFloat() / it.snapshot.targetSources.coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 0f
    val allContributors = uids.isNotEmpty() && completedProfiles == uids.size
    val delivered = if (combined) budget?.delivered == true && final?.status == AgentRunStatus.COMPLETED && allContributors else reportAccepted && allContributors
    val ready = delivered && (!research || reportAccepted) && !active
    val writing = final?.status == AgentRunStatus.RUNNING || !combined && sessions.isNotEmpty() && sessions.all { it.snapshot.complete }
    val contributorFraction = if (research) maxOf(completedProfiles.toFloat() / uids.size.coerceAtLeast(1), sourceFraction * 0.65f) else completedProfiles.toFloat() / uids.size.coerceAtLeast(1)
    val fraction = if (ready) 1f else (0.7f * contributorFraction + if (writing) 0.15f else 0f).coerceIn(0f, 0.95f)
    val phase = when {
        ready -> "Complete"
        !active -> "Partial · retained work"
        writing -> "Writing and validating"
        research -> "Researching"
        else -> "Generating profiles"
    }
    return ConversationWorkProgress(fraction, "$phase · ${(fraction * 100).toInt()}%", active)
}
