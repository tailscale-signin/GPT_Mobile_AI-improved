package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveRunId
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveTimeline
import dev.chungjungsoo.gptmobile.data.research.researchHash

/** The persisted Combined revision is shared dialogue; contributor drafts remain provenance. */
internal fun List<MessageV2>.canonicalCombinedAssistant(): MessageV2? {
    val combined = filter { it.combinedSources.isNotEmpty() && it.effectiveRunId()?.startsWith("combined-synthesis:") == true }
    if (combined.isEmpty()) return null
    require(combined.size == 1) { "Conflicting Combined history owners; reload before sending." }
    val selected = combined.single()
    val content = selected.effectiveContent()
    if (content.isBlank()) error("The previous Combined answer has no usable context. Finish or retry that turn before continuing.")
    val quality = selected.effectiveTimeline().lastOrNull { it.combinedQuality != null }?.combinedQuality
    if (quality != null && quality.answerDigest.isNotBlank()) {
        require(quality.answerDigest == researchHash(content)) { "The selected Combined history revision changed; reload before sending." }
    }
    // Partial context is useful but cannot masquerade as a final accepted answer.
    return if (quality?.complete == true) {
        selected
    } else {
        selected.copy(
            content = "$content\n\n[Previous Combined answer is partial; unfinished work remains.]",
            activeRevisionIndex = dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST
        )
    }
}
