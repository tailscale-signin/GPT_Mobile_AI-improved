package dev.chungjungsoo.gptmobile.data.research.delivery

import dev.chungjungsoo.gptmobile.data.research.ResearchClaimFact
import java.util.Locale

/** Literal quantity and relation checks. These never establish entailment or independent scientific validity. */
internal object ResearchNumericalGrounding {
    private const val NUMBER = "[-+]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?(?:[eE][-+]?\\d+)?"
    private val quantity = Regex("(?i)(?<![\\p{L}\\p{N}])($NUMBER)\\s*(%|participants?|patients?|subjects?|volunteers?|points?|mg|kg|g|ml|litres?|liters?|mmhg|hours?|days?|weeks?|months?|years?)(?![\\p{L}\\p{N}])")
    private val comparator = Regex("(?i)(?<![\\p{L}\\p{N}])($NUMBER)\\s*(?:versus|vs\\.?)\\s*($NUMBER)(?![\\p{L}\\p{N}])")
    private fun number(value: String): String = value.replace(",", "").toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: value
    private fun unit(value: String): String = when (val normalized = value.lowercase(Locale.ROOT).removeSuffix("s")) {
        "patient", "participant", "subject", "volunteer" -> "participant"
        "liter", "litre" -> "litre"
        else -> normalized
    }
    private fun quantities(text: String) = quantity.findAll(text).map { number(it.groupValues[1]) to unit(it.groupValues[2]) }.toSet()
    private fun comparisons(text: String) = comparator.findAll(text).map { number(it.groupValues[1]) to number(it.groupValues[2]) }.toSet()

    private val populationFamilies = listOf(
        setOf("adult", "adults"), setOf("child", "children", "pediatric", "paediatric"), setOf("infant", "infants", "neonatal"),
        setOf("rat", "rats"), setOf("mouse", "mice", "murine"), setOf("rabbit", "rabbits"), setOf("dog", "dogs", "canine"),
        setOf("human", "humans"), setOf("animal", "animals")
    )
    private fun contains(text: String, phrase: String) = phrase.isNotBlank() && Regex("(?iu)(?<![\\p{L}\\p{N}])${Regex.escape(phrase)}(?![\\p{L}\\p{N}])").containsMatchIn(text)
    private fun populationLabels(text: String): Set<Int> = populationFamilies.indices.filter { index -> populationFamilies[index].any { contains(text, it) } }.toSet()

    fun issues(claim: String, passages: List<String>, facts: List<ResearchClaimFact> = emptyList()): List<String> {
        val sourceQuantities = passages.flatMap { quantities(it) }.toSet()
        val sourceComparisons = passages.flatMap { comparisons(it) }.toSet()
        return buildList {
            if (quantities(claim).any { it !in sourceQuantities }) add("NUMERICAL_OUTCOME_UNIT_MISMATCH")
            if (comparisons(claim).any { it !in sourceComparisons }) add("NUMERICAL_COMPARATOR_ORDER_MISMATCH")
            // Legacy records have no relation proof. Their literal checks remain compatible;
            // the packet labels their relation metadata UNKNOWN and semantic validation false.
            if (facts.isNotEmpty()) {
                claim.split(Regex("(?<=[.!?;])\\s+|\\n+")).filter(String::isNotBlank).forEach { sentence ->
                    quantities(sentence).forEach quantity@{ pair ->
                        val matching = facts.filter { (number(it.value) to unit(it.unit)) == pair }
                        if (matching.isEmpty()) {
                            if (Regex("(?i)improv|reduc|increase|decrease|score|outcome|effect").containsMatchIn(sentence)) add("NUMERICAL_RELATION_METADATA_REQUIRED")
                            return@quantity
                        }
                        // A literal sentence may be quoted without inventing its missing labels.
                        if (matching.any { sentence.trim() in it.quote }) return@quantity
                        var candidates = matching
                        val populations = facts.map { it.population }.filter(String::isNotBlank).distinct().filter { contains(sentence, it) }
                        val populationKinds = populationLabels(sentence)
                        val outcomes = facts.map { it.outcome }.filter(String::isNotBlank).distinct().filter { contains(sentence, it) }
                        val interventions = facts.map { it.intervention }.filter(String::isNotBlank).distinct().filter { contains(sentence, it) }
                        fun restrict(code: String, asserted: Boolean, get: (ResearchClaimFact) -> String, fits: (ResearchClaimFact) -> Boolean) {
                            if (!asserted || candidates.isEmpty()) return
                            val remaining = candidates.filter(fits)
                            if (remaining.isEmpty()) add(if (candidates.any { get(it).isBlank() }) "NUMERICAL_RELATION_METADATA_REQUIRED" else code)
                            candidates = remaining
                        }
                        restrict("NUMERICAL_POPULATION_MISMATCH", populations.isNotEmpty() || populationKinds.isNotEmpty(), { it.population }) { fact ->
                            populations.all { contains(fact.population, it) } && populationKinds.all { it in populationLabels(fact.population) }
                        }
                        restrict("NUMERICAL_INTERVENTION_MISMATCH", interventions.isNotEmpty(), { it.intervention }) { fact -> interventions.all { contains(fact.intervention, it) } }
                        restrict("NUMERICAL_OUTCOME_MISMATCH", outcomes.isNotEmpty(), { it.outcome }) { fact -> outcomes.all { contains(fact.outcome, it) } }
                        val comparatorLabels = facts.map { it.comparator }.filter(String::isNotBlank).distinct().filter { contains(sentence, it) }
                        restrict("NUMERICAL_COMPARATOR_RELATION_MISMATCH", comparatorLabels.isNotEmpty(), { it.comparator }) { fact -> comparatorLabels.all { contains(fact.comparator, it) } }
                        if (candidates.isNotEmpty() && candidates.none { fact -> fact.qualifiers.all { contains(sentence, it) } }) add("NUMERICAL_QUALIFIER_MISSING")
                        if (candidates.isNotEmpty() && populations.isEmpty() && populationKinds.isEmpty() && candidates.any { it.population.isNotBlank() }) add("NUMERICAL_RELATION_METADATA_REQUIRED")
                        if (candidates.isNotEmpty() && outcomes.isEmpty() && candidates.any { it.outcome.isNotBlank() } && Regex("(?i)score|outcome|improv|reduc|increase|decrease").containsMatchIn(sentence)) add("NUMERICAL_RELATION_METADATA_REQUIRED")
                    }
                }
            }
        }.distinct()
    }
}
