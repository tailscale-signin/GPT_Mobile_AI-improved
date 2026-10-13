package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.text.BreakIterator
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
enum class ClaimSupportClass { EXCLUSIVE, SHARED, MIXED, CONFLICTING, UNSUPPORTED, UNKNOWN }

@Serializable
data class AttributionClaim(
    val startUtf16: Int,
    val endUtf16: Int,
    val textDigest: String,
    val comparisons: List<ProfileClaimComparison>
)

/** Compared IDs are receipts for all reviewed chunks, never a candidate-search shortlist. */
@Serializable
data class ProfileClaimComparison(
    val profileUid: String,
    val comparedSpanIds: List<String>,
    val supportSpanIds: List<String> = emptyList(),
    val contradictionSpanIds: List<String> = emptyList(),
    val uncertain: Boolean = false
)

@Serializable
data class SentenceAttributionProposal(
    val startUtf16: Int,
    val endUtf16: Int,
    val textDigest: String,
    val claims: List<AttributionClaim>
)

@Serializable
data class CombinedProvenanceSidecar(
    val version: Int = 1,
    val answerDigest: String,
    val corpusDigest: String,
    val parserVersion: String,
    val policyRevision: String,
    val verifierRevision: String,
    val sentences: List<SentenceAttributionProposal>
)

/** Supplied by the application qualification registry, never decoded from model output. */
data class AttributionVerifierQualification(
    val verifierRevision: String,
    val evaluationDigest: String,
    val emittedExclusiveLabels: Long,
    val correctExclusiveLabels: Long,
    val heldOutHumanLabels: Boolean
) {
    val qualified: Boolean get() = heldOutHumanLabels &&
        evaluationDigest.matches(Regex("[a-f0-9]{64}")) &&
        emittedExclusiveLabels > 0 &&
        correctExclusiveLabels in 0..emittedExclusiveLabels &&
        correctExclusiveLabels.toDouble() / emittedExclusiveLabels >= 0.99
}

data class ValidatedSentenceAttribution internal constructor(
    val startUtf16: Int,
    val endUtf16: Int,
    val supportClass: ClaimSupportClass,
    val supporterUids: Set<String>,
    val supportSpanIds: Set<String>,
    val comparedSpans: Long,
    val expectedSpans: Long,
    val warning: String?
) {
    val exclusiveProfileUid: String? get() = supporterUids.singleOrNull().takeIf { supportClass == ClaimSupportClass.EXCLUSIVE }
}

data class ValidatedCombinedProvenance internal constructor(
    val answerDigest: String,
    val corpusDigest: String,
    val parserVersion: String,
    val policyRevision: String,
    val sentences: List<ValidatedSentenceAttribution>,
    val warning: String? = null
)

/**
 * Model IDs, confidence and similarity are untrusted. Every colored sentence must cover
 * all substantive text, resolve immutable spans and compare every eligible competitor.
 * Semantic correctness additionally requires a separately qualified verifier revision.
 */
