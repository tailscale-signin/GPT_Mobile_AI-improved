package dev.chungjungsoo.gptmobile.data.research

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Literal attributes of one captured finding. Empty attributes remain unknown, not inferred. */
data class ResearchClaimFact(
    val value: String = "",
    val unit: String = "",
    val population: String = "",
    val intervention: String = "",
    val outcome: String = "",
    val comparator: String = "",
    val qualifiers: List<String> = emptyList(),
    val sourceId: String = "",
    val sourceVersion: String = "",
    val passageHash: String = "",
    val locator: String = "",
    val quote: String = "",
    val quoteStart: Int = -1,
    val quoteEnd: Int = -1
) {
    fun json() = buildJsonObject {
        put("value", value)
        put("unit", unit)
        put("population", population)
        put("intervention", intervention)
        put("outcome", outcome)
        put("comparator", comparator)
        put("qualifiers", JsonArray(qualifiers.map(::JsonPrimitive)))
        put("sourceId", sourceId)
        put("sourceVersion", sourceVersion)
        put("passageHash", passageHash)
        put("locator", locator)
        put("quote", quote)
        put("quoteStart", quoteStart)
        put("quoteEnd", quoteEnd)
        put("relationMetadata", if (population.isBlank() || outcome.isBlank()) "PARTIAL_OR_UNKNOWN" else "CAPTURE_BOUND")
    }

    fun matchesCapture(source: ResearchSource, claimQuote: String): Boolean =
        sourceId == source.id &&
            sourceVersion == capturedResearchVersion(source) &&
            passageHash == researchHash(source.passage) &&
            quote.isNotBlank() &&
            quote in claimQuote &&
            quoteStart >= 0 &&
            quoteEnd == quoteStart + quote.length &&
            quoteEnd <= source.passage.length &&
            source.passage.substring(quoteStart, quoteEnd) == quote &&
            literalAttributesMatch(quote)

    private fun literalAttributesMatch(span: String): Boolean {
        val attributes = listOf(value, unit, population, intervention, outcome, comparator) + qualifiers
        if (attributes.all(String::isBlank) || attributes.filter(String::isNotBlank).any { !literalContains(span, it) }) return false
        if (value.isNotBlank() && unit.isNotBlank() && !Regex("(?iu)(?<![\\p{L}\\p{N}])${Regex.escape(value)}\\s*${Regex.escape(unit)}(?![\\p{L}\\p{N}])").containsMatchIn(span)) return false
        // Attributes from separate findings may not be joined just because the same number recurs.
        val relations = listOf(value, unit, population, intervention, outcome, comparator).filter(String::isNotBlank)
        return span.split(Regex("(?<=[.!?;])\\s+|\\n+")).any { sentence -> relations.all { literalContains(sentence, it) } }
    }

    companion object {
        private fun literalContains(text: String, literal: String) = Regex("(?iu)(?<![\\p{L}\\p{N}])${Regex.escape(literal)}(?![\\p{L}\\p{N}])").containsMatchIn(text)

        /** The application supplies the binding; model-supplied bindings must agree exactly. */
        fun bind(row: JsonObject, source: ResearchSource, claimQuote: String): ResearchClaimFact? {
            val quote = row.text("quote").ifBlank { claimQuote }
            val start = source.passage.indexOf(quote)
            if (quote.length !in 1..1200 || quote !in claimQuote || start < 0) return null
            val expectedVersion = capturedResearchVersion(source)
            val expectedHash = researchHash(source.passage)
            if (row.text("sourceId").let { it.isNotBlank() && it != source.id } ||
                row.text("sourceVersion").let { it.isNotBlank() && it != expectedVersion } ||
                row.text("passageHash").let { it.isNotBlank() && it != expectedHash }
            ) {
                return null
            }
            val suppliedStart = (row["quoteStart"] as? JsonPrimitive)?.intOrNull
            val suppliedEnd = (row["quoteEnd"] as? JsonPrimitive)?.intOrNull
            if (suppliedStart != null && suppliedStart != start || suppliedEnd != null && suppliedEnd != start + quote.length) return null
            val fields = listOf("value", "unit", "population", "intervention", "outcome", "comparator")
            if (fields.any { row.text(it).length > 300 } || row.strings("qualifiers").let { it.size > 6 || it.any { qualifier -> qualifier.length > 300 } }) return null
            val fact = ResearchClaimFact(
                row.text("value").trim(), row.text("unit").trim(), row.text("population").trim(), row.text("intervention").trim(),
                row.text("outcome").trim(), row.text("comparator").trim(), row.strings("qualifiers").map(String::trim),
                source.id, expectedVersion, expectedHash, "${source.location.take(240)}; characters $start-${start + quote.length}", quote, start, start + quote.length
            )
            return fact.takeIf { it.matchesCapture(source, claimQuote) }
        }
    }
}

/** One version, one literal claim span, and any declared facts must all refer to the same capture. */
fun researchClaimMatchesCapture(claim: ResearchClaim, source: ResearchSource): Boolean =
    source.id == claim.sourceId &&
        source.readable &&
        claim.quote.isNotBlank() &&
        claim.quote in source.passage &&
        (claim.sourceVersion.isBlank() || claim.sourceVersion == capturedResearchVersion(source)) &&
        (claim.passageHash.isBlank() || claim.passageHash == researchHash(source.passage)) &&
        (claim.reviewedSourceVersion.isBlank() || claim.reviewedSourceVersion == capturedResearchVersion(source)) &&
        claim.facts.all { it.matchesCapture(source, claim.quote) }
