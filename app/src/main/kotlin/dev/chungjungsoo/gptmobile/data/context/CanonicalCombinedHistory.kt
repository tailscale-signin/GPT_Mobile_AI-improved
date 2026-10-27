package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveCombinedSources
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveRunId
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveTimeline
import dev.chungjungsoo.gptmobile.data.research.researchHash
import dev.chungjungsoo.gptmobile.util.isAssistantErrorMessage

/** Detect Combined by lifecycle identity, never by matching user-visible notice text. */
internal fun List<MessageV2>.hasCombinedHistory(): Boolean = any {
    // A selected retained contributor revision is not a delivered Combined final.
    // Keep the turn's raw identity even when its visible fallback has another run ID.
    it.currentRunId?.startsWith("combined-synthesis:") == true ||
        it.combinedSources.isNotEmpty() ||
        it.timeline.any { item -> item.combinedTurn != null } ||
        it.effectiveRunId()?.startsWith("combined-synthesis:") == true ||
        it.effectiveTimeline().any { item -> item.combinedTurn != null }
}

/** Only a selected delivered revision is assistant prose. Delivery and provenance stay typed. */
internal fun List<MessageV2>.canonicalCombinedAssistant(): MessageV2? {
    val combined = filter { it.effectiveRunId()?.startsWith("combined-synthesis:") == true }
    val markers = flatMap { it.effectiveTimeline().mapNotNull { item -> item.combinedTurn } }
    if (combined.isEmpty()) {
        require(markers.map { it.rootBudgetId to it.requestedPolicyDigest }.distinct().size <= 1) {
            "Conflicting accepted Combined turn identities; reload before sending."
        }
        return null
    }
    require(combined.size == 1) { "Conflicting Combined history owners; reload before sending." }
    val selected = combined.single()
    val content = selected.effectiveContent()
    val timeline = selected.effectiveTimeline()
    val quality = timeline.lastOrNull { it.combinedQuality != null }?.combinedQuality
    if (quality != null && quality.answerDigest.isNotBlank()) {
        require(quality.answerDigest == researchHash(content)) { "The selected Combined history revision changed; reload before sending." }
    }
    if (timeline.any { it.combinedFallback?.kind == "EVIDENCE_ONLY" } || quality?.status in setOf("FALLBACK_SOURCE_COMPILATION", "SYNTHESIS_UNAVAILABLE")) return null
    if (content.isBlank() || isAssistantErrorMessage(content)) return null
    // A partial or cancelled delivery may contain useful committed prose. Preserve those exact
    // bytes and its quality report; never invent an assistant answer or append internal notices.
    return selected.copy(
        content = content,
        thoughts = "",
        timeline = timeline,
        combinedSources = selected.effectiveCombinedSources(),
        currentRunId = selected.effectiveRunId(),
        activeRevisionIndex = ACTIVE_REVISION_LATEST
    )
}
