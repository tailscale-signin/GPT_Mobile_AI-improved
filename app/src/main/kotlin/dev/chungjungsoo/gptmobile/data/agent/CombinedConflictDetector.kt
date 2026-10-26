package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationTrace

/** Candidates for editorial comparison, NOT established factual contradictions. */
internal data class CombinedConflictCandidate(
    val leftUid: String,
    val rightUid: String,
    val leftText: String,
    val rightText: String,
    val kind: String,
    val leftClaimId: String = "",
    val rightClaimId: String = ""
)

internal data class CombinedConflictScan(
    val candidates: List<CombinedConflictCandidate>,
    val inspectedPairs: Long,
    val complete: Boolean
)

/** Do not treat agreement among models as independent external evidence. */
internal object CombinedConflictDetector {
    fun scan(sources: List<CombinedModelResponse>, maxPairChecks: Long = 1_000_000L, maxCandidates: Int = Int.MAX_VALUE): CombinedConflictScan =
        CombinedOperationTrace.measure("conflict_scan") { scanClaims(sources, maxPairChecks, maxCandidates) }

    private fun scanClaims(sources: List<CombinedModelResponse>, maxPairChecks: Long, maxCandidates: Int): CombinedConflictScan {
        data class Claim(
            val uid: String,
            val text: String,
            val fingerprint: CombinedClaimMatcher.Fingerprint,
            val id: String
        )
        val claims = sources.flatMapIndexed { sourceIndex, source ->
            CombinedContentCoverage.sentences(CombinedContentCoverage.sourceCoverageText(source.content))
                .mapIndexed { index, text -> Claim(source.platformUid, text, CombinedClaimMatcher.fingerprint(text), "C${sourceIndex + 1}.${index + 1}") }
                .filter { it.text.length >= 16 }
        }
        val conflicts = mutableListOf<CombinedConflictCandidate>()
        // Clause parsing is independent of the comparison partner. Reuse it across
        // the scan instead of running normalization and regex extraction per pair.
        val clauseFingerprints = mutableMapOf<String, CombinedClaimMatcher.Fingerprint>()
        var scanned = 0L
        var overflow = false
        for ((index, first) in claims.withIndex()) {
            for (other in index + 1 until claims.size) {
                val second = claims[other]
                if (scanned >= maxPairChecks) return CombinedConflictScan(conflicts, scanned, false)
                scanned++
                if (CombinedClaimMatcher.possibleConflict(first.fingerprint, second.fingerprint, clauseFingerprints)) {
                    if (conflicts.size < maxCandidates) {
                        val kind = when {
                            first.fingerprint.qualifiers.outcome != second.fingerprint.qualifiers.outcome &&
                                "NULL_EFFECT" in setOf(first.fingerprint.qualifiers.outcome, second.fingerprint.qualifiers.outcome) -> "OUTCOME"
                            first.fingerprint.negative != second.fingerprint.negative -> "POLARITY"
                            first.fingerprint.qualifiers.causalStrength != second.fingerprint.qualifiers.causalStrength -> "CAUSAL_STRENGTH"
                            else -> "NUMERIC"
                        }
                        conflicts += CombinedConflictCandidate(first.uid, second.uid, first.text, second.text, kind, first.id, second.id)
                    } else {
                        overflow = true
                    }
                }
            }
        }
        return CombinedConflictScan(conflicts, scanned, !overflow)
    }
}
