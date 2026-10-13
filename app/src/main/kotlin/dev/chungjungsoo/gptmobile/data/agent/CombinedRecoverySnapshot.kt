package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkConfigKey
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AnswerSegment
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.research.researchHash
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
        require(snapshot.version in 1..3 && snapshot.userMessage.id == run.userMessageId && snapshot.userMessage.chatId == run.chatId) { "The saved synthesis input is incompatible." }
        require(primaryUid == snapshot.profileUid && platform.enabled && platform.uid == snapshot.profileUid && platform.model == snapshot.model && benchmarkConfigKey(platform) == snapshot.configKey) { "Restore the pinned primary and its saved configuration before resuming." }
        require(snapshot.sources.none { it.localOnly } || platform.compatibleType == ClientType.LITERT_LM) { "Local-only contributions require the original local primary." }
        snapshot.claimInventory?.let {
            require(it == CombinedClaimInventory.freeze(snapshot.sources)) { "The saved source inventory failed its integrity check." }
        }
        val prefix = if (run.status == "COMPLETED") {
            ""
        } else {
            AnswerSegmentChain.text(segments, run.runId, run.ownershipFence).also {
                require(it == message.content) { "The saved answer and committed segments disagree; the answer was not replaced." }
            }
        }
        return snapshot.copy(version = 3, resumeFromRunId = run.runId, committedPrefix = prefix)
    }
}
