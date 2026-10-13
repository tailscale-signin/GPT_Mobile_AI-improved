package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.serialization.Serializable

/** Frozen synthesis input; output and continuation text can never become a new contribution. */
@Serializable
data class CombinedInputSnapshot(
    val version: Int = 1,
    val userMessage: MessageV2,
    val history: List<MessageV2>,
    val replies: List<List<MessageV2>>,
    val sources: List<CombinedModelResponse>,
    val prompt: String,
    val profileUid: String,
    val model: String,
    val systemPrompt: String,
    val configKey: String,
    val lineage: ContributionLineage? = null
)
