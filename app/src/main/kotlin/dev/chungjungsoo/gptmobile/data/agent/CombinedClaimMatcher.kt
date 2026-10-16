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
        val ordered: String
    )

    private val word = Regex("[\\p{L}\\p{N}]+(?:[’'][\\p{L}\\p{N}]+)?")
    private val numeric = Regex("(?<![\\p{L}\\d])[+−-]?(?:\\d{1,3}(?:[,\\u00a0 ]\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)(?:\\s*%)?(?![\\p{L}\\d])")
    private val contraction = Regex("\\b([\\p{L}]+)n't\\b")
    private val bound = Regex("\\b(at least|at most|more than|less than|no more than|no less than)\\b")
    private val stop = setOf(
        "a", "an", "and", "are", "as", "at", "be", "been", "by", "for", "from", "has",
        "have", "in", "into", "is", "it", "its", "of", "on", "or", "our", "that", "the",
        "their", "them", "there", "these", "this", "those", "to", "was", "were", "which",
        "while", "who", "with", "would", "not", "no", "never", "without", "only", "can",
        "could", "may", "might", "perhaps", "possibly", "allegedly", "reportedly", "did", "does", "do"
    )
    private val negatives = setOf("not", "no", "never", "without", "cannot", "didnt", "isnt", "wasnt")
    private val hedges = setOf("may", "might", "could", "perhaps", "possibly", "allegedly", "reportedly", "uncertain", "unverified")
    private val restrictions = setOf("only", "except", "unless", "atmost", "atleast", "maximum", "minimum")
    private val aliases = mapOf(
        "railway" to "rail", "railroad" to "rail", "rail" to "rail",
        "conscript" to "conscript", "conscription" to "conscript",
        "navy" to "naval", "fleet" to "naval", "naval" to "naval",
        "finance" to "fund", "financ" to "fund", "fund" to "fund",
        "build" to "build", "built" to "build", "construction" to "build",
        "athenian" to "athens", "athens" to "athens",
        "merchant" to "merchant", "trad" to "trade", "approv" to "approve"
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
        val quantities = numeric.findAll(normalized).map { match ->
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
            ordered = words.filter { it !in stop }.joinToString(" ")
        )
    }

    /** Subject-preserving alignment; never compare contradictory numbers or reversed polarity. */
    fun match(source: Fingerprint, answer: Fingerprint): Match {
        if (source.negative != answer.negative) return Match.NONE
        if (source.hedged && !answer.hedged) return Match.NONE
        if (source.restrictions != answer.restrictions) return Match.NONE
        // A bag of numbers loses their associations (12 homes / 14 schools vs the reverse).
        // Ambiguous reorderings remain unresolved instead of silently proving coverage.
        if (source.numbers.isNotEmpty() && source.orderedNumbers != answer.orderedNumbers) return Match.NONE
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

    fun match(source: String, answer: String): Match = match(fingerprint(source), fingerprint(answer))

    /**
     * Flag disputes for the final editor's evidence check. A different number is not by
     * itself a contradiction; the surrounding subject must have substantial overlap.
     */
    fun possibleConflict(first: Fingerprint, second: Fingerprint): Boolean {
        if (first.terms.size < 3 || second.terms.size < 3) return false
        val shared = first.terms.intersect(second.terms).size
        val overlapRatio = shared.toDouble() / minOf(first.terms.size, second.terms.size)
        val sameTopic = shared >= 3 && (overlapRatio >= 0.72 || (shared >= 4 && overlapRatio >= 0.45))
        if (!sameTopic) return false
        val competingValues = first.numbers.subtract(second.numbers).isNotEmpty() && second.numbers.subtract(first.numbers).isNotEmpty()
        val numberDisagreement = first.numbers.isNotEmpty() && second.numbers.isNotEmpty() && competingValues
        val polarityDisagreement = first.negative != second.negative
        return numberDisagreement || polarityDisagreement
    }
}
