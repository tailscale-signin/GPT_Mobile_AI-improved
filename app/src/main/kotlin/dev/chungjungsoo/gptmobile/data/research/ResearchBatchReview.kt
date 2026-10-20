package dev.chungjungsoo.gptmobile.data.research

import kotlinx.serialization.json.JsonObject

/** Records are accepted independently: malformed neighbours never erase an accepted claim. */
internal object ResearchBatchReview {
    fun id(claim: ResearchClaim) = researchHash("${claim.sourceId}:${claim.questionId}:${claim.text}:${claim.quote}")
    fun apply(rows: List<JsonObject>, claims: List<ResearchClaim>, source: ResearchSource, questions: List<ResearchQuestion>, reply: ResearchModelReply?): List<ResearchClaim> {
        val unique = rows.groupBy { it.text("claimId") }
        return claims.map { claim ->
            val row = unique[id(claim)]?.singleOrNull() ?: return@map claim
            if (row.text("sourceVersionId") != source.versionId || row.text("sourceHash") != researchHash(source.passage)) return@map claim
            if (!source.readable || claim.quote !in source.passage || row.text("verdict") !in setOf("Supported", "Contradicted", "Insufficient") || row.text("relevance") !in setOf("Relevant", "Background", "Irrelevant") || questions.none { it.id == row.text("questionId") }) return@map claim
            if (row.text("verdict") == "Contradicted" && (row.text("rationale").isBlank() || row.text("counterEvidence").isBlank() || row.text("counterEvidence") !in source.passage)) return@map claim
            claim.copy(verdict = row.text("verdict"), relevance = row.text("relevance"), questionId = row.text("questionId"), reviewedBy = reply?.modelKey.orEmpty(), reviewInvocationId = reply?.invocationId.orEmpty(), independentReview = reply?.independent == true, rationale = row.text("rationale").take(1200), counterEvidence = row.text("counterEvidence").take(1200), reviewedSourceVersion = source.versionId.ifBlank { researchHash(source.passage) }, reviewRubric = "passage-review-v2")
        }
    }
}
