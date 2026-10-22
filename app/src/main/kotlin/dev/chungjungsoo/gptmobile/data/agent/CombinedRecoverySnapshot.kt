package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkConfigKey
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AnswerSegment
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Reconstruct only from the original durable input and the owning revision's verified segments. */
internal object CombinedRecoverySnapshot {
    private val json = Json { ignoreUnknownKeys = true }

    fun prepare(run: AgentRun, message: MessageV2, primaryUid: String?, platform: PlatformV2, segments: List<AnswerSegment>): CombinedInputSnapshot {
        require(run.runId.startsWith("combined-synthesis:") && run.status in setOf("COMPLETED", "FAILED", "CANCELED", "INTERRUPTED")) { "The Combined writer is still active." }
        require(message.currentRunId == run.runId && message.ownershipFence == run.ownershipFence && message.id == run.assistantMessageId && message.chatId == run.chatId) { "The saved answer owner changed." }
        val raw = requireNotNull(run.synthesisSnapshot) { "This legacy answer has no frozen synthesis input." }
        require(researchHash(raw) == run.synthesisKey) { "The frozen synthesis input failed its integrity check." }
        val snapshot = json.decodeFromString<CombinedInputSnapshot>(raw)
        require(snapshot.version in 1..6 && snapshot.userMessage.id == run.userMessageId && snapshot.userMessage.chatId == run.chatId) { "The saved synthesis input is incompatible." }
        if (snapshot.version >= 6) require(snapshot.snapshotId.isNotBlank() && snapshot.inputDigest == snapshot.contentDigest()) { "The sealed synthesis snapshot changed." }
        val modern = snapshot.version >= 5 && snapshot.combined != null
        val accepted = snapshot.combined?.requested
        if (modern) {
            require(accepted != null && snapshot.profileUid in accepted.participantUids && snapshot.combined?.rootBudgetId?.isNotBlank() == true) { "The saved fallback owner is not an accepted participant." }
            require(accepted.requestedPolicyDigest == combinedDigest(Json.encodeToString(accepted.copy(requestedPolicyDigest = "")))) { "The accepted Combined policy failed its digest check." }
            require(accepted.userPrompt == snapshot.userMessage.content && accepted.requestedModels[snapshot.profileUid] == snapshot.model) { "The saved owner model or original request differs from the accepted policy." }
            require(accepted.requestedPolicyDigest.isNotBlank() && snapshot.combined?.plan?.requested?.requestedPolicyDigest.let { it == null || it == accepted.requestedPolicyDigest }) { "The saved Combined policy and work plan disagree." }
            snapshot.combined?.plan?.let { plan ->
                require(plan.effectivePlanDigest == combinedDigest(Json.encodeToString(plan.copy(effectivePlanDigest = "")))) { "The saved Combined work plan failed its digest check." }
            }
            snapshot.acceptedTools?.combined?.let { toolsContext ->
                require(toolsContext.rootBudgetId == snapshot.combined?.rootBudgetId && toolsContext.requested.requestedPolicyDigest == accepted.requestedPolicyDigest) { "The accepted tools belong to a different Combined turn." }
            }
            val compiledContract = CombinedAnswerPolicy.from(snapshot.userMessage.content, snapshot.history.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.content })
            val compatibleContract = if (snapshot.answerContract?.version == 2) compiledContract.copy(version = 2, task = null) else compiledContract
            require(snapshot.answerContract != null && snapshot.answerContract == compatibleContract) { "The saved answer contract changed." }
        }
        require((modern || primaryUid == snapshot.profileUid) && platform.enabled && platform.uid == snapshot.profileUid && platform.model == snapshot.model && benchmarkConfigKey(platform) == snapshot.configKey) { "Restore the saved Combined owner and its accepted configuration before resuming." }
        require(snapshot.sources.none { it.localOnly } || platform.compatibleType == ClientType.LITERT_LM) { "Local-only contributions require an authorized local synthesis owner." }
        snapshot.claimInventory?.let {
            require(it == CombinedClaimInventory.freeze(snapshot.sources).let { frozen -> if (it.version == 1) frozen.copy(version = 1, claims = emptyList()) else frozen }) { "The saved source inventory failed its integrity check." }
        }
        val committed = if (run.status == "COMPLETED") {
            ""
        } else {
            AnswerSegmentChain.text(segments, run.runId, run.ownershipFence).also {
                require(it == message.content) { "The saved answer and committed segments disagree; the answer was not replaced." }
            }
        }
        // Structured delivery is a complete new revision. Verify the archived prefix but do not
        // feed already-rendered prose back into the record protocol as a continuation prefix.
        val prefix = if (snapshot.deliveryProtocol in setOf("PASSAGES_V2", "PASSAGES_V3")) "" else committed
        return snapshot.copy(version = maxOf(snapshot.version, 3), resumeFromRunId = run.runId, committedPrefix = prefix, deliveryResumeFromRevisionId = if (snapshot.deliveryProtocol == "PASSAGES_V3") snapshot.liveRevisionId(run.runId) else null)
    }
}
