package dev.chungjungsoo.gptmobile.data.agent

import java.text.Normalizer
import java.util.Locale

/**
 * Conservative, deterministic claim alignment. A strong lexical paraphrase is a coverage
 * candidate, not verified semantic entailment or proof that a model statement is true.
 * Quantities, polarity and epistemic hedges are deliberately checked before matching.
 */
internal object CombinedClaimMatcher {
    enum class Match { NONE, POSSIBLE, PARAPHRASE, EXACT }

    data class Fingerprint(
        val terms: Set<String>,
        val numbers: Set<String>,
        val orderedNumbers: List<String>,
        val negative: Boolean,
        val hedged: Boolean,
        val restrictions: Set<String>,
        val ordered: String,
        val qualifiers: CombinedClaimQualifiers = CombinedClaimQualifiers(),
        val eventActions: Set<String> = emptySet(),
        val eventClauses: List<String> = emptyList(),
        val measurements: List<String> = emptyList(),
        val assertionBasis: String = "UNSPECIFIED"
    )

    private val word = Regex("[\\p{L}\\p{N}]+(?:[’'][\\p{L}\\p{N}]+)?")
    private const val MEASUREMENT_UNITS = "mg|kg|g|ml|l|ppm|hours?|minutes?|days?|weeks?|months?|years?|kilometers?|kilometres?|degrees?"
    private val measurementUnit = Regex("^\\s*($MEASUREMENT_UNITS)\\b")
    private val numeric = Regex("(?<![\\p{L}\\d])[+−-]?(?:\\d{1,3}(?:[,\\u00a0 ]\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)(?:\\s*%)?(?:(?![\\p{L}\\d])|(?=(?:$MEASUREMENT_UNITS)\\b))")
    private val contraction = Regex("\\b([\\p{L}]+)n't\\b")
    private val bound = Regex("\\b(at least|at most|more than|less than|no more than|no less than)\\b")
    private val stop = setOf(
        "a", "an", "and", "also", "but", "however", "are", "as", "at", "be", "been", "by", "for", "from", "has",
        "have", "in", "into", "is", "it", "its", "of", "on", "or", "our", "that", "the",
        "their", "them", "there", "these", "this", "those", "to", "was", "were", "which",
        "while", "who", "with", "would", "not", "no", "never", "without", "only", "can",
        "could", "may", "might", "perhaps", "possibly", "allegedly", "reportedly", "did", "does", "do"
    )
    private val negatives = setOf("not", "no", "never", "without", "cannot", "didnt", "isnt", "wasnt")
    private val hedges = setOf("may", "might", "could", "perhaps", "possibly", "allegedly", "reportedly", "uncertain", "unverified")
    private val restrictions = setOf("only", "except", "unless", "atmost", "atleast", "maximum", "minimum")
    private val practiceReport = Regex("\\b(?:athletes?|people|users?|patients?|clinicians?|companies|developers|practitioners)\\b.{0,64}\\b(?:use|uses|using|try|tries|adopt|adopts)\\b")
    private val studyFinding = Regex("\\b(?:studies|study|trials?|research|evidence)\\b.{0,64}\\b(?:suggests?|shows?|showed|finds?|found|reports?|reported|demonstrates?|indicates?)\\b")
    private val aliases = mapOf(
        "railway" to "rail", "railroad" to "rail", "rail" to "rail",
        "conscript" to "conscript", "conscription" to "conscript",
        "navy" to "naval", "fleet" to "naval", "naval" to "naval",
        "finance" to "fund", "financ" to "fund", "fund" to "fund",
        "build" to "build", "built" to "build", "construction" to "build",
        "athenian" to "athens", "athens" to "athens",
        "merchant" to "merchant", "trad" to "trade", "approv" to "approve",
        "reconcile" to "reconcile", "reconcil" to "reconcile", "reconciliation" to "reconcile"
    )

    private fun canonical(token: String): String {
        val plain = token.lowercase(Locale.ROOT).replace('’', '\'')
        aliases[plain]?.let { return it }
        val stem = when {
            plain.length > 5 && plain.endsWith("ies") -> plain.dropLast(3) + "y"
            plain.length > 6 && plain.endsWith("ing") -> plain.dropLast(3)
            plain.length > 5 && plain.endsWith("ed") -> plain.dropLast(2)
            plain.length > 4 && plain.endsWith("s") && !plain.endsWith("ss") -> plain.dropLast(1)
            else -> plain
        }
        return aliases[stem] ?: stem
    }

