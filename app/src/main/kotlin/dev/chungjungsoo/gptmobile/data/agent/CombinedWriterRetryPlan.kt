package dev.chungjungsoo.gptmobile.data.agent

import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A user-authorized writing attempt reuses its sources and funds only one new writing stage. */
internal fun CombinedExecutionContext.forExplicitWriterRetry(profileUid: String = requireNotNull(this.profileUid)): CombinedExecutionContext {
    require(requested.singlePass && profileUid in requested.participantUids)
    val original = requireNotNull(plan)
    val retryPlan = original.copy(
        baselineByOwner = emptyMap(),
        optionalAllowance = CombinedResourceQuota(),
        costReservesByOwner = original.costReservesByOwner.filterKeys { it == "finalization" },
        workItems = listOf(CombinedWorkItem("finalization", CombinedRunRole.SYNTHESIS, assignedOwnerKey = "finalization")),
        blockedParticipantUids = emptySet(),
        unfundedRequiredWorkIds = emptySet(),
        renewable = false,
        toolsAllowed = false,
        searchAllowed = false,
        effectivePlanDigest = ""
    ).let { it.copy(effectivePlanDigest = combinedDigest(Json.encodeToString(it))) }
    return copy(rootBudgetId = UUID.randomUUID().toString(), plan = retryPlan, role = CombinedRunRole.SYNTHESIS, profileUid = profileUid, workItemId = "finalization")
}
