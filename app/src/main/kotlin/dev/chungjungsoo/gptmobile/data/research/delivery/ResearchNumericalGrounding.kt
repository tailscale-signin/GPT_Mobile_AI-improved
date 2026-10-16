package dev.chungjungsoo.gptmobile.data.research.delivery

import java.util.Locale

/** Conservative value/unit and ordered-comparator checks against passages, never bibliography numbers. */
internal object ResearchNumericalGrounding {
    private const val NUMBER = "[-+]?\\d+(?:\\.\\d+)?"
    private val quantity = Regex("(?i)(?<![\\p{L}\\p{N}])($NUMBER)\\s*(%|participants?|patients?|subjects?|volunteers?|points?|mg|kg|g|ml|litres?|liters?|mmhg|hours?|days?|weeks?|months?|years?)(?![\\p{L}\\p{N}])")
    private val comparator = Regex("(?i)(?<![\\p{L}\\p{N}])($NUMBER)\\s*(?:versus|vs\\.?)\\s*($NUMBER)(?![\\p{L}\\p{N}])")
    private fun number(value: String): String = value.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: value
    private fun unit(value: String): String = when (val normalized = value.lowercase(Locale.ROOT).removeSuffix("s")) {
        "patient", "participant", "subject", "volunteer" -> "participant"
        "liter", "litre" -> "litre"
        else -> normalized
    }
    private fun quantities(text: String) = quantity.findAll(text).map { number(it.groupValues[1]) to unit(it.groupValues[2]) }.toSet()
    private fun comparisons(text: String) = comparator.findAll(text).map { number(it.groupValues[1]) to number(it.groupValues[2]) }.toSet()

    fun issues(claim: String, passages: List<String>): List<String> {
        val sourceQuantities = passages.flatMap { quantities(it) }.toSet()
        val sourceComparisons = passages.flatMap { comparisons(it) }.toSet()
        return buildList {
            if (quantities(claim).any { it !in sourceQuantities }) add("NUMERICAL_OUTCOME_UNIT_MISMATCH")
            if (comparisons(claim).any { it !in sourceComparisons }) add("NUMERICAL_COMPARATOR_ORDER_MISMATCH")
        }
    }
}
