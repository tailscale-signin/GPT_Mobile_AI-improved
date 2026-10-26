package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse

/** A labelled, incomplete local compilation. Never masquerades as successful model synthesis. */
internal fun deterministicCombinedFallback(sources: List<CombinedModelResponse>, maxCharacters: Int, maximumWords: Int?): String? {
    val bodies = sources.filter { it.content.isNotBlank() }
    if (bodies.isEmpty()) return null
    val output = buildString {
        append("## Retained contributions\n\nThe final writer could not deliver an answer. These original contributions are unverified and may disagree. Resume final synthesis to combine them.\n")
        bodies.forEachIndexed { index, source ->
            append("\n### Contribution ${index + 1}\n\n")
            append(source.content)
            append('\n')
        }
    }
    // All-or-nothing: no silently dropped sources or clipped claims. The original
    // sources remain available in tabs when the user's explicit limit cannot fit.
    return output.takeIf { it.length <= maxCharacters && (maximumWords == null || visibleWordCount(it) <= maximumWords) }
}
