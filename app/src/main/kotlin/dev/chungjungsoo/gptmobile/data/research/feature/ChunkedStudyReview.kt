package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Every saved part is a valid, grounded artifact tied to the exact evidence packet. */
internal suspend fun chunkedStudyReview(
    prompt: String,
    outcome: ResearchOutcome,
    passages: List<EvidencePassage>,
    saved: Map<String, StudyReviewArtifact>,
    generate: suspend (String) -> StudyReviewArtifact,
    commit: suspend (String, StudyReviewArtifact) -> Unit
): StudyReviewArtifact {
    val packetKey = researchHash(prompt)
    suspend fun part(name: String, instruction: String, validate: (StudyReviewArtifact) -> Unit): StudyReviewArtifact {
        val key = "$packetKey:$name"
        val value = saved[key] ?: generate(prompt + "\nPART OVERRIDE: " + instruction)
        ResearchReviewScoreCalculator.validate(Json.encodeToString(value), outcome, passages)
        validate(value)
        if (key !in saved) commit(key, value)
        return value
    }
    val summary = part("summary", "Return only the conclusion, limitation, outcomeKey and cited passageIds, plus EMPTY reliability and accuracy arrays. Keep conclusion and limitation under 300 characters each. Defer domain judgments to later parts.") {
        require(it.reliability.isEmpty() && it.accuracy.isEmpty())
    }
    var result = summary
    for ((kind, domains) in listOf("reliability" to ResearchReviewScoreCalculator.reliabilityDomains, "accuracy" to ResearchReviewScoreCalculator.accuracyDomains)) {
        for (domain in domains) {
            val value = part("$kind:$domain", "Copy this exact summary and its citations unchanged: ${Json.encodeToString(summary)}. Supply ONLY domain $domain in $kind, leaving the other array empty. Rationale at most 300 characters. Retain all concern flags that apply.") {
                require(it.conclusion == summary.conclusion && it.limitation == summary.limitation && it.passageIds == summary.passageIds)
                require(if (kind == "reliability") it.reliability.map { row -> row.domain } == listOf(domain) && it.accuracy.isEmpty() else it.accuracy.map { row -> row.domain } == listOf(domain) && it.reliability.isEmpty())
            }
            result = result.copy(reliability = result.reliability + value.reliability, accuracy = result.accuracy + value.accuracy, criticalConcern = result.criticalConcern || value.criticalConcern, summaryContradiction = result.summaryContradiction || value.summaryContradiction, integrityConcern = result.integrityConcern || value.integrityConcern)
        }
    }
    return ResearchReviewScoreCalculator.validate(Json.encodeToString(result), outcome, passages)
}
