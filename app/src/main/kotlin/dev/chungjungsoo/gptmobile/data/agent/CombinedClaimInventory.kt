package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.text.BreakIterator
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CombinedSourceSection(
    val id: String,
    val profileUid: String,
    val bodyDigest: String,
    val startUtf16: Int,
    val endUtf16: Int,
    val startUtf8: Long,
    val endUtf8: Long,
    val textDigest: String
)

/** Complete source occurrences, including partial contributions. This is not semantic verification. */
@Serializable
data class CombinedClaimInventory(
    val version: Int = 1,
    val corpusDigest: String,
    val sourceDigests: Map<String, String>,
    val sections: List<CombinedSourceSection>
) {
    fun page(offset: Int, size: Int): List<CombinedSourceSection> {
        require(offset >= 0 && size in 1..256)
        return sections.drop(offset).take(size)
    }

    fun resolve(section: CombinedSourceSection, sources: List<CombinedModelResponse>): String? {
        if (section !in sections) return null
        val source = sources.singleOrNull { it.platformUid == section.profileUid } ?: return null
        if (researchHash(source.content) != section.bodyDigest) return null
        if (section.startUtf16 < 0 || section.endUtf16 !in section.startUtf16..source.content.length) return null
        return source.content.substring(section.startUtf16, section.endUtf16).takeIf { researchHash(it) == section.textDigest }
    }

    companion object {
        fun freeze(sources: List<CombinedModelResponse>): CombinedClaimInventory {
            require(sources.map { it.platformUid }.distinct().size == sources.size) { "Ambiguous contributor identity" }
            val digests = sources.associate { it.platformUid to researchHash(it.content) }
            val sections = buildList {
                sources.forEach { source ->
                    val bodyDigest = digests.getValue(source.platformUid)
                    val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(source.content) }
                    var start = 0
                    var bytes = 0L
                    while (start < source.content.length) {
                        val lineEnd = source.content.indexOf('\n', start).takeIf { it >= 0 }?.plus(1) ?: source.content.length
                        val proposedEnd = minOf(lineEnd.toLong(), start.toLong() + 4096).toInt()
                        val end = if (boundaries.isBoundary(proposedEnd)) proposedEnd else boundaries.following(proposedEnd)
                        val text = source.content.substring(start, end)
                        val nextBytes = Math.addExact(bytes, text.toByteArray(Charsets.UTF_8).size.toLong())
                        // Whitespace is retained too, so reconstruction and extraction reconciliation have no holes.
                        add(CombinedSourceSection(researchHash("${source.platformUid}|$bodyDigest|$start|$end"), source.platformUid, bodyDigest, start, end, bytes, nextBytes, researchHash(text)))
                        start = end
                        bytes = nextBytes
                    }
                }
            }
            return CombinedClaimInventory(corpusDigest = researchHash(Json.encodeToString(digests) + Json.encodeToString(sections)), sourceDigests = digests, sections = sections)
        }
    }
}

@Serializable
enum class CombinedClaimDisposition { INCLUDED, MERGED_EQUIVALENT, CONFLICT_EXPLAINED, EXCLUDED_IRRELEVANT, EXCLUDED_UNSUPPORTED, WITHHELD_BY_POLICY, MISSING }

/** Exact ranges bind proposed atomic claims to source and output, not to model-generated quote strings. */
@Serializable
data class CombinedCoverageLink(val startUtf16: Int, val endUtf16: Int, val textDigest: String)

@Serializable
data class CombinedClaimDecision(
    val id: String,
    val sectionId: String,
    val source: CombinedCoverageLink,
    val disposition: CombinedClaimDisposition,
    val output: List<CombinedCoverageLink> = emptyList(),
    val reason: String = ""
)

@Serializable
data class CombinedCoverageProposal(
    val corpusDigest: String,
    val answerDigest: String,
    val policyRevision: String,
    val decisions: List<CombinedClaimDecision>
)

data class CombinedCoverageValidation(
    val structuralErrors: List<String>,
    val missingSectionIds: Set<String>,
    val includedClaims: Int,
    val excludedClaims: Int,
    val missingClaims: Int,
    val totalClaims: Int
) {
    val structurallyAccounted: Boolean get() = structuralErrors.isEmpty() && missingSectionIds.isEmpty() && missingClaims == 0 && totalClaims > 0

    // Explicit exclusions increase accounting, never inclusion. Semantic auditing is a separate requirement.
    val includedFraction: Double? get() = if (structuralErrors.isNotEmpty() || missingSectionIds.isNotEmpty()) {
        null
    } else if (totalClaims == 0) {
        0.0
    } else {
        includedClaims.toDouble() / totalClaims
    }
}

