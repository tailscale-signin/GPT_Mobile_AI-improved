package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable

@Serializable
data class CombinedSupportEdge(
    val id: String,
    val claimId: String,
    val evidenceId: String,
    val excerptId: String,
    val excerptDigest: String,
    val relation: String,
    val applicability: List<String> = emptyList(),
    val method: String = CombinedEvidenceVerifier.METHOD,
    val limitations: List<String> = listOf("Conservative text and applicability checks; not independent factual certification")
)

/** Bound to the actual visible revision and retained excerpts, never accepted from the editor's JSON. */
@Serializable
data class CombinedVerificationReceipt(
    val id: String,
    val claimId: String,
    val answerDigest: String,
    val revisionId: String,
    val finalSpan: CombinedCoverageLink,
    val evidenceIds: Set<String>,
    val excerptDigests: Map<String, String>,
    val result: String,
    val method: String = CombinedEvidenceVerifier.METHOD,
    val limitations: List<String> = listOf("Checks supplied passages only; source accuracy and general entailment remain unverified"),
    val physicalAttemptIds: Set<String> = emptySet(),
    val citedEvidenceIds: Set<String> = emptySet(),
    val toolEventIds: Set<String> = emptySet()
)

internal data class CombinedEvidenceAssessment(
    val result: String,
    val edges: List<CombinedSupportEdge>,
    val issues: List<String>,
    val material: Boolean,
    val citedEvidenceIds: Set<String> = emptySet(),
    val toolEventIds: Set<String> = emptySet()
)

/** Deterministic vetoes complement the verifier; lexical similarity never becomes a truth score. */
internal object CombinedEvidenceVerifier {
    const val METHOD = "RETAINED_PASSAGE_APPLICABILITY_V1"

    fun assess(claimId: String, proposition: String, ledger: CombinedEvidenceLedger, citedIds: Set<String> = emptySet()): CombinedEvidenceAssessment {
        val target = CombinedClaimMatcher.fingerprint(proposition)
        val edges = mutableListOf<CombinedSupportEdge>()
        val issues = linkedSetOf<String>()
        var textualSupport = false
        var contradiction = false
        var partial = false
        ledger.records.forEach { record ->
            record.retainedExcerpts.forEach excerptLoop@{ excerpt ->
                if (!excerpt.readable || researchHash(excerpt.text) != excerpt.digest) return@excerptLoop
                var relation = "DOES_NOT_ADDRESS"
                val limitations = linkedSetOf<String>()
                CombinedContentCoverage.sentences(excerpt.text).forEach sentenceLoop@{ sentence ->
                    val source = CombinedClaimMatcher.fingerprint(sentence)
                    val overlap = source.terms.intersect(target.terms).size
                    val related = overlap >= 3 && overlap.toDouble() / minOf(source.terms.size, target.terms.size).coerceAtLeast(1) >= 0.55
                    val exact = CombinedContentCoverage.normalized(sentence) == CombinedContentCoverage.normalized(proposition)
                    if (!related && !exact) return@sentenceLoop
                    val applicability = CombinedClaimSemantics.applicabilityConflicts(source.qualifiers, target.qualifiers)
                    if (applicability.isNotEmpty()) {
                        // An explicitly cited study about another route/population cannot substantiate this claim.
                        if (record.id in citedIds) {
                            if (relation != "CONTRADICTS") relation = "PARTIAL_SUPPORT"
                            limitations += applicability
                            issues += applicability.map { "$it:$claimId:${record.id}" }
                            partial = true
                        }
                        return@sentenceLoop
                    }
                    if (CombinedClaimMatcher.possibleConflict(source, target)) {
                        relation = "CONTRADICTS"
                        contradiction = true
                        issues += "SUPPLIED_PASSAGE_CONFLICT:$claimId:${record.id}"
                        return@sentenceLoop
                    }
                    val losses = CombinedClaimSemantics.lostQualifiers(source.qualifiers, target.qualifiers)
                    if (losses.isNotEmpty()) {
                        if (relation != "CONTRADICTS") relation = "PARTIAL_SUPPORT"
                        limitations += losses
                        if (record.id in citedIds || CombinedClaimSemantics.consequential(proposition)) issues += losses.map { "$it:$claimId:${record.id}" }
                        partial = true
                    } else if (exact ||
                        CombinedClaimMatcher.match(source, target) >= CombinedClaimMatcher.Match.PARAPHRASE &&
                        CombinedClaimMatcher.match(target, source) >= CombinedClaimMatcher.Match.PARAPHRASE
                    ) {
                        if (relation == "DOES_NOT_ADDRESS") relation = "TEXT_SUPPORT"
                        textualSupport = true
                    }
                }
                if (relation != "DOES_NOT_ADDRESS" || record.id in citedIds) {
                    edges += CombinedSupportEdge(
                        researchHash("$claimId|${record.id}|${excerpt.id}|${excerpt.digest}|$relation"),
                        claimId,
                        record.id,
                        excerpt.id,
                        excerpt.digest,
                        relation,
                        applicability = limitations.toList()
                    )
                }
            }
        }
        if (citedIds.any { id -> ledger.records.none { it.id == id } }) issues += "UNKNOWN_SOURCE_REFERENCE:$claimId"
        if (citedIds.any { id -> ledger.records.firstOrNull { it.id == id }?.readable == false }) issues += "DISCOVERY_CANNOT_SUPPORT_OUTCOME:$claimId"
        val result = when {
            contradiction -> "CONFLICTING_PASSAGES"
            partial -> "PARTIAL_SUPPORT"
            textualSupport -> "TEXT_SUPPORTED_WITH_LIMITS"
            citedIds.isNotEmpty() -> "CITED_UNVERIFIED"
            else -> "UNVERIFIED"
        }
        val consulted = edges.mapTo(linkedSetOf()) { it.evidenceId } + citedIds
        return CombinedEvidenceAssessment(result, edges, issues.toList(), contradiction || issues.any { it.substringBefore(':') in setOf("INTERVENTION_ROUTE_MISMATCH", "POPULATION_MISMATCH", "NULL_OR_UNESTABLISHED_OUTCOME_CHANGED", "CAUSAL_STRENGTH_INCREASED") }, citedIds, ledger.records.filter { it.id in consulted }.flatMapTo(linkedSetOf()) { it.toolEventIds })
    }