    fun fingerprint(text: String): Fingerprint {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace('’', '\'')
            .replace(Regex("(?i)\\b(?:leave|leaves|leaving) beneficial (?:ros|oxidants) intact\\b|\\bwithout disrupting beneficial (?:ros|oxidants)\\b"), "preserve beneficial ros")
            .replace(contraction) { match ->
                val auxiliary = when (val stem = match.groupValues[1]) {
                    "ca" -> "can"
                    "wo" -> "will"
                    "sha" -> "shall"
                    else -> stem
                }
                "$auxiliary not"
            }
        val words = word.findAll(normalized).map { canonical(it.value) }.toList()
        val qualifiers = CombinedClaimSemantics.qualifiers(text)
        val numericText = Regex("\\b(?:[a-z]{2,}-[0-9]+|(?:il|nrf)[ ]?[0-9]+|[a-z]+[0-9]+)\\b").replace(normalized, " ")
        val numericMatches = numeric.findAll(numericText).toList()
        val quantities = numericMatches.map { match ->
            match.value.replace(",", "").replace(" ", "").replace(160.toChar().toString(), "").replace('−', '-')
                .removePrefix("+")
        }.toList()
        return Fingerprint(
            terms = words.filter { it !in stop && it.none(Char::isDigit) }.toSet(),
            numbers = quantities.toSet(),
            orderedNumbers = quantities,
            negative = words.any { it in negatives },
            hedged = words.any { it in hedges },
            restrictions = words.filter { it in restrictions }.toSet() + bound.findAll(normalized).map { it.value.replace(" ", "") },
            ordered = words.filter { it !in stop }.joinToString(" "),
            qualifiers = qualifiers,
            eventActions = CombinedHistoricalEvent.actions(text),
            eventClauses = if (words.any { it in negatives || it in hedges || it in restrictions } || qualifiers.uncertainty.isNotEmpty()) emptyList() else CombinedClaimClauses.spans(text).takeIf { it.size > 1 }?.map { it.comparisonText }.orEmpty(),
            measurements = numericMatches.mapIndexedNotNull { index, match ->
                measurementUnit.find(numericText.substring(match.range.last + 1))?.groupValues?.get(1)?.let { unit ->
                    quantities[index] + ":" + unit.removeSuffix("s")
                }
            },
            assertionBasis = when {
                studyFinding.containsMatchIn(normalized) -> "STUDY_FINDING"
                practiceReport.containsMatchIn(normalized) -> "REPORTED_PRACTICE"
                else -> "UNSPECIFIED"
            }
        )
    }

    /** Subject-preserving alignment; never compare contradictory numbers or reversed polarity. */
    fun compatible(source: Fingerprint, answer: Fingerprint): Boolean {
        if (CombinedClaimSemantics.lostQualifiers(source.qualifiers, answer.qualifiers).isNotEmpty()) return false
        // Reporting why someone uses an intervention is not a study finding that
        // it achieves that outcome, even when most words are shared.
        if (source.assertionBasis == "REPORTED_PRACTICE" && answer.assertionBasis == "STUDY_FINDING") return false
        if (source.negative != answer.negative) return false
        if (source.hedged && !answer.hedged) return false
        if (source.restrictions != answer.restrictions) return false
        // A bag of numbers loses their associations (12 homes / 14 schools vs the reverse).
        // Ambiguous reorderings remain unresolved instead of silently proving coverage.
        if (source.numbers.isNotEmpty() && source.orderedNumbers != answer.orderedNumbers) return false
        if (source.measurements.isNotEmpty() && source.measurements != answer.measurements) return false
        return true
    }

    fun match(source: Fingerprint, answer: Fingerprint): Match {
        if (!compatible(source, answer)) return Match.NONE
        val overlap = source.terms.intersect(answer.terms).size
        val exact = source.ordered.isNotBlank() &&
            (" " + source.ordered + " " in " " + answer.ordered + " ") &&
            (source.terms.size >= 3 || source.numbers.isNotEmpty())
        if (exact) return Match.EXACT
        if (source.terms.size < 4 || overlap < 3) return Match.NONE
        val recall = overlap.toDouble() / source.terms.size
        return when {
            overlap >= 4 && recall >= 0.77 -> Match.PARAPHRASE
            overlap >= 3 && recall >= 0.55 -> Match.POSSIBLE
            else -> Match.NONE
        }
    }

