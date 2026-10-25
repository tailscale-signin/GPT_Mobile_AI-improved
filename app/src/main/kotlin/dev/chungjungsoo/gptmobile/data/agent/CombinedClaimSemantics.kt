package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.util.Locale
import kotlinx.serialization.Serializable

/** Explicit dimensions are kept separate; an empty field means unknown, not universally applicable. */
@Serializable
data class CombinedClaimQualifiers(
    val times: Set<String> = emptySet(),
    val units: Set<String> = emptySet(),
    val populations: Set<String> = emptySet(),
    val routes: Set<String> = emptySet(),
    val studyTypes: Set<String> = emptySet(),
    val entities: Set<String> = emptySet(),
    val sampleSizes: Set<String> = emptySet(),
    val polarity: String = "UNSPECIFIED",
    val outcome: String = "UNSPECIFIED",
    val causalStrength: String = "UNSPECIFIED",
    val uncertainty: Set<String> = emptySet(),
    val restrictions: Set<String> = emptySet()
)

@Serializable
data class CombinedClaimOccurrence(
    val candidateId: String,
    val profileUid: String,
    val bodyDigest: String,
    val sourceSpan: CombinedCoverageLink? = null,
    val attemptId: String? = null,
    val createdAt: Long? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val occurrenceId: String = "",
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val tableCells: List<CombinedTableCellAddress> = emptyList()
)

@Serializable
data class CombinedClaimRecord(
    val id: String,
    val semanticId: String,
    val proposition: String,
    val subject: String = "",
    val predicate: String = "",
    val objectOrOutcome: String = "",
    val qualifiers: CombinedClaimQualifiers = CombinedClaimQualifiers(),
    val occurrences: List<CombinedClaimOccurrence> = emptyList(),
    val profileUids: Set<String> = emptySet(),
    val ancestryIds: Set<String> = emptySet(),
    val extractionMethod: String = "CONSERVATIVE_PROPOSITION_V1"
) {
    val candidateIds: Set<String> get() = occurrences.mapTo(linkedSetOf()) { it.candidateId }
    val canonicalId: String get() = "claim:$semanticId"
}

/**
 * Conservative applicability checks. These rules can reject an unsafe alignment, but cannot
 * certify general natural-language entailment. Unparsed propositions remain available in full.
 */
