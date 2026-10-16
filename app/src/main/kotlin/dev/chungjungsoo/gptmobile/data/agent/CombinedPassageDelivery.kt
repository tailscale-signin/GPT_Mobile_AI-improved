package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class CombinedPassageRecord(
    val start: Int,
    val end: Int,
    val digest: String,
    val profileUids: Set<String>,
    val sourceIds: Set<String> = emptySet(),
    val claimIds: Set<String> = emptySet(),
    val evidenceStatus: String = "UNVERIFIED",
    val origin: String = "SYNTHESIS_DECLARATION"
) {
    val primaryProfileUid: String? get() = profileUids.sorted().firstOrNull()
    fun matches(answer: String): Boolean = start >= 0 && end > start && end <= answer.length && researchHash(answer.substring(start, end)) == digest
}

@Serializable
data class CombinedClaimReview(val claimId: String, val disposition: String, val reason: String)

/** Completion, textual accounting, evidence support and semantic truth are separate dimensions. */
@Serializable
data class CombinedQualityReport(
    val version: Int = 2,
    val answerDigest: String = "",
    val complete: Boolean = false,
    val status: String = "WRITING",
    val wordCount: Int = 0,
    val issues: List<String> = emptyList(),
    val passages: List<CombinedPassageRecord> = emptyList(),
    val decisions: List<CombinedClaimReview> = emptyList(),
    val sourceUnits: Int = 0,
    val lexicalMissing: Int = 0,
    val semanticCoverageVerified: Boolean = false,
    val factualAccuracyVerified: Boolean = false,
    val duplicatePassagesRemoved: Int = 0,
    val repairRounds: Int = 0,
    val turnContext: CombinedTurnContext? = null
)

