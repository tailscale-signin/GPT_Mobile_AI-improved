package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import kotlinx.serialization.Serializable

@Serializable
data class CombinedAnswerCheck(
    val id: String,
    val category: String,
    val result: String,
    val material: Boolean = false,
    val claimIds: Set<String> = emptySet(),
    val message: String = ""
)

internal data class CombinedFinalAssessment(
    val checks: List<CombinedAnswerCheck>,
    val decisions: List<CombinedClaimReview>,
    val receipts: List<CombinedVerificationReceipt>,
    val supportEdges: List<CombinedSupportEdge>,
    val verificationNeeds: List<CombinedVerificationNeed>
) {
    val issues: List<String> get() = checks.filter { it.result in setOf("FAIL", "NEEDS_VERIFICATION") }.map { it.id }.distinct()
    val correctionRequired: Boolean get() = checks.any { it.material && it.result == "FAIL" }
    val requiredFailures: List<String> get() = checks.filter { it.result == "FAIL" && (it.material || it.category in setOf("REQUEST", "SCOPE", "RESEARCH_SCOPE")) }.map { it.id }
    val hardFailures: List<String> get() = checks.filter { it.result == "FAIL" }.map { it.id }
}

/** Live delivery uses the same claim inventory as synthesis; no independent, unenforced sidecar. */
internal object CombinedFinalCheck {
    private fun sentencesWithSpans(answer: String, start: Int = 0, end: Int = answer.length): List<Pair<String, CombinedCoverageLink>> {
        val body = answer.substring(start, end)
        var cursor = 0
        val spans = CombinedTextSpanIndex(body)
        return CombinedContentCoverage.sentences(body).mapNotNull { text ->
            val span = spans.find(text, cursor) ?: return@mapNotNull null
            cursor = span.endUtf16
            text to span.copy(startUtf16 = start + span.startUtf16, endUtf16 = start + span.endUtf16)
        }
    }

