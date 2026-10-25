package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedDeliveryCheckpoint
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A typed, known rejection may resume only the durable owner and its unchanged record cursor. */
internal object CombinedAppendResume {
    private val json = Json { ignoreUnknownKeys = true }

    fun validate(run: AgentRun, message: MessageV2, checkpoint: CombinedDeliveryCheckpoint, decision: ProviderLimitDecision, now: Long = System.currentTimeMillis()): CombinedInputSnapshot {
        require(dev.chungjungsoo.gptmobile.BuildConfig.COMBINED_APPEND_CONTINUATIONS && decision.knownNotAccepted && decision.retryAt <= now) { "COMBINED_APPEND_PROVIDER_WAIT" }
        require(run.status == "WAITING_PROVIDER_LIMIT" && run.profileUid == decision.profileUid && run.modelSnapshot == decision.model) { "COMBINED_APPEND_WAIT_OWNER_CHANGED" }
        require(message.currentRunId == run.runId && message.ownershipFence == run.ownershipFence && message.id == run.assistantMessageId) { "COMBINED_APPEND_WAIT_OWNER_CHANGED" }
        val raw = requireNotNull(run.synthesisSnapshot) { "COMBINED_APPEND_SNAPSHOT_UNAVAILABLE" }
        require(researchHash(raw) == run.synthesisKey) { "COMBINED_APPEND_SNAPSHOT_CHANGED" }
        val frozen = json.decodeFromString<CombinedInputSnapshot>(raw)
        frozen.bindAcceptedStage()
        require(frozen.combined?.requested?.appendContinuations == true && frozen.deliveryProtocol == "PASSAGES_V3" && frozen.profileUid == decision.profileUid && frozen.model == decision.model) { "COMBINED_APPEND_NOT_ACCEPTED" }
        requireNotNull(frozen.writerRequestPlan) { "COMBINED_APPEND_WRITER_PLAN_UNAVAILABLE" }.validate(frozen)
        val accepted = requireNotNull(frozen.combined).requested
        require(accepted.requestedPolicyDigest == combinedDigest(Json.encodeToString(accepted.copy(requestedPolicyDigest = "")))) { "COMBINED_APPEND_POLICY_CHANGED" }
        require(checkpoint.runId == run.runId && checkpoint.fence == run.ownershipFence && checkpoint.revisionId == frozen.liveRevisionId(run.runId) && checkpoint.snapshotDigest == frozen.inputDigest) { "COMBINED_APPEND_CURSOR_CHANGED" }
        val state = json.decodeFromString<CombinedRecordCheckpoint>(checkpoint.stateJson)
        require(state.snapshotDigest == frozen.inputDigest && state.revisionId == checkpoint.revisionId && state.appendReady && !state.ended && state.noProgressAttempts < 2 && state.lastAttemptOutcome == "KNOWN_PROVIDER_REJECTION" && state.retryAtMillis == decision.retryAt) { "COMBINED_APPEND_REJECTION_CHANGED" }
        return frozen
    }
}
