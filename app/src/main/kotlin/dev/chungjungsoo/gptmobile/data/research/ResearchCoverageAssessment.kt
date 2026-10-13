package dev.chungjungsoo.gptmobile.data.research

data class ResearchCoverageAssessment(val coveredQuestionIds: Set<String>, val missingQuestionIds: Set<String>, val relevantClaims: List<ResearchClaim>) {
    val complete: Boolean get() = coveredQuestionIds.isNotEmpty() && missingQuestionIds.isEmpty()
}

/** Readability, passage support, question relevance and publication suitability are separate gates. */
fun assessResearchCoverage(questions: List<ResearchQuestion>, claims: List<ResearchClaim>, sources: List<ResearchSource>): ResearchCoverageAssessment {
    val sourceById = sources.associateBy { it.id }
    val accepted = claims.filter { claim ->
        val source = sourceById[claim.sourceId]
        val question = questions.firstOrNull { it.id == claim.questionId }
        val needsPeerReview = question?.evidenceNeeded?.contains("peer", ignoreCase = true) == true
        claim.verdict == "Supported" &&
            claim.relevance == "Relevant" &&
            claim.exactQuoteVerified &&
            question != null &&
            source?.readable == true &&
            source.isRetracted != true &&
            (!needsPeerReview || source.reviewStatus.equals("peer_reviewed", ignoreCase = true) && source.isPreprint != true)
    }
    val covered = accepted.map { it.questionId }.toSet()
    return ResearchCoverageAssessment(covered, questions.map { it.id }.toSet() - covered, accepted)
}
