package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.containsLocalOnlyMemory
import dev.chungjungsoo.gptmobile.util.isAssistantErrorMessage

/** Build from the latest candidate, never a historical revision selected for inspection. */
internal fun combinedSynthesisInputs(
    responses: List<MessageV2>,
    runs: Map<String, AgentRun>,
    names: Map<String, String>,
    models: Map<String, String>,
    currentRequest: String? = null
): List<CombinedModelResponse>? {
    if (responses.any { message ->
            val status = runs[message.currentRunId]?.status
            status in setOf(AgentRunStatus.QUEUED, AgentRunStatus.RUNNING) ||
                (message.currentRunId != null && status == null) ||
                (message.currentRunId == null && message.content.isBlank())
        }
    ) {
        return null
    }
    val synthesis = responses.firstOrNull { it.isCombinedSynthesis() }
    val sources = responses.mapNotNull { message ->
        val uid = message.platformType ?: return@mapNotNull null
        val original = message.combinedSources.firstOrNull { it.platformUid == uid }
        val originalContent = original?.content ?: message.content
        val content = currentRequest?.let { currentRequestContribution(it, originalContent) } ?: sanitizeContribution(originalContent)
        if (isAssistantErrorMessage(content)) return@mapNotNull null
        // The synthesis run's status describes the editor, not the preserved contributor.
        val status = if (message.isCombinedSynthesis() && original != null) {
            original.completionStatus
        } else {
            runs[message.currentRunId]?.status ?: original?.completionStatus ?: AgentRunStatus.COMPLETED
        }
        val assessment = CombinedContributionUsability.assess(status, content, allowPartial = true)
        if (!assessment.admitted) return@mapNotNull null
        val receipt = message.timeline.lastOrNull { it.researchDeliveryReceipt != null }?.researchDeliveryReceipt
        val disposition = when (receipt?.reportValidity) {
            dev.chungjungsoo.gptmobile.data.research.delivery.ResearchReportValidity.ACCEPTED -> dev.chungjungsoo.gptmobile.data.database.entity.ContributionDisposition.ACCEPTED_REPORT
            dev.chungjungsoo.gptmobile.data.research.delivery.ResearchReportValidity.EVIDENCE_ONLY -> dev.chungjungsoo.gptmobile.data.database.entity.ContributionDisposition.EVIDENCE_ONLY
            dev.chungjungsoo.gptmobile.data.research.delivery.ResearchReportValidity.INCOMPLETE, dev.chungjungsoo.gptmobile.data.research.delivery.ResearchReportValidity.REJECTED -> dev.chungjungsoo.gptmobile.data.database.entity.ContributionDisposition.USABLE_PARTIAL
            null -> null
        }
        CombinedModelResponse(
            platformUid = uid,
            platformName = names[uid] ?: original?.platformName ?: "AI",
            modelName = models[uid] ?: original?.modelName.orEmpty(),
            content = content,
            completionStatus = status,
            qualityScore = assessment.structuralUsability,
            structuralUsability = assessment.structuralUsability,
            sourceIds = original?.sourceIds.orEmpty(),
            evidenceRefs = original?.evidenceRefs.orEmpty(),
            localOnly = original?.localOnly == true || message.containsLocalOnlyMemory(),
            attemptId = original?.attemptId ?: message.currentRunId,
            createdAt = original?.createdAt ?: message.createdAt,
            sourceDigest = dev.chungjungsoo.gptmobile.data.research.researchHash(content),
            ancestryIds = original?.ancestryIds.orEmpty(),
            rawContentDigest = original?.rawContentDigest?.takeIf(String::isNotBlank) ?: dev.chungjungsoo.gptmobile.data.research.researchHash(originalContent),
            disposition = disposition ?: original?.disposition?.takeUnless { it == dev.chungjungsoo.gptmobile.data.database.entity.ContributionDisposition.LEGACY_UNKNOWN }
                ?: if (status == AgentRunStatus.COMPLETED) dev.chungjungsoo.gptmobile.data.database.entity.ContributionDisposition.ACCEPTED_REPORT else dev.chungjungsoo.gptmobile.data.database.entity.ContributionDisposition.USABLE_PARTIAL
        )
    }
    if (sources.isEmpty()) return null
    // A persisted editor owns the frozen corpus, including after failure or completion.
    // Late helper updates require explicit user recovery; never silently replace the final answer.
    if (synthesis != null) return null
    return sources
}
