package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse

/**
 * Last-resort deterministic source preservation after editorial synthesis and repair.
 * These are unverified source excerpts, not an endorsement or an inferred fact merge.
 * This function is all-or-nothing and never exceeds the delivery character budget.
 */
internal fun appendMissingCombinedPassages(
    draft: String,
    missing: List<CombinedContentCoverage.Unit>,
    sources: List<CombinedModelResponse>,
    maxCharacters: Int
): String? {
    if (missing.isEmpty()) return draft
    if (draft.length >= maxCharacters) return null
    val names = sources.associate { it.platformUid to it.platformName.replace(Regex("[\\r\\n#*`\\[\\]<>]"), "").trim().take(80) }
    val tail = StringBuilder(
        "\n\n## Additional source details\n\n" +
            "The passages below were not fully integrated by the synthesis model. " +
            "They are retained from individual AI profiles, not independently verified facts.\n\n"
    )
    for (unit in missing) {
        val profiles = unit.profileUids.sorted().joinToString(", ") { uid -> names[uid]?.takeIf(String::isNotBlank) ?: "Profile" }
        val passage = "**From $profiles:**\n\n${unit.text}\n\n"
        if (draft.length.toLong() + tail.length + passage.length > maxCharacters.toLong()) return null
        tail.append(passage)
    }
    return (draft + tail.toString()).takeIf { it.length <= maxCharacters }
}