internal object CombinedCoverageValidator {
    fun validate(
        inventory: CombinedClaimInventory,
        sources: List<CombinedModelResponse>,
        answer: String,
        proposal: CombinedCoverageProposal,
        policyRevision: String,
        withheldSectionIds: Set<String> = emptySet()
    ): CombinedCoverageValidation {
        val errors = mutableListOf<String>()
        val missing = linkedSetOf<String>()
        if (runCatching { CombinedClaimInventory.freeze(sources) }.getOrNull() != inventory || proposal.corpusDigest != inventory.corpusDigest || proposal.answerDigest != researchHash(answer) || proposal.policyRevision != policyRevision) {
            return CombinedCoverageValidation(listOf("Stale corpus, answer or policy"), inventory.sections.map { it.id }.toSet(), 0, 0, proposal.decisions.size, proposal.decisions.size)
        }
        val sections = inventory.sections.associateBy { it.id }
        val sourcesByUid = sources.associateBy { it.platformUid }
        val boundaries = sourcesByUid.mapValues { (_, source) -> BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(source.content) } }
        val answerBoundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(answer) }
        fun valid(text: String, range: CombinedCoverageLink, chars: BreakIterator): Boolean = range.startUtf16 >= 0 && range.endUtf16 > range.startUtf16 && range.endUtf16 <= text.length && chars.isBoundary(range.startUtf16) && chars.isBoundary(range.endUtf16) && researchHash(text.substring(range.startUtf16, range.endUtf16)) == range.textDigest
        if (proposal.decisions.any { it.id.isBlank() } || proposal.decisions.map { it.id }.distinct().size != proposal.decisions.size) errors += "Missing or duplicate claim identity"
        if (proposal.decisions.any { it.sectionId !in sections }) errors += "Unknown source section"
        val bySection = proposal.decisions.groupBy { it.sectionId }
        inventory.sections.forEach { section ->
            val text = sourcesByUid[section.profileUid]?.content.orEmpty()
            val claims = bySection[section.id].orEmpty().sortedBy { it.source.startUtf16 }
            var cursor = section.startUtf16
            var complete = section.endUtf16 <= text.length
            claims.forEach { claim ->
                val range = claim.source
                if (range.startUtf16 < cursor || range.endUtf16 > section.endUtf16 || boundaries[section.profileUid]?.let { valid(text, range, it) } != true) {
                    errors += "Invalid source range: ${claim.id}"
                    complete = false
                } else {
                    if (!text.substring(cursor, range.startUtf16).isBlank()) complete = false
                    cursor = range.endUtf16
                }
                val hasOutput = claim.disposition in setOf(CombinedClaimDisposition.INCLUDED, CombinedClaimDisposition.MERGED_EQUIVALENT, CombinedClaimDisposition.CONFLICT_EXPLAINED)
                if (hasOutput && (claim.output.isEmpty() || claim.output.any { !valid(answer, it, answerBoundaries) })) errors += "Missing or stale output link: ${claim.id}"
                if (!hasOutput && claim.output.isNotEmpty()) errors += "Exclusion or missing claim cannot claim output inclusion: ${claim.id}"
                if (!hasOutput && claim.disposition != CombinedClaimDisposition.MISSING && claim.reason.isBlank()) errors += "Exclusion requires a reason: ${claim.id}"
                if (claim.disposition == CombinedClaimDisposition.WITHHELD_BY_POLICY && section.id !in withheldSectionIds) errors += "Model cannot grant itself a policy exclusion: ${claim.id}"
                if (section.id in withheldSectionIds && claim.disposition != CombinedClaimDisposition.WITHHELD_BY_POLICY) errors += "Policy-withheld material cannot be included: ${claim.id}"
            }
            if (cursor !in 0..text.length || section.endUtf16 !in cursor..text.length || !text.substring(cursor, section.endUtf16).isBlank()) complete = false
            if (!complete) missing += section.id
        }
        val included = proposal.decisions.count { it.disposition in setOf(CombinedClaimDisposition.INCLUDED, CombinedClaimDisposition.MERGED_EQUIVALENT, CombinedClaimDisposition.CONFLICT_EXPLAINED) }
        val absent = proposal.decisions.count { it.disposition == CombinedClaimDisposition.MISSING }
        return CombinedCoverageValidation(errors.distinct(), missing, included, proposal.decisions.size - included - absent, absent, proposal.decisions.size)
    }
}
