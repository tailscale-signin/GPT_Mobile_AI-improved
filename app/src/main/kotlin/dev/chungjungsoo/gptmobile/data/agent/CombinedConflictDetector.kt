package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse

/** Candidates for editorial comparison, NOT established factual contradictions. */
internal data class CombinedConflictCandidate(
    val leftUid: String,
    val rightUid: String,
    val leftText: String,
    val rightText: String,
    val kind: String
)

internal data class CombinedConflictScan(
    val candidates: List<CombinedConflictCandidate>,
    val inspectedPairs: Long,
    val complete: Boolean
)

/** Do not treat agreement among models as independent external evidence. */
internal object CombinedConflictDetector {
    fun scan(sources: List<CombinedModelResponse>): CombinedConflictScan {
        data class Claim(
            val uid: String,
            val text: String,
            val fingerprint: CombinedClaimMatcher.Fingerprint
        )
        val claims = sources.flatMap { source ->
            CombinedContentCoverage.sentences(ConversationSubject.withoutMetadata(source.content))
                .filter { it.length >= 16 }
                .map { Claim(source.platformUid, it, CombinedClaimMatcher.fingerprint(it)) }
        }
        // Full source responses are passed unchanged to the editor. This scan is a hint
        // only; on oversized corpora we explicitly report that scanning was incomplete.
        if (claims.size > 1500) return CombinedConflictScan(emptyList(), 0, false)
        val conflicts = mutableListOf<CombinedConflictCandidate>()
        var scanned = 0L
        var overflow = false
        claims.forEachIndexed { index, first ->
            for (other in index + 1 until claims.size) {
                val second = claims[other]
                if (first.uid == second.uid) continue
                scanned++
                if (CombinedClaimMatcher.possibleConflict(first.fingerprint, second.fingerprint)) {
                    if (conflicts.size < 256) {
                        val kind = if (first.fingerprint.negative != second.fingerprint.negative) "POLARITY" else "NUMERIC"
                        conflicts += CombinedConflictCandidate(first.uid, second.uid, first.text, second.text, kind)
                    } else {
                        overflow = true
                    }
                }
            }
        }
        return CombinedConflictScan(conflicts, scanned, !overflow)
    }
}
