package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.ContributionDisposition
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.containsLocalOnlyMemory
import dev.chungjungsoo.gptmobile.data.research.delivery.ResearchReportValidity
import dev.chungjungsoo.gptmobile.data.research.researchHash
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
        // Validate control markup before sanitizing, which may retain an innocent preamble.
        if (dev.chungjungsoo.gptmobile.data.agent.DSML_CONTROL_MARKUP.containsMatchIn(originalContent)) return@mapNotNull null
        val content = currentRequest?.let { CombinedContributionQuality.currentRequestContribution(it, originalContent) } ?: CombinedContributionQuality.sanitizeContribution(originalContent)
        if (isAssistantErrorMessage(content)) return@mapNotNull null
        // The synthesis run's status describes the editor, not the preserved contributor.
        val status = if (message.isCombinedSynthesis() && original != null) {
            original.completionStatus
        } else {
            runs[message.currentRunId]?.status ?: original?.completionStatus ?: AgentRunStatus.COMPLETED
        }
        val modelName = models[uid] ?: original?.modelName.orEmpty()
        val demo = uid == "builtin-free-porchlm" || names[uid]?.contains("PorchLM", true) == true || modelName.contains("porch", true)
        val sourceTimeline = if (original?.attemptId != null && original.attemptId != message.currentRunId) message.revisions.firstOrNull { it.runId == original.attemptId }?.timeline.orEmpty() else message.timeline
        val generation = sourceTimeline.lastOrNull { it.generation != null }?.generation
        val finishReason = generation?.finishReason ?: original?.nativeFinishReason
        val assessment = CombinedContributionUsability.assess(status, content, allowPartial = true, request = currentRequest, demo = demo, finishReason = finishReason, outputTokens = generation?.outputTokens, outputAllowance = generation?.outputAllowance ?: 256.takeIf { uid == "builtin-free-ai_horde" })
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("CombinedScreening", "CONTRIBUTION_SCREENED · uid=$uid · admitted=${assessment.admitted} · reason=${assessment.reason} · relevance=${assessment.relevance} · coherence=${assessment.coherence} · completeness=${assessment.completeness}")
        if (!assessment.admitted) return@mapNotNull null
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
            disposition = if (assessment.usablePartial) ContributionDisposition.USABLE_PARTIAL else combinedContributionDisposition(message, original, status),
            contentCompleteness = assessment.completeness,
            factualSupport = original?.factualSupport ?: "UNVERIFIED",
            nativeFinishReason = finishReason
        )
    }
    if (sources.isEmpty()) return null
    // A persisted editor owns the frozen corpus, including after failure or completion.
    // Late helper updates require explicit user recovery; never silently replace the final answer.
    if (synthesis != null) return null
    return sources
}

/** An editor's receipt cannot validate its preserved contributor, or a later appended answer. */
internal fun combinedContributionDisposition(message: MessageV2, original: CombinedModelResponse?, status: String): ContributionDisposition {
    val preserved = original?.attemptId?.let { attempt -> message.revisions.firstOrNull { it.runId == attempt } }
    val preservedContributor = original != null && message.currentRunId != original.attemptId
    val timeline = if (preservedContributor) preserved?.timeline.orEmpty() else message.timeline
    val rawText = if (preservedContributor) preserved?.content ?: original!!.content else message.content
    val receipt = timeline.lastOrNull { it.researchDeliveryReceipt != null }?.researchDeliveryReceipt
    if (receipt != null) {
        val originalDigest = original?.rawContentDigest?.takeIf(String::isNotBlank)
        if (!receipt.matchesRevision(rawText) || originalDigest != null && originalDigest != researchHash(rawText)) {
            return ContributionDisposition.USABLE_PARTIAL
        }
        return when (receipt.reportValidity) {
            ResearchReportValidity.ACCEPTED -> ContributionDisposition.ACCEPTED_REPORT
            ResearchReportValidity.EVIDENCE_ONLY -> ContributionDisposition.EVIDENCE_ONLY
            ResearchReportValidity.INCOMPLETE, ResearchReportValidity.REJECTED -> ContributionDisposition.USABLE_PARTIAL
        }
    }
    original?.disposition?.takeUnless { it == ContributionDisposition.LEGACY_UNKNOWN }?.let { return it }
    // A known research answer without its own matching receipt is still useful evidence,
    // but a successful transport does not supply the missing report validation.
    if (message.timeline.any { it.researchDeliveryReceipt != null } || preserved?.timeline?.any { it.researchDeliveryReceipt != null } == true) {
        return ContributionDisposition.USABLE_PARTIAL
    }
    return if (status == AgentRunStatus.COMPLETED) ContributionDisposition.ACCEPTED_REPORT else ContributionDisposition.USABLE_PARTIAL
}
