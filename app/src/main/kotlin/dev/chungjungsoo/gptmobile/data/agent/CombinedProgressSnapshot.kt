package dev.chungjungsoo.gptmobile.data.agent

/** Only UI-relevant milestones are emitted; token accounting cannot recompose the Home list. */
data class CombinedProgressSnapshot(val chatId: Int, val acceptedAtMillis: Long, val participants: List<String>, val completedProfiles: Set<String>, val delivered: Boolean, val phase: String? = null)

internal fun CombinedBudgetSnapshot.progressSnapshot() = CombinedProgressSnapshot(
    chatId,
    plan.requested.acceptedAtMillis,
    plan.requested.participantUids,
    plan.requested.participantUids.filterTo(linkedSetOf()) { stageOutcomes["contributor:$it"]?.substringBefore(':') == "COMPLETED" },
    deliveryStatus == "COMPLETED",
    deliveryPhase
)