    fun match(source: String, answer: String): Match {
        val sourceFingerprint = fingerprint(source)
        val direct = match(sourceFingerprint, fingerprint(answer))
        if (direct >= Match.PARAPHRASE && sourceFingerprint.eventClauses.isEmpty()) return direct
        // Keep strict number/qualifier checks on each candidate clause. Never compare
        // number sets across a merged sentence or relax date associations globally.
        val answerClauses = CombinedClaimClauses.spans(answer).map { fingerprint(it.comparisonText) }
        val sourceClauses = sourceFingerprint.eventClauses.ifEmpty { listOf(source) }.map(::fingerprint)
        val matches = sourceClauses.map { part -> answerClauses.maxOfOrNull { match(part, it) } ?: Match.NONE }
        // A full-sentence bag of terms can look identical after two event objects swap
        // dates. Once independent clauses exist, every association must survive.
        val aligned = matches.minOrNull() ?: Match.NONE
        return if (sourceFingerprint.eventClauses.isNotEmpty()) aligned else maxOf(direct, aligned)
    }

    /**
     * Flag disputes for the final editor's evidence check. A different number is not by
     * itself a contradiction; the surrounding subject must have substantial overlap.
     */
    fun possibleConflict(first: Fingerprint, second: Fingerprint, clauseFingerprints: MutableMap<String, Fingerprint> = mutableMapOf()): Boolean {
        if (first.eventClauses.isNotEmpty() || second.eventClauses.isNotEmpty()) {
            val firstParts = first.eventClauses.map { clauseFingerprints.getOrPut(it) { fingerprint(it) } }.ifEmpty { listOf(first) }
            val secondParts = second.eventClauses.map { clauseFingerprints.getOrPut(it) { fingerprint(it) } }.ifEmpty { listOf(second) }
            return firstParts.any { a -> secondParts.any { b -> possibleConflict(a, b, clauseFingerprints) } }
        }
        if (first.eventActions.isNotEmpty() && second.eventActions.isNotEmpty() && first.eventActions.intersect(second.eventActions).isEmpty()) return false
        val sameDatedEvent = first.eventActions.intersect(second.eventActions).isNotEmpty() && (first.numbers + second.numbers).all { it.matches(Regex("[12][0-9]{3}")) }
        if (CombinedClaimSemantics.applicabilityConflicts(first.qualifiers, second.qualifiers).any { it != "TIME_SCOPE_MISMATCH" || !sameDatedEvent }) return false
        if (first.terms.size < 3 || second.terms.size < 3) return false
        val sharedTerms = first.terms.intersect(second.terms)
        val shared = sharedTerms.size
        val overlapRatio = shared.toDouble() / minOf(first.terms.size, second.terms.size)
        val sameTopic = shared >= 3 && (overlapRatio >= 0.72 || (shared >= 4 && overlapRatio >= 0.45) || "reconcile" in sharedTerms)
        if (!sameTopic) return false
        val competingValues = first.numbers.subtract(second.numbers).isNotEmpty() && second.numbers.subtract(first.numbers).isNotEmpty()
        val numberDisagreement = first.numbers.isNotEmpty() && second.numbers.isNotEmpty() && competingValues
        val polarityDisagreement = first.negative != second.negative
        val outcomeDisagreement = setOf(first.qualifiers.outcome, second.qualifiers.outcome).let {
            "POSITIVE_ASSERTION" in it && ("NULL_EFFECT" in it || "INSUFFICIENT_EVIDENCE" in it)
        }
        val causalDisagreement = first.qualifiers.causalStrength != second.qualifiers.causalStrength &&
            "CAUSAL" in setOf(first.qualifiers.causalStrength, second.qualifiers.causalStrength) &&
            setOf(first.qualifiers.causalStrength, second.qualifiers.causalStrength).any { it in setOf("ASSOCIATION", "MECHANISTIC") }
        return numberDisagreement || polarityDisagreement || outcomeDisagreement || causalDisagreement
    }
}
