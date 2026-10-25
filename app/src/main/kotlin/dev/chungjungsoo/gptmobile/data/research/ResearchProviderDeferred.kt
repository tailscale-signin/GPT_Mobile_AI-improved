package dev.chungjungsoo.gptmobile.data.research

import dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision
import kotlinx.serialization.Serializable

/** References the exact unfinished stage; saved captures and tool receipts remain authoritative. */
@Serializable
data class ResearchDeferredStage(
    val rootWorkId: String,
    val stage: String,
    val promptDigest: String,
    val limit: ProviderLimitDecision,
    val workItemId: String? = null
)

internal class ResearchProviderDeferred(val checkpoint: ResearchDeferredStage) : IllegalStateException("RESEARCH_WAITING_PROVIDER_LIMIT: Saved progress will resume when this profile is eligible.")

/** One paused model operation, including the exact admitted packet and its continuation claims. */
@Serializable
data class ResearchStageCursor(
    val checkpoint: ResearchDeferredStage,
    val prompt: String,
    val outputTokens: Int,
    val inputTokens: Int,
    val bounded: Boolean,
    val sourceId: String = "",
    val sourceVersion: String = "",
    val sourceHash: String = ""
) {
    internal fun validate() {
        require(checkpoint.stage in ResearchModelStage.entries.map { it.name })
        require(checkpoint.limit.knownNotAccepted)
        require(!checkpoint.workItemId.isNullOrBlank())
        require(prompt.isNotBlank() && researchHash(prompt) == checkpoint.promptDigest)
        require(outputTokens > 0 && inputTokens > 0)
        require(if (sourceId.isBlank()) sourceVersion.isBlank() && sourceHash.isBlank() else sourceVersion.isNotBlank() && sourceHash.matches(Regex("[a-f0-9]{64}")))
    }

    internal fun matchesSource(source: ResearchSource): Boolean = source.id == sourceId &&
        capturedResearchVersion(source) == sourceVersion &&
        researchHash(source.passage) == sourceHash

    internal fun requestBudget() = ResearchModelRequestBudget(outputTokens, inputTokens, bounded)
}
