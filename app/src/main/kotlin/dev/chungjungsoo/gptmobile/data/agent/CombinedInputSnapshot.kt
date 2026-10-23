package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import kotlinx.serialization.Serializable

/** Frozen synthesis input; output and continuation text can never become a new contribution. */
@Serializable
data class CombinedInputSnapshot(
    val version: Int = 5,
    val userMessage: MessageV2,
    val history: List<MessageV2>,
    val replies: List<List<MessageV2>>,
    val sources: List<CombinedModelResponse>,
    val prompt: String,
    val profileUid: String,
    val model: String,
    val systemPrompt: String,
    val configKey: String,
    val lineage: ContributionLineage? = null,
    val claimInventory: CombinedClaimInventory? = null,
    val resumeFromRunId: String? = null,
    val committedPrefix: String = "",
    val deliveryProtocol: String = "LEGACY_TEXT",
    val evidence: CombinedEvidenceLedger = CombinedEvidenceLedger(),
    val turnContext: CombinedTurnContext? = null,
    val combined: CombinedExecutionContext? = null,
    val acceptedTools: ChatMcpToolConfig? = null,
    val answerContract: CombinedAnswerPolicy? = null,
    val revisionId: String = "",
    val verificationReceipts: List<CombinedVerificationReceipt> = emptyList(),
    val temperature: Float? = null,
    val snapshotId: String = "",
    val inputDigest: String = "",
    val predecessorSnapshotId: String? = null,
    val selectedHistoryRevisionIds: List<String> = emptyList(),
    val deliveryResumeFromRevisionId: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val acceptedTask: AcceptedTaskRef? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val undeliveredHistoryTurnIds: List<Int> = emptyList(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val writerAuthorityRevisions: Map<String, String> = emptyMap(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val transferableEvidenceIds: List<String> = emptyList(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val contributionsTransferable: Boolean = false,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val predecessorRunId: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val dispatchIdentity: String = ""
) {
    fun contentDigest(): String = dev.chungjungsoo.gptmobile.data.research.researchHash(
        SNAPSHOT_JSON.encodeToString(serializer(), copy(inputDigest = "", resumeFromRunId = null, committedPrefix = "", deliveryResumeFromRevisionId = null))
    )

    /** Always persist dynamic defaults such as message creation times. */
    fun encode(): String = SNAPSHOT_JSON.encodeToString(serializer(), this)

    fun seal(): CombinedInputSnapshot {
        require(version >= 6 && snapshotId.isNotBlank() && inputDigest.isBlank())
        return copy(inputDigest = contentDigest())
    }

    companion object {
        private val SNAPSHOT_JSON = kotlinx.serialization.json.Json { encodeDefaults = true }
    }
}
