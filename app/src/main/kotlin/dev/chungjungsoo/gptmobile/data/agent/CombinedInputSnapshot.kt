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
    val temperature: Float? = null
)
