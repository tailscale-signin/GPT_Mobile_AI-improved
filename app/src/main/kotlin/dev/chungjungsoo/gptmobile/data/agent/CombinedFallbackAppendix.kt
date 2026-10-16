package dev.chungjungsoo.gptmobile.data.agent

/**
 * Last-resort deterministic source preservation after editorial synthesis and repair.
 * These are unverified source excerpts, not an endorsement or an inferred fact merge.
 * This function is all-or-nothing and never exceeds the delivery character budget.
 */
internal fun appendMissingCombinedPassages(
    draft: String,
    missing: List<CombinedContentCoverage.Unit>,
    maxCharacters: Int
): String? {
    if (missing.isEmpty()) return draft
    if (draft.length >= maxCharacters) return null
    // Retain every missing fact without displaying source-profile names. Provenance
    // remains in the frozen combined sources for colour mapping and Debug inspection.
    val tail = StringBuilder("\n\n## Further details\n\n")
    for (unit in missing) {
        val passage = "${unit.text}\n\n"
        if (draft.length.toLong() + tail.length + passage.length > maxCharacters.toLong()) return null
        tail.append(passage)
    }
    return (draft + tail.toString()).takeIf { it.length <= maxCharacters }
}
