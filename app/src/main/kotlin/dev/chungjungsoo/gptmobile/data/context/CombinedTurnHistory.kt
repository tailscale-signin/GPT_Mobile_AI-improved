package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext
import kotlinx.serialization.Serializable

/** Delivery history is independent of factual verification and survives a failed primary. */
@Serializable
data class CombinedTurnHistory(
    val rootBudgetId: String,
    val requestedPolicyDigest: String,
    val participantUids: List<String>,
    val deliveryStatus: String = "CANDIDATE_PENDING",
    val profileUid: String? = null,
    val ancestryIds: Set<String> = emptySet(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val deliveryReason: String? = null
) {
    companion object {
        fun accepted(context: CombinedExecutionContext) = CombinedTurnHistory(
            context.rootBudgetId,
            context.requested.requestedPolicyDigest,
            context.requested.participantUids,
            profileUid = context.profileUid
        )
    }
}

internal fun dev.chungjungsoo.gptmobile.data.database.entity.MessageV2.withCombinedOutcome(status: String): dev.chungjungsoo.gptmobile.data.database.entity.MessageV2 = copy(
    timeline = timeline.map { item ->
        item.combinedTurn?.let { marker -> item.copy(combinedTurn = marker.copy(deliveryStatus = "CANDIDATE_$status")) } ?: item
    }
)