    fun receipt(answer: String, revisionId: String, claimId: String, span: CombinedCoverageLink, assessment: CombinedEvidenceAssessment): CombinedVerificationReceipt {
        val answerDigest = researchHash(answer)
        return CombinedVerificationReceipt(
            researchHash("$answerDigest|$revisionId|$claimId|${span.textDigest}|${assessment.result}"), claimId, answerDigest, revisionId, span,
            assessment.edges.mapTo(linkedSetOf()) { it.evidenceId } + assessment.citedEvidenceIds, assessment.edges.associate { it.excerptId to it.excerptDigest }, assessment.result,
            limitations = (assessment.edges.flatMap { it.limitations } + assessment.issues + "No whole-answer factual verification is claimed").distinct(),
            citedEvidenceIds = assessment.citedEvidenceIds, toolEventIds = assessment.toolEventIds
        )
    }

    fun validateReceipt(answer: String, revisionId: String, ledger: CombinedEvidenceLedger, receipt: CombinedVerificationReceipt): Boolean {
        val span = receipt.finalSpan
        if (receipt.method != METHOD ||
            receipt.answerDigest != researchHash(answer) ||
            receipt.revisionId != revisionId ||
            span.startUtf16 < 0 ||
            span.endUtf16 <= span.startUtf16 ||
            span.endUtf16 > answer.length ||
            researchHash(answer.substring(span.startUtf16, span.endUtf16)) != span.textDigest
        ) {
            return false
        }
        val evidence = ledger.records.filter { it.id in receipt.evidenceIds }
        if (evidence.size != receipt.evidenceIds.size) return false
        val excerpts = evidence.flatMap { it.retainedExcerpts }.associateBy { it.id }
        if (receipt.excerptDigests.any { (id, digest) -> excerpts[id]?.let { it.digest == digest && researchHash(it.text) == digest && it.readable } != true }) return false
        if (!receipt.evidenceIds.containsAll(receipt.citedEvidenceIds)) return false
        val rechecked = assess(receipt.claimId, answer.substring(span.startUtf16, span.endUtf16), ledger, receipt.citedEvidenceIds)
        return rechecked.result == receipt.result && receipt.limitations.isNotEmpty()
    }
}