/** One NDJSON record per paragraph permits progressive delivery without leaking protocol data. */
internal class CombinedPassageDecoder(
    private val sources: List<CombinedModelResponse>,
    private val evidence: CombinedEvidenceLedger,
    private val maxCharacters: Int
) {
    private val pending = StringBuilder()
    private val output = StringBuilder()
    private val seen = mutableListOf<String>()
    val passages = mutableListOf<CombinedPassageRecord>()
    val decisions = mutableListOf<CombinedClaimReview>()
    val issues = linkedSetOf<String>()
    var duplicatesRemoved = 0
        private set
    private var mode: Boolean? = null
    private var plainRecoveryBlocked = false
    private val sourceIds = evidence.records.associateBy { it.id }
    private val claimIds = sources.flatMapIndexed { index, source ->
        CombinedContentCoverage.sentences(CombinedContentCoverage.sourceCoverageText(source.content)).indices.map { "C${index + 1}.${it + 1}" }
    }.toSet()

    fun accept(delta: String, final: Boolean = false): List<String> {
        pending.append(delta)
        if (mode == null && pending.isNotBlank()) mode = pending.toString().trimStart().let { it.startsWith("{") || it.startsWith("```") }
        if (mode == false) {
            issues += "PLAIN_TEXT_FALLBACK_NO_EXPLICIT_ATTRIBUTION"
            if (plainRecoveryBlocked) {
                pending.setLength(0)
                return emptyList()
            }
            val marker = pending.indexOf(COMBINED_RECOVERY_MARKER)
            val end = if (marker >= 0) {
                marker
            } else if (final) {
                pending.length
            } else {
                (pending.length - COMBINED_RECOVERY_MARKER.length).coerceAtLeast(0)
            }
            val text = pending.substring(0, end)
            if (marker >= 0) {
                plainRecoveryBlocked = true
                issues += "INTERNAL_RECOVERY_MARKER_REJECTED"
                pending.setLength(0)
            } else {
                pending.delete(0, end)
            }
            require(output.length.toLong() + text.length <= maxCharacters) { "COMBINED_DELIVERY_LIMIT" }
            output.append(text)
            return listOf(text).filter(String::isNotEmpty)
        }
        val deltas = mutableListOf<String>()
        while (true) {
            val end = pending.indexOf("\n").takeIf { it >= 0 } ?: if (final && pending.isNotEmpty()) pending.length else break
            val line = pending.substring(0, end).trim()
            pending.delete(0, minOf(end + 1, pending.length))
            if (line.isBlank() || line.startsWith("```")) continue
            val row = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            if (row == null) {
                issues += "INVALID_DELIVERY_RECORD"
                continue
            }
            fun text(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            fun strings(key: String) = (row[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
            if (text("type") == "decision") {
                val id = text("claim")
                val disposition = text("disposition")
                if (id in claimIds && disposition in setOf("MERGED", "OUT_OF_SCOPE", "UNSUPPORTED", "CONFLICT", "MISSING") && text("reason").isNotBlank()) {
                    decisions.removeAll { it.claimId == id }
                    decisions += CombinedClaimReview(id, disposition, text("reason"))
                } else {
                    issues += "INVALID_CLAIM_DECISION"
                }
                continue
            }
            if (text("type") != "passage" || text("text").isBlank()) {
                issues += "INVALID_DELIVERY_RECORD"
                continue
            }
            val body = text("text").trim()
            if (body.contains(COMBINED_RECOVERY_MARKER)) {
                issues += "INTERNAL_RECOVERY_MARKER_REJECTED"
                continue
            }
            val duplicate = seen.any { existing ->
                CombinedContentCoverage.normalized(existing) == CombinedContentCoverage.normalized(body) ||
                    CombinedClaimMatcher.match(existing, body) >= CombinedClaimMatcher.Match.PARAPHRASE &&
                    CombinedClaimMatcher.match(body, existing) >= CombinedClaimMatcher.Match.PARAPHRASE
            }
            if (duplicate) {
                duplicatesRemoved++
                continue
            }
            val separator = if (output.isEmpty()) "" else "\n\n"
            val requested = strings("profiles")
            val profiles = requested.mapNotNull { id -> id.removePrefix("C").toIntOrNull()?.let { sources.getOrNull(it - 1)?.platformUid } }.toSet()
            if (profiles.size != requested.size) issues += "UNKNOWN_PROFILE_REFERENCE"
            val cited = strings("sources")
            if (cited.any { it !in sourceIds }) issues += "UNKNOWN_SOURCE_REFERENCE"
            val validSources = cited.filter { it in sourceIds }.toSet()
            val claims = strings("claims")
            if (claims.any { it !in claimIds }) issues += "UNKNOWN_CLAIM_REFERENCE"
            val retainedClaims = claims.filter { it in claimIds }.toSet()
            val claimProfiles = retainedClaims.mapNotNull { id -> id.substringBefore('.').removePrefix("C").toIntOrNull()?.let { sources.getOrNull(it - 1)?.platformUid } }.toSet()
            val owners = profiles.intersect(claimProfiles)
            if (profiles.isNotEmpty() && owners.isEmpty()) issues += "MISSING_CONTRIBUTION_LINK"
            // Citations are app-resolved URLs, never model-authored source links.
            val citations = validSources.sorted().joinToString(" ") { id -> "[$id](${sourceIds.getValue(id).url})" }
            val bodyUrls = Regex("https?://[^\\s<>\\[\\]{}]+", RegexOption.IGNORE_CASE).findAll(body).map { it.value.trimEnd('.', ',', ';', ':', ')') }.toSet()
            if (bodyUrls.any { url -> evidence.records.none { it.url == url } }) issues += "UNRESOLVED_CITATION_URL"
            val rendered = body + if (citations.isBlank()) "" else "\n\n$citations"
            require(output.length.toLong() + separator.length + rendered.length <= maxCharacters) { "COMBINED_DELIVERY_LIMIT" }
            output.append(separator)
            val start = output.length
            output.append(body)
            val supported = validSources.any { id ->
                val source = sourceIds.getValue(id)
                source.readable &&
                    CombinedContentCoverage.sentences(body).all { sentence ->
                        CombinedContentCoverage.normalized(source.passage).contains(CombinedContentCoverage.normalized(sentence))
                    }
            }
            passages += CombinedPassageRecord(
                start,
                output.length,
                researchHash(body),
                owners,
                validSources,
                retainedClaims,
                if (supported) {
                    "PASSAGE_SUPPORTED"
                } else if (validSources.isNotEmpty()) {
                    "CITED_UNVERIFIED"
                } else {
                    "UNVERIFIED"
                }
            )
            if (citations.isNotBlank()) output.append("\n\n$citations")
            seen += body
            deltas += separator + rendered
        }
        require(pending.length.toLong() + output.length <= maxCharacters.toLong() + 65_536) { "COMBINED_RECORD_LIMIT" }
        return deltas
    }

    fun answer(): String = output.toString()
}