    fun check(
        answer: String,
        sources: List<CombinedModelResponse>,
        evidence: CombinedEvidenceLedger,
        policy: CombinedAnswerPolicy,
        passages: List<CombinedPassageRecord> = emptyList(),
        declarations: List<CombinedClaimReview> = emptyList(),
        revisionId: String = ""
    ): CombinedFinalAssessment {
        val checks = mutableListOf<CombinedAnswerCheck>()
        val receipts = mutableListOf<CombinedVerificationReceipt>()
        val edges = mutableListOf<CombinedSupportEdge>()
        val needs = mutableListOf<CombinedVerificationNeed>()
        val contracts = policy.violations(answer)
        checks += CombinedAnswerCheck("ANSWER_CONTRACT", "REQUEST", if (contracts.isEmpty()) "PASS" else "FAIL", message = contracts.joinToString(", "))
        checks += contracts.map { CombinedAnswerCheck(it, "REQUEST", "FAIL", material = it == "TASK_SUBJECT_MISMATCH") }
        if (policy.task?.historicalSurvey == true && (policy.targetWords ?: policy.maximumWords ?: 500) >= 150) {
            val available = sources.flatMap { CombinedContentCoverage.sentences(it.content) }.filter(CombinedHistoryOutline::substantive).flatMap { CombinedHistoryOutline.periods(it) }.toSet()
            val covered = CombinedHistoryOutline.periods(answer)
            (available - covered).forEach { period ->
                checks += CombinedAnswerCheck("TASK_COVERAGE_UNMET:${CombinedHistoryOutline.labels[period]}", "SCOPE", "FAIL", true, message = "Retained contributions cover this requested historical period, but the answer does not.")
            }
        }
        val inventory = CombinedClaimSemantics.records(sources)
        val byId = inventory.flatMap { claim -> claim.candidateIds.map { it to claim } }.toMap()
        val destinations = if (passages.isEmpty()) sentencesWithSpans(answer) else passages.filter { it.matches(answer) }.flatMap { sentencesWithSpans(answer, it.start, it.end) }
        if (passages.any { !it.matches(answer) }) checks += CombinedAnswerCheck("STALE_PASSAGE_REVISION", "PROVENANCE", "FAIL", true)

        val sourceAssessments = inventory.associate { claim -> claim.id to CombinedEvidenceVerifier.assess(claim.id, claim.proposition, evidence) }
        val matches = inventory.associate { claim ->
            claim.id to destinations.filter { (text, _) ->
                CombinedClaimSemantics.lostQualifiers(claim.qualifiers, CombinedClaimSemantics.qualifiers(text)).isEmpty() &&
                    (
                        CombinedContentCoverage.normalized(claim.proposition) == CombinedContentCoverage.normalized(text) ||
                            CombinedClaimMatcher.match(claim.proposition, text) >= CombinedClaimMatcher.Match.PARAPHRASE
                        )
            }.map { it.second }
        }
        val corrections = inventory.associate { claim ->
            claim.id to if (sourceAssessments[claim.id]?.result == "CONFLICTING_PASSAGES") {
                destinations.filter { (text, _) ->
                    CombinedClaimMatcher.possibleConflict(CombinedClaimMatcher.fingerprint(claim.proposition), CombinedClaimMatcher.fingerprint(text)) &&
                        CombinedEvidenceVerifier.assess(claim.id, text, evidence).result == "TEXT_SUPPORTED_WITH_LIMITS"
                }.map { it.second }
            } else {
                emptyList()
            }
        }
        val reviewed = declarations.map { declaration ->
            val claim = byId[declaration.claimId]
            val correctedOrDisputed = if (claim != null && declaration.disposition in setOf("CORRECTED", "DISPUTED")) {
                destinations.filter { (text, _) ->
                    val aligned = CombinedClaimMatcher.fingerprint(claim.proposition).terms.intersect(CombinedClaimMatcher.fingerprint(text).terms).size >= 3
                    if (declaration.disposition == "CORRECTED") {
                        aligned &&
                            sourceAssessments[claim.id]?.result == "CONFLICTING_PASSAGES" &&
                            CombinedEvidenceVerifier.assess(claim.id, text, evidence).result == "TEXT_SUPPORTED_WITH_LIMITS"
                    } else {
                        aligned && Regex("(?i)\\b(?:disputed|claims?|asserted|reported|disagree|contradict|uncertain)\\b").containsMatchIn(text)
                    }
                }.map { it.second }
            } else {
                emptyList()
            }
            val outputs = (claim?.let { matches[it.id] }.orEmpty() + correctedOrDisputed).distinct().filter { span ->
                declaration.finalQuote.isBlank() || answer.substring(span.startUtf16, span.endUtf16).contains(declaration.finalQuote)
            }
            val linksRequired = declaration.disposition in setOf("INCLUDED", "MERGED", "QUALIFIED", "DISPUTED", "CORRECTED")
            if (claim == null || linksRequired && outputs.isEmpty() || declaration.disposition == "MERGED" && declaration.finalQuote.isBlank()) {
                val issue = if (claim == null) "UNKNOWN_CLAIM_DECISION:${declaration.claimId}" else "UNTRUTHFUL_${declaration.disposition}_DISPOSITION:${declaration.claimId}"
                checks += CombinedAnswerCheck(issue, "FAIRNESS", "FAIL", claimIds = setOf(declaration.claimId))
                declaration.copy(disposition = "UNRESOLVED", semanticStatus = "UNESTABLISHED", failedChecks = listOf(issue))
            } else if (linksRequired) {
                declaration.copy(finalSpans = outputs, semanticStatus = "CONSERVATIVE_PROPOSITION_AND_QUALIFIER_CHECKED")
            } else if (declaration.disposition == "OMITTED_DUPLICATE" &&
                claim != null &&
                inventory.none { other ->
                    other.id != claim.id &&
                        CombinedClaimMatcher.match(claim.proposition, other.proposition) >= CombinedClaimMatcher.Match.PARAPHRASE &&
                        CombinedClaimMatcher.match(other.proposition, claim.proposition) >= CombinedClaimMatcher.Match.PARAPHRASE
                } &&
                claim.candidateIds.size < 2
            ) {
                val issue = "UNESTABLISHED_DUPLICATE_EXCLUSION:${declaration.claimId}"
                checks += CombinedAnswerCheck(issue, "FAIRNESS", "FAIL", claimIds = setOf(declaration.claimId))
                declaration.copy(disposition = "UNRESOLVED", semanticStatus = "UNESTABLISHED", failedChecks = listOf(issue))
            } else {
                declaration.copy(semanticStatus = "REASON_RECORDED_NOT_FACTUALLY_CERTIFIED")
            }
        }.toMutableList()
        passages.filter { it.matches(answer) }.forEach { passage ->
            passage.claimIds.forEach { id ->
                val claim = byId[id]
                val linked = claim != null && matches[claim.id].orEmpty().any { it.startUtf16 >= passage.start && it.endUtf16 <= passage.end }
                val corrected = reviewed.any { it.claimId == id && it.disposition in setOf("CORRECTED", "DISPUTED") && it.finalSpans.isNotEmpty() }
                if (!linked && !corrected) checks += CombinedAnswerCheck("UNESTABLISHED_FINAL_CLAIM_LINK:$id:${passage.passageId}", "PROVENANCE", "FAIL", claimIds = setOf(id))
            }
        }
        // A linked inclusion is recorded even when the model emits no redundant decision record.
        inventory.forEach { claim ->
            val outputs = matches[claim.id].orEmpty()
            claim.candidateIds.filter { id -> reviewed.none { it.claimId == id } }.forEach { id ->
                reviewed += if (outputs.isNotEmpty()) {
                    CombinedClaimReview(id, "INCLUDED", "Identified final span retains the proposition and extracted qualifiers.", outputs, "CONSERVATIVE_PROPOSITION_AND_QUALIFIER_CHECKED")
                } else if (corrections[claim.id].orEmpty().isNotEmpty()) {
                    CombinedClaimReview(id, "CORRECTED", "An inspected passage contradicts the original claim and supports the aligned replacement with its qualifiers.", corrections[claim.id].orEmpty(), "RETAINED_PASSAGE_CORRECTION_WITH_LIMITS")
                } else {
                    CombinedClaimReview(id, "UNRESOLVED", "No established inclusion or stated exclusion; lexical absence alone does not prove a material omission.")
                }
            }
            if (outputs.isEmpty() && corrections[claim.id].orEmpty().isEmpty() && (claim.qualifiers.uncertainty.isNotEmpty() || claim.qualifiers.outcome in setOf("NULL_EFFECT", "INSUFFICIENT_EVIDENCE"))) {
                val original = CombinedClaimMatcher.fingerprint(claim.proposition)
                val related = destinations.filter { (text, _) ->
                    val target = CombinedClaimMatcher.fingerprint(text)
                    original.terms.intersect(target.terms).size >= 3 &&
                        CombinedClaimSemantics.applicabilityConflicts(claim.qualifiers, target.qualifiers).isEmpty()
                }
                if (related.isNotEmpty()) {
                    val grounded = sourceAssessments[claim.id]?.result == "TEXT_SUPPORTED_WITH_LIMITS"
                    checks += CombinedAnswerCheck("MATERIAL_QUALIFIER_UNRESOLVED:${claim.id}", "QUALIFICATIONS", if (grounded) "FAIL" else "NEEDS_VERIFICATION", grounded, claim.candidateIds, "A relevant minority qualification or null finding has no established final span.")
                    needs += CombinedVerificationNeed(claim.id, claim.proposition, "Retain or resolve the missing qualification against exact evidence", qualifiers = claim.qualifiers)
                }
            }
        }

        destinations.forEachIndexed { index, (text, span) ->
            val id = "F${index + 1}"
            val cited = passages.filter { span.startUtf16 >= it.start && span.endUtf16 <= it.end }.flatMapTo(linkedSetOf()) { it.sourceIds }
            val assessment = CombinedEvidenceVerifier.assess(id, text, evidence, cited)
            edges += assessment.edges
            receipts += CombinedEvidenceVerifier.receipt(answer, revisionId, id, span, assessment)
            checks += assessment.issues.map { CombinedAnswerCheck(it, "EVIDENCE", "FAIL", assessment.material, setOf(id)) }
            if (assessment.result in setOf("UNVERIFIED", "CITED_UNVERIFIED", "PARTIAL_SUPPORT", "CONFLICTING_PASSAGES") && CombinedClaimSemantics.consequential(text)) {
                // Hedged, honestly attributed limitations do not become confident conclusions merely because they are delivered.
                val qualifiers = CombinedClaimSemantics.qualifiers(text)
                val limited = qualifiers.outcome == "INSUFFICIENT_EVIDENCE" || Regex("(?i)\\b(?:unverified|unable to verify|could not verify|not confirmed|evidence (?:is |remains )?(?:limited|inconsistent|unavailable))\\b").containsMatchIn(text)
                if (!limited || assessment.material) {
                    needs += CombinedVerificationNeed(id, text, assessment.issues.joinToString("; ").ifBlank { "Consequential assertion lacks inspected support" }, cited, qualifiers)
                    checks += CombinedAnswerCheck("CONSEQUENTIAL_CLAIM_NEEDS_EVIDENCE:$id", "EVIDENCE", "NEEDS_VERIFICATION", claimIds = setOf(id))
                }
            }
        }
        policy.requiredTopics.forEach { topic ->
            val terms = CombinedClaimMatcher.fingerprint(topic).terms
            if (terms.isNotEmpty() && destinations.none { (text, _) -> CombinedClaimMatcher.fingerprint(text).terms.containsAll(terms) }) {
                needs += CombinedVerificationNeed("requested-topic:" + dev.chungjungsoo.gptmobile.data.research.researchHash(topic), topic, "Explicit requested topic lacks a final answer span; address it or disclose the evidence limitation")
                checks += CombinedAnswerCheck("REQUIRED_TOPIC_UNRESOLVED:$topic", "SCOPE", "NEEDS_VERIFICATION", message = "Explicit requested topic lacks an established final span")
            }
        }
        if (policy.requestedLanguage != null) checks += CombinedAnswerCheck("LANGUAGE:${policy.requestedLanguage}", "REQUEST", "NEEDS_REVIEW", message = "Requested language is preserved in the frozen contract; deterministic lexical checks do not certify language")
        val finalConflicts = CombinedConflictDetector.scan(listOf(CombinedModelResponse("final", "Final", content = answer)))
        finalConflicts.candidates.forEach { conflict ->
            // Reporting a disagreement is legitimate. A conflict cue remains a review need, not an automatic fact correction.
            val explained = Regex("(?i)\\b(?:conflict|disagree|disputed|contradict|uncertain|unresolved|whereas|however|although|reported|claimed)\\b").containsMatchIn(conflict.leftText + " " + conflict.rightText)
            if (!explained) {
                checks += CombinedAnswerCheck("FINAL_ALIGNED_CONFLICT:${conflict.leftClaimId}:${conflict.rightClaimId}", "CONFLICT", "NEEDS_VERIFICATION", message = conflict.kind)
                needs += CombinedVerificationNeed(conflict.leftClaimId, conflict.leftText + "\n" + conflict.rightText, "Resolve the aligned ${conflict.kind.lowercase()} conflict in the draft")
            }
        }
        if (!finalConflicts.complete) checks += CombinedAnswerCheck("CONFLICT_SCAN_INCOMPLETE", "CONFLICT", "NEEDS_VERIFICATION")
        return CombinedFinalAssessment(checks.distinctBy { it.id }, reviewed, receipts, edges.distinctBy { it.id }, needs.distinctBy { it.proposition to it.reason })
    }
}