object CombinedProvenanceValidator {
    fun validate(
        answer: String,
        sources: List<CombinedModelResponse>,
        lineage: ContributionLineage,
        sidecar: CombinedProvenanceSidecar,
        parserVersion: String,
        policyRevision: String,
        qualification: AttributionVerifierQualification?,
        allowLocalOnly: Boolean,
        authorizedProfileUids: Set<String>
    ): ValidatedCombinedProvenance {
        val answerDigest = researchHash(answer)
        fun invalid(reason: String) = ValidatedCombinedProvenance(answerDigest, lineage.corpusDigest, parserVersion, policyRevision, emptyList(), reason)
        if (sidecar.version != 1 ||
            sidecar.answerDigest != answerDigest ||
            sidecar.corpusDigest != lineage.corpusDigest ||
            sidecar.parserVersion != parserVersion ||
            sidecar.policyRevision != policyRevision
        ) {
            return invalid("Answer, corpus, parser or permission revision changed")
        }
        val rebuilt = ContributionLineage.freeze(lineage.revisions.map { it.profileUid }, sources)
        if (rebuilt.corpusDigest != lineage.corpusDigest || rebuilt.spans != lineage.spans || rebuilt.revisions != lineage.revisions) {
            return invalid("Contribution revision or source locators changed")
        }
        val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(answer) }
        fun validRange(start: Int, end: Int) = start >= 0 && end > start && end <= answer.length && boundaries.isBoundary(start) && boundaries.isBoundary(end)
        val ordered = sidecar.sentences.sortedBy { it.startUtf16 }
        if (ordered.zipWithNext().any { (a, b) -> a.endUtf16 > b.startUtf16 } ||
            ordered.any {
                !validRange(it.startUtf16, it.endUtf16) || researchHash(answer.substring(it.startUtf16, it.endUtf16)) != it.textDigest
            }
        ) {
            return invalid("Invalid or overlapping sentence ranges")
        }
        val spansByProfile = lineage.spans.groupBy { it.profileUid }
        // Rebuilding above already checked each immutable body, range, byte offset and digest once.
        val permittedSpanIds = lineage.spans.filter { span ->
            span.profileUid in authorizedProfileUids && lineage.revisions.single { it.profileUid == span.profileUid }.let { allowLocalOnly || !it.localOnly }
        }.map { it.id }.toSet()
        val completeCorpus = lineage.revisions.isNotEmpty() &&
            lineage.revisions.all {
                it.eligibleForComparison &&
                    it.profileUid in authorizedProfileUids &&
                    (!it.localOnly || allowLocalOnly) &&
                    !spansByProfile[it.profileUid].isNullOrEmpty()
            }
        val semanticQualified = qualification?.qualified == true && qualification.verifierRevision == sidecar.verifierRevision
        val selected = lineage.revisions.map { it.profileUid }.toSet()
        val outcomes = ordered.map { sentence ->
            var warning: String? = null
            val claims = sentence.claims.sortedBy { it.startUtf16 }
            val expected = lineage.spans.size.toLong() * claims.size.toLong()
            var compared = 0L
            val supportingSpans = linkedSetOf<String>()
            val supporters = mutableListOf<Set<String>>()
            var conflict = false
            var uncertain = false
            val claimBoundsValid = claims.isNotEmpty() &&
                claims.all {
                    validRange(it.startUtf16, it.endUtf16) &&
                        it.startUtf16 >= sentence.startUtf16 &&
                        it.endUtf16 <= sentence.endUtf16 &&
                        researchHash(answer.substring(it.startUtf16, it.endUtf16)) == it.textDigest
                } &&
                claims.zipWithNext().none { (a, b) -> a.endUtf16 > b.startUtf16 }
            var cursor = sentence.startUtf16
            val claimsCoverSentence = claimBoundsValid &&
                claims.all { claim ->
                    val gap = answer.substring(cursor, claim.startUtf16)
                    cursor = claim.endUtf16
                    !gap.codePoints().anyMatch { Character.isLetterOrDigit(it) }
                } &&
                !answer.substring(cursor, sentence.endUtf16).codePoints().anyMatch { Character.isLetterOrDigit(it) }
            if (!claimsCoverSentence) warning = "Claims do not cover the complete sentence"
            if (!completeCorpus) warning = "A selected contribution is missing, partial or unauthorized"
            if (!semanticQualified) warning = warning ?: "Semantic verifier has not qualified for this revision"
            if (claimsCoverSentence) {
                claims.forEach { claim ->
                    val claimSupporters = linkedSetOf<String>()
                    if (claim.comparisons.map { it.profileUid }.toSet() != selected || claim.comparisons.size != selected.size) {
                        warning = "Missing or duplicate profile comparison"
                    }
                    claim.comparisons.forEach { comparison ->
                        val required = spansByProfile[comparison.profileUid].orEmpty().map { it.id }.toSet()
                        val reviewed = comparison.comparedSpanIds.toSet()
                        if (comparison.profileUid !in selected || reviewed != required || reviewed.size != comparison.comparedSpanIds.size) {
                            warning = "Comparison does not cover the complete contribution"
                        }
                        compared += reviewed.intersect(required).size
                        val evidence = comparison.supportSpanIds + comparison.contradictionSpanIds
                        val evidenceValid = comparison.profileUid in authorizedProfileUids &&
                            evidence.all { it in required && it in permittedSpanIds } &&
                            evidence.toSet().size == evidence.size
                        if (!evidenceValid) warning = "Unresolvable or duplicate support evidence"
                        if (evidenceValid && comparison.supportSpanIds.isNotEmpty()) {
                            claimSupporters += comparison.profileUid
                            supportingSpans += comparison.supportSpanIds
                        }
                        conflict = conflict || comparison.contradictionSpanIds.isNotEmpty()
                        uncertain = uncertain || comparison.uncertain
                    }
                    supporters += claimSupporters
                }
            }
            val supportClass = when {
                warning != null || uncertain -> ClaimSupportClass.UNKNOWN
                conflict -> ClaimSupportClass.CONFLICTING
                supporters.any { it.isEmpty() } -> ClaimSupportClass.UNSUPPORTED
                supporters.distinct().size != 1 -> ClaimSupportClass.MIXED
                supporters.singleOrNull()?.size == 1 || supporters.all { it.size == 1 } -> ClaimSupportClass.EXCLUSIVE
                else -> ClaimSupportClass.SHARED
            }
            ValidatedSentenceAttribution(
                sentence.startUtf16,
                sentence.endUtf16,
                supportClass,
                supporters.flatten().toSet(),
                supportingSpans,
                compared,
                expected,
                warning ?: "Uncertain semantic comparison".takeIf { uncertain }
            )
        }
        return ValidatedCombinedProvenance(answerDigest, lineage.corpusDigest, parserVersion, policyRevision, outcomes)
    }
}
