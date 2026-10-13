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
            researchSourceCanAnswer(question, source) &&
            (!needsPeerReview || source.reviewStatus.equals("peer_reviewed", ignoreCase = true) && source.isPreprint != true)
    }
    val covered = accepted.map { it.questionId }.toSet()
    return ResearchCoverageAssessment(covered, questions.map { it.id }.toSet() - covered, accepted)
}

/** A declared empirical requirement cannot be satisfied by pricing, protocols or commentary. */
fun researchSourceCanAnswer(question: ResearchQuestion, source: ResearchSource): Boolean {
    val requirement = question.evidenceNeeded.lowercase()
    val empirical = Regex("study|studies|trial|experiment|empirical|controlled|measured outcome").containsMatchIn(requirement)
    if (!empirical) return true
    val type = source.publicationType.lowercase().replace('_', ' ').replace('-', ' ')
    if (type.isBlank() || type == "unknown") return false
    val requested = (question.text + " " + question.evidenceNeeded).lowercase()
    if (listOf("protocol", "comment", "editorial", "price", "product", "questionnaire", "prevalence").any { it in type && it !in requested }) return false
    if ("primary" in requirement && ("review" in type || "meta analysis" in type)) return false
    if ("randomized" in requirement || "randomised" in requirement) return "randomized" in type || "randomised" in type
    if ("controlled" in requirement && "controlled" !in type) return false
    return listOf("study", "trial", "experiment", "research article", "observational", "review", "meta analysis").any { it in type }
}
