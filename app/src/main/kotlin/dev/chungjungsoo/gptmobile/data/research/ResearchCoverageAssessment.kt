package dev.chungjungsoo.gptmobile.data.research

data class ResearchCoverageAssessment(val coveredQuestionIds: Set<String>, val missingQuestionIds: Set<String>, val relevantClaims: List<ResearchClaim>, val metadataRequiredQuestionIds: Set<String> = emptySet()) {
    val complete: Boolean get() = coveredQuestionIds.isNotEmpty() && missingQuestionIds.isEmpty()
}

/** Readability, passage support, question relevance and publication suitability are separate gates. */
fun assessResearchCoverage(questions: List<ResearchQuestion>, claims: List<ResearchClaim>, sources: List<ResearchSource>): ResearchCoverageAssessment {
    val sourceById = sources.associateBy { it.id }
    val accepted = claims.filter { claim ->
        val source = sourceById[claim.sourceId]
        val question = questions.firstOrNull { it.id == claim.questionId }
        claim.verdict == "Supported" &&
            claim.relevance == "Relevant" &&
            claim.exactQuoteVerified &&
            source != null &&
            claim.quote.isNotBlank() &&
            claim.quote in source.passage &&
            (claim.reviewedSourceVersion.isBlank() || claim.reviewedSourceVersion == capturedResearchVersion(source)) &&
            question != null &&
            source.readable &&
            source.isRetracted != true &&
            researchSourceCanAnswer(question, source)
    }
    val covered = accepted.map { it.questionId }.toSet()
    return ResearchCoverageAssessment(covered, questions.map { it.id }.toSet() - covered, accepted, questions.filter { question -> sources.any { it.readable && assessResearchRequirement(question, it) == ResearchRequirementResult.METADATA_REQUIRED } }.map { it.id }.toSet())
}

/** Typed alternatives distinguish unknown metadata from an incompatible study. */
fun researchSourceCanAnswer(question: ResearchQuestion, source: ResearchSource): Boolean = assessResearchRequirement(question, source) == ResearchRequirementResult.SATISFIED
