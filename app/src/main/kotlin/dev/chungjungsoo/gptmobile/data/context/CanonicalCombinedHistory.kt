package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveCombinedSources
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveRunId
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveTimeline
import dev.chungjungsoo.gptmobile.data.research.researchHash
import dev.chungjungsoo.gptmobile.util.isAssistantErrorMessage

/** Contributor drafts and failed attempts are not statements delivered as the shared Combined answer. */
internal fun List<MessageV2>.canonicalCombinedAssistant(): MessageV2? {
    val combined = filter { it.effectiveRunId()?.startsWith("combined-synthesis:") == true }
    val markers = flatMap { it.effectiveTimeline().mapNotNull { item -> item.combinedTurn } }
    if (combined.isEmpty()) {
        if (markers.isEmpty()) return null // Legacy ordinary multi-model conversations retain their existing history path.
        require(markers.map { it.rootBudgetId to it.requestedPolicyDigest }.distinct().size == 1) { "Conflicting accepted Combined turn identities; reload before sending." }
        val available = filter { it.effectiveContent().isNotBlank() && !isAssistantErrorMessage(it.effectiveContent()) }
        val status = when {
            markers.any { it.deliveryStatus in setOf("CANCELLED", "CANCELED", "CANDIDATE_CANCELED", "CANDIDATE_CANCELLED") } -> "cancelled"
            markers.any { it.deliveryStatus in setOf("FAILED", "CANDIDATE_FAILED", "MERGE_UNAVAILABLE") } -> "unavailable"
            markers.any { it.deliveryStatus in setOf("INTERRUPTED", "CANDIDATE_INTERRUPTED") } -> "interrupted"
            else -> "not delivered"
        }
        val text = "[Combined turn: the merged final was $status. ${available.size} individual draft(s) were available; they are candidate material, not a delivered Combined answer. Preserve this distinction in recaps.]"
        return first().copy(content = text, thoughts = "", combinedSources = emptyList(), activeRevisionIndex = ACTIVE_REVISION_LATEST)
    }
    require(combined.size == 1) { "Conflicting Combined history owners; reload before sending." }
    val selected = combined.single()
    val content = selected.effectiveContent()
    val quality = selected.effectiveTimeline().lastOrNull { it.combinedQuality != null }?.combinedQuality
    if (quality != null && quality.answerDigest.isNotBlank()) {
        require(quality.answerDigest == researchHash(content)) { "The selected Combined history revision changed; reload before sending." }
    }
    val status = quality?.deliveredScopeStatus?.takeIf(String::isNotBlank)
        ?: if (quality?.complete == true) "COMPLETED" else "PARTIAL"
    val ancestry = selected.effectiveCombinedSources().flatMap { source ->
        source.ancestryIds.ifEmpty { setOf("contribution:${source.sourceDigest.ifBlank { researchHash(source.content) }}") }
    }.toSet()
    val historyNote = when {
        content.isBlank() || status in setOf("FAILED", "CANCELLED", "CANCELED") -> "[Previous Combined turn: no final answer was delivered ($status). Original contributor attempts are preserved separately; do not recap a candidate as delivered.]"
        quality?.correctionRequired == true -> "[Previous Combined draft requires correction. Its earlier support assessment is invalid. Do not repeat affected assertions as established facts; use the corrected revision or explicitly describe the historical error.]"
        status == "PARTIAL" || status in setOf("CHECKING", "WRITING") -> "[Previous Combined answer is partial; unfinished work remains.]"
        status == "COMPLETED_WITH_LIMITS" -> "[Previous Combined answer was delivered with stated limits. Verification remains ${quality?.verificationStatus ?: "UNVERIFIED"}; delivery is not factual certification.]"
        else -> ""
    }
    val correctionNotes = quality?.decisions.orEmpty().filter { it.disposition in setOf("CORRECTED", "OMITTED_UNSUPPORTED") }.map { decision ->
        val replacement = decision.finalSpans.mapNotNull { span ->
            if (span.startUtf16 >= 0 && span.endUtf16 in (span.startUtf16 + 1)..content.length && researchHash(content.substring(span.startUtf16, span.endUtf16)) == span.textDigest) content.substring(span.startUtf16, span.endUtf16) else null
        }.joinToString(" ")
        "${decision.claimId}: ${decision.reason}" + replacement.takeIf(String::isNotBlank)?.let { " Corrected text: $it" }.orEmpty()
    }
    val ancestryNote = if (ancestry.isEmpty()) "" else "[Evidence ancestry: ${ancestry.sorted().joinToString(",")}. Inherited repetitions retain these origins and are not independent corroboration.]"
    val notes = listOf(historyNote, ancestryNote) + correctionNotes.takeIf { it.isNotEmpty() }?.let { listOf("[Claim corrections: ${it.joinToString("; ")}]") }.orEmpty()
    val rendered = (listOf(content.takeIf(String::isNotBlank).orEmpty()) + notes).filter(String::isNotBlank).joinToString("\n\n")
    return if (rendered == content && selected.activeRevisionIndex == ACTIVE_REVISION_LATEST) selected else selected.copy(content = rendered, activeRevisionIndex = ACTIVE_REVISION_LATEST)
}