internal object CombinedClaimSemantics {
    private fun has(text: String, pattern: String): Boolean = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)
    private fun labels(text: String, rules: Map<String, String>): Set<String> = rules.filterValues { has(text, it) }.keys
    private val routeRules = linkedMapOf(
        "INHALATION" to "\\binhal(?:ed|ation|ing|e)\\b|\\bbreath(?:ed|ing)\\b.*\\bhydrogen\\b",
        "ORAL_WATER" to "\\bhydrogen[- ]rich water\\b|\\bhydrogen water\\b|\\b(?:drinking|drank|oral|orally|ingest(?:ed|ion))\\b",
        "INJECTION" to "\\b(?:inject(?:ed|ion)|intravenous|intraperitoneal)\\b",
        "TOPICAL" to "\\b(?:topical|skin application)\\b",
        "IN_VITRO" to "\\b(?:in vitro|cell culture|cultured cells)\\b"
    )
    private val populationRules = linkedMapOf(
        "HUMAN" to "\\b(?:human|patients?|participants?|volunteers?|people)\\b",
        "ANIMAL" to "\\b(?:mice|mouse|rats?|rodents?|animal(?:s)?)\\b",
        "CHILD" to "\\b(?:children|child|pediatric|paediatric|infants?)\\b",
        "ADULT" to "\\badults?\\b",
        "HEALTHY" to "\\bhealthy (?:participants|adults|volunteers|people)\\b",
        "PARKINSON" to "\\bparkinson(?:'s|’s)?\\b"
    )
    private val studyRules = linkedMapOf(
        "RANDOMIZED" to "\\brandomi[sz]ed\\b|\\brct\\b",
        "OBSERVATIONAL" to "\\b(?:observational|cohort|case[- ]control)\\b",
        "PILOT" to "\\bpilot\\b",
        "META_ANALYSIS" to "\\bmeta[- ]analysis\\b",
        "MECHANISTIC" to "\\b(?:mechanism|mechanistic|biochemical|plausibility)\\b",
        "PRECLINICAL" to "\\b(?:preclinical|animal study|in vitro)\\b"
    )
    fun qualifiers(text: String): CombinedClaimQualifiers {
        val lower = text.lowercase(Locale.ROOT)
        val uncertainty = labels(
            lower,
            linkedMapOf(
                "POSSIBLE" to "\\b(?:may|might|could|possibly|potential|suggests?|suggested)\\b",
                "REPORTED" to "\\b(?:reportedly|allegedly|according to|reported by)\\b",
                "LIMITED_EVIDENCE" to "\\b(?:limited|preliminary|small|pilot|inconsistent|uncertain|unverified|not established|not proven|insufficient)\\b"
            )
        )
        val nullOutcome = has(lower, "\\b(?:null (?:result|finding|outcome)|no (?:significant |statistically significant |clinical )?(?:benefit|improvement|effect|difference)|(?:did not|didn't|didn’t|failed to) (?:significantly )?(?:improve|reduce|increase|benefit)|not (?:statistically )?significant)\\b")
        val insufficient = has(lower, "\\b(?:insufficient evidence|not established|not proven|no evidence|unproven)\\b")
        val positive = has(lower, "\\b(?:improve[ds]?|benefits?|effective|efficacy|reduce[ds]?|increase[ds]?)\\b")
        val outcome = when {
            nullOutcome -> "NULL_EFFECT"
            insufficient -> "INSUFFICIENT_EVIDENCE"
            positive -> "POSITIVE_ASSERTION"
            else -> "UNSPECIFIED"
        }
        return CombinedClaimQualifiers(
            times = Regex("\\b(?:1[0-9]{3}|20[0-9]{2})(?:-[0-9]{2}-[0-9]{2})?\\b").findAll(lower).mapTo(linkedSetOf()) { it.value },
            units = Regex("\\b(?:[0-9]+(?:\\.[0-9]+)?)\\s*(%|mg|g|kg|ml|l|ppm|hours?|minutes?|days?|weeks?|months?|years?|kilometers?|kilometres?|degrees?)\\b").findAll(lower).mapTo(linkedSetOf()) { it.groupValues[1].removeSuffix("s") },
            populations = labels(lower, populationRules),
            routes = labels(lower, routeRules),
            studyTypes = labels(lower, studyRules),
            entities = Regex("\\b(?:il[- ]?[0-9]+|tnf[- ]?(?:alpha|α)|nf[- ]?kb|nrf[0-9]+|ros|[a-z]{2,}[-][0-9]+)\\b", RegexOption.IGNORE_CASE).findAll(lower).mapTo(linkedSetOf()) { it.value.replace(" ", "-") },
            sampleSizes = Regex("\\b(?:n\\s*=\\s*([0-9]+)|([0-9]+)\\s+(?:patients?|participants?|volunteers?|subjects?))\\b").findAll(lower).mapTo(linkedSetOf()) { it.groupValues[1].ifBlank { it.groupValues[2] } },
            polarity = if (nullOutcome || insufficient) "NEGATIVE" else "UNSPECIFIED",
            outcome = outcome,
            causalStrength = when {
                has(lower, "\\b(?:caused?|causes|causal|led to|proved?|proves|demonstrated efficacy|established benefit)\\b") -> "CAUSAL"
                has(lower, "\\b(?:associated|association|correlat(?:ed|ion)|linked to)\\b") -> "ASSOCIATION"
                has(lower, "\\b(?:mechanism|mechanistic|plausib(?:le|ility))\\b") -> "MECHANISTIC"
                else -> "UNSPECIFIED"
            },
            uncertainty = uncertainty,
            restrictions = Regex("\\b(?:only|except|unless|at most|at least|without proving|not necessarily)\\b").findAll(lower).mapTo(linkedSetOf()) { it.value }
        )
    }

    fun applicabilityConflicts(first: CombinedClaimQualifiers, second: CombinedClaimQualifiers): List<String> = buildList {
        fun exclusive(a: Set<String>, b: Set<String>) = a.isNotEmpty() && b.isNotEmpty() && a.intersect(b).isEmpty()
        if (exclusive(first.routes, second.routes)) add("INTERVENTION_ROUTE_MISMATCH")
        // A contrast such as "animal evidence is stronger than human evidence" has
        // both populations. It must match itself; only disjoint explicit scopes
        // establish a population conflict. lostQualifiers still rejects dropping
        // either population from a comparison.
        val populationKinds = setOf("ANIMAL", "HUMAN")
        if (exclusive(first.populations.intersect(populationKinds), second.populations.intersect(populationKinds))) add("POPULATION_MISMATCH")
        if (exclusive(first.times, second.times)) add("TIME_SCOPE_MISMATCH")
        if (exclusive(first.units, second.units)) add("UNIT_MISMATCH")
        if (exclusive(first.entities, second.entities)) add("ENTITY_MISMATCH")
    }

    fun lostQualifiers(source: CombinedClaimQualifiers, target: CombinedClaimQualifiers): List<String> = buildList {
        addAll(applicabilityConflicts(source, target))
        if (!target.times.containsAll(source.times)) add("TIME_SCOPE_OMITTED")
        if (!target.units.containsAll(source.units)) add("UNIT_OMITTED")
        if (!target.entities.containsAll(source.entities)) add("ENTITY_OMITTED")
        if (!target.restrictions.containsAll(source.restrictions)) add("RESTRICTION_OMITTED")
        if (source.routes.isNotEmpty() && !target.routes.containsAll(source.routes)) add("INTERVENTION_ROUTE_OMITTED")
        if (source.populations.isNotEmpty() && !target.populations.containsAll(source.populations)) add("POPULATION_OMITTED")
        if (source.studyTypes.isNotEmpty() && !target.studyTypes.containsAll(source.studyTypes)) add("STUDY_TYPE_OMITTED")
        if (source.sampleSizes.isNotEmpty() && !target.sampleSizes.containsAll(source.sampleSizes)) add("SAMPLE_SIZE_OMITTED")
        if (source.uncertainty.isNotEmpty() && !target.uncertainty.containsAll(source.uncertainty)) add("UNCERTAINTY_OMITTED")
        if (source.outcome in setOf("NULL_EFFECT", "INSUFFICIENT_EVIDENCE") && source.outcome != target.outcome) add("NULL_OR_UNESTABLISHED_OUTCOME_CHANGED")
        if (source.causalStrength in setOf("ASSOCIATION", "MECHANISTIC") && target.causalStrength == "CAUSAL") add("CAUSAL_STRENGTH_INCREASED")
    }

    fun consequential(text: String): Boolean = has(text, "\\b(?:patients?|treatment|disease|therapy|mortality|clinical|medical|toxicolog\\w*|cocaine|overdose|malpractice|criminal|reconciled|reconciliation)\\b")

    fun records(sources: List<CombinedModelResponse>): List<CombinedClaimRecord> = CombinedContentIndexes.get(sources).records

    internal fun extractRecords(sources: List<CombinedModelResponse>): List<CombinedClaimRecord> {
        val candidates = sources.flatMapIndexed { sourceIndex, source ->
            var cursor = 0
            val digest = researchHash(source.content)
            val spans = CombinedTextSpanIndex(source.content)
            val controls = if (source.claimExtractionVersion >= 3) CombinedEditorialView.prepare(source.content).omissions.filter { it.reason == "STANDALONE_SELF_EDITING" } else emptyList()
            val extracted = if (source.claimExtractionVersion >= 2) CombinedSourceClaims.units(source.content, source.contentCompleteness == "PARTIAL", excludeStructural = source.claimExtractionVersion >= 4).filterNot { unit -> controls.any { unit.start >= it.start && unit.end <= it.end } } else null
            val texts = extracted?.map { it.text } ?: CombinedContentCoverage.sentences(CombinedContentCoverage.sourceCoverageText(source.content))
            texts.mapIndexed { index, text ->
                val unit = extracted?.get(index)
                val sourceSpan = unit?.let { CombinedSourceClaims.span(source.content, it) } ?: spans.find(text, cursor)
                if (sourceSpan != null) cursor = sourceSpan.endUtf16
                val qualifiers = qualifiers(text)
                val id = "C${sourceIndex + 1}.${index + 1}"
                // Word normalization removes signs, decimal marks and percent symbols.
                // Preserve legacy identities for ordinary prose, never merge distinct values.
                val numeric = CombinedClaimMatcher.fingerprint(text).orderedNumbers
                val numericIdentity = if (numeric.any { value -> value.any { it in "-.%" } }) "|numeric-v2:$numeric" else ""
                val semanticId = researchHash(CombinedContentCoverage.normalized(text) + "|" + qualifiers + numericIdentity)
                val split = Regex("\\b(?:is|are|was|were|has|have|had|did|does|approved|reported|caused|showed|found)\\b", RegexOption.IGNORE_CASE).find(text)
                CombinedClaimRecord(
                    id = id,
                    semanticId = semanticId,
                    proposition = text,
                    subject = split?.let { text.substring(0, it.range.first).trim() }.orEmpty(),
                    predicate = split?.value.orEmpty(),
                    objectOrOutcome = split?.let { text.substring(it.range.last + 1).trim() }.orEmpty(),
                    qualifiers = qualifiers,
                    occurrences = listOf(
                        CombinedClaimOccurrence(
                            id,
                            source.platformUid,
                            digest,
                            sourceSpan,
                            source.attemptId,
                            source.createdAt,
                            occurrenceId = if (source.claimExtractionVersion >= 2) researchHash("${source.platformUid}|$digest|${source.attemptId}|${sourceSpan?.startUtf16}|${sourceSpan?.endUtf16}|$semanticId") else "",
                            tableCells = unit?.cells.orEmpty()
                        )
                    ),
                    profileUids = setOf(source.platformUid),
                    ancestryIds = source.ancestryIds.ifEmpty { setOf("contribution:$digest") },
                    extractionMethod = when {
                        source.claimExtractionVersion >= 4 -> "EDITORIAL_CLAIM_V4"
                        source.claimExtractionVersion >= 3 -> "MAPPED_EDITORIAL_PROPOSITION_V3"
                        source.claimExtractionVersion >= 2 -> "SCOPED_PROPOSITION_V2"
                        else -> "CONSERVATIVE_PROPOSITION_V1"
                    }
                )
            }
        }
        // Only identical propositions/qualifiers are collapsed automatically. A lexical paraphrase
        // shortlist must not erase a distinct qualifier before a semantic comparison occurs.
        return candidates.groupBy { it.semanticId }.values.map { copies ->
            copies.first().copy(
                occurrences = copies.flatMap { it.occurrences },
                profileUids = copies.flatMapTo(linkedSetOf()) { it.profileUids },
                ancestryIds = copies.flatMapTo(linkedSetOf()) { it.ancestryIds }
            )
        }
    }
}
