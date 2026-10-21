package dev.chungjungsoo.gptmobile.data.research

import kotlinx.serialization.Serializable

@Serializable
enum class RequirementCombination { ANY_OF, ALL_OF }

@Serializable
data class ResearchEvidenceRequirement(
    val designs: Set<String> = emptySet(),
    val combination: RequirementCombination = RequirementCombination.ANY_OF,
    val peerReviewed: Boolean = false,
    val primaryOnly: Boolean = false,
    val population: Set<String> = emptySet(),
    val interventions: Set<String> = emptySet(),
    val outcomes: Set<String> = emptySet()
)

enum class ResearchRequirementResult { SATISFIED, METADATA_REQUIRED, UNSUITABLE }

fun researchEvidenceRequirement(question: ResearchQuestion): ResearchEvidenceRequirement {
    question.requirement?.let { return it }
    val prose = question.evidenceNeeded.lowercase().replace('-', ' ').replace("peer reviewed", "")
    val designs = buildSet {
        if (Regex("randomi[sz]ed|\\brct\\b").containsMatchIn(prose)) add("RANDOMIZED_TRIAL")
        if ("controlled" in prose && "RANDOMIZED_TRIAL" !in this) add("CONTROLLED_TRIAL")
        if (Regex("observational|cohort|case control").containsMatchIn(prose)) add("OBSERVATIONAL")
        if ("systematic review" in prose) add("SYSTEMATIC_REVIEW")
        if ("meta analys" in prose) add("META_ANALYSIS")
    }
    return ResearchEvidenceRequirement(designs, if ("all of" in prose) RequirementCombination.ALL_OF else RequirementCombination.ANY_OF, question.evidenceNeeded.contains("peer", true), "primary" in prose)
}

fun assessResearchRequirement(question: ResearchQuestion, source: ResearchSource): ResearchRequirementResult {
    val need = researchEvidenceRequirement(question)
    val type = source.publicationType.lowercase().replace('_', ' ').replace('-', ' ')
    val known = source.studyDesigns.map { it.uppercase() }.toMutableSet()
    if (Regex("randomi[sz]ed").containsMatchIn(type)) {
        known += "RANDOMIZED_TRIAL"
        known += "CONTROLLED_TRIAL"
    }
    if ("controlled" in type) known += "CONTROLLED_TRIAL"
    if (Regex("observational|cohort|case control").containsMatchIn(type)) known += "OBSERVATIONAL"
    if ("systematic review" in type) known += "SYSTEMATIC_REVIEW"
    if ("meta analys" in type) known += "META_ANALYSIS"
    if (source.isRetracted == true || need.peerReviewed && (source.isPreprint == true || source.reviewStatus.lowercase() in setOf("not_peer_reviewed", "preprint"))) return ResearchRequirementResult.UNSUITABLE
    if (need.peerReviewed && source.reviewStatus.lowercase() !in setOf("peer_reviewed", "confirmed_peer_reviewed")) return ResearchRequirementResult.METADATA_REQUIRED
    val empirical = need.designs.isNotEmpty() || Regex("study|studies|trial|experiment|empirical|controlled|measured outcome").containsMatchIn(question.evidenceNeeded.lowercase())
    if (empirical && ((type.isBlank() || type == "unknown" || type == "journal article") && known.isEmpty())) return ResearchRequirementResult.METADATA_REQUIRED
    if (need.primaryOnly && ("review" in type || "meta analysis" in type || known.any { it in setOf("SYSTEMATIC_REVIEW", "META_ANALYSIS") })) return ResearchRequirementResult.UNSUITABLE
    if (empirical && listOf("protocol", "comment", "editorial", "price", "product").any { it in type && it !in question.text.lowercase() }) return ResearchRequirementResult.UNSUITABLE
    if (need.designs.isNotEmpty()) {
        if (known.isEmpty()) return ResearchRequirementResult.METADATA_REQUIRED
        val matches = need.designs.map { it.uppercase() in known }
        if (if (need.combination == RequirementCombination.ALL_OF) !matches.all { it } else !matches.any { it }) return ResearchRequirementResult.UNSUITABLE
    }
    for ((required, actual) in listOf(need.population to source.population, need.interventions to source.intervention, need.outcomes to source.outcomes)) {
        if (required.isNotEmpty()) {
            if (actual.isBlank()) return ResearchRequirementResult.METADATA_REQUIRED
            if (required.none { actual.contains(it, true) }) return ResearchRequirementResult.UNSUITABLE
        }
    }
    return ResearchRequirementResult.SATISFIED
}
