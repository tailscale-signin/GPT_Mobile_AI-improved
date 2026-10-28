package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse

/** A labelled recovery message. Source responses stay in their own contributor records and tabs. */
internal fun deterministicCombinedFallback(sources: List<CombinedModelResponse>, maxCharacters: Int, maximumWords: Int?): String? {
    if (sources.none { it.content.isNotBlank() }) return null
    val output = "A unified answer could not be completed. The original responses remain available in their contributor tabs. Retry synthesis to combine and check them."
    // Never turn raw model responses into the answer bubble. Keep source material
    // intact in contributor records so a later synthesis can use the full corpus.
    return output.takeIf { it.length <= maxCharacters && (maximumWords == null || visibleWordCount(it) <= maximumWords) }
}
