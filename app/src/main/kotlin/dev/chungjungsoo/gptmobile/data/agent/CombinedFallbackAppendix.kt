package dev.chungjungsoo.gptmobile.data.agent

/** Stable, explicit boundary for debug-only recovery excerpts. */
internal const val COMBINED_RECOVERY_MARKER = "<!-- gptmobile:combined-recovery:v1 -->"

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
    // Compatibility API for old callers. Recovery excerpts belong in audit metadata,
    // never in stored, displayed, copied or exported answer prose.
    return draft.takeIf { missing.isEmpty() && it.length <= maxCharacters }
}
