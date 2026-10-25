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
    val origin: String = "SYNTHESIS_DECLARATION",
    val passageId: String = "",
    val semanticLinkStatus: String = "UNVERIFIED",
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val linkVersion: Int = 0,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val links: List<CombinedPassageLink> = emptyList()
) {
    val primaryProfileUid: String? get() = profileUids.sorted().firstOrNull()
    fun matches(answer: String): Boolean = start >= 0 && end > start && end <= answer.length && researchHash(answer.substring(start, end)) == digest
}

@Serializable
data class CombinedPassageLink(
    val start: Int,
    val end: Int,
    val digest: String,
    val profileUids: Set<String>,
    val claimIds: Set<String>,
    val identityLinkStatus: String = "KNOWN_OCCURRENCES",
    val semanticSupportStatus: String = "CONSERVATIVE_LINK_CHECKED"
) {
    fun matches(answer: String): Boolean = start >= 0 && end > start && end <= answer.length && researchHash(answer.substring(start, end)) == digest
}

@Serializable
data class CombinedClaimReview(
    val claimId: String,
    val disposition: String,
    val reason: String,
    val finalSpans: List<CombinedCoverageLink> = emptyList(),
    val semanticStatus: String = "UNVERIFIED",
    val declaredDisposition: String = disposition,
    val failedChecks: List<String> = emptyList(),
    val finalQuote: String = ""
)

/** Completion, textual accounting, evidence support and semantic truth are separate dimensions. */
@Serializable
data class CombinedAssessmentSnapshot(
    val answerDigest: String,
    val revisionId: String,
    val wordCount: Int,
    val checks: List<CombinedAnswerCheck> = emptyList(),
    val decisions: List<CombinedClaimReview> = emptyList(),
    val unresolvedSourceLinks: Int = 0,
    val partialSourceLinks: Int = 0,
    val verificationNeeds: List<CombinedVerificationNeed> = emptyList()
)

/** All final measurements below refer to [answerDigest]; writer measurements are separate. */
@Serializable
data class CombinedQualityReport(
    val version: Int = 5,
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
    val repairAccepted: Int = 0,
    val repairRejected: Int = 0,
    val repairSkippedReason: String? = null,
    val turnContext: CombinedTurnContext? = null,
    val deliveredScopeStatus: String = "",
    val verificationStatus: String = "UNVERIFIED",
    val correctionRequired: Boolean = false,
    val revisionId: String = "",
    val wordCounterVersion: String = CombinedWordCounter.VERSION,
    val verificationReceipts: List<CombinedVerificationReceipt> = emptyList(),
    val supportEdges: List<CombinedSupportEdge> = emptyList(),
    val checks: List<CombinedAnswerCheck> = emptyList(),
    val verificationNeeds: List<CombinedVerificationNeed> = emptyList(),
    val repairRejections: List<String> = emptyList(),
    val consideredProfileUids: Set<String> = emptySet(),
    val requestedPolicyDigest: String = "",
    val effectivePlanDigest: String = "",
    val includedProfileUids: Set<String> = emptySet(),
    val representedProfileUids: Set<String> = emptySet(),
    val finalAssessment: Boolean = false,
    val lengthCompliance: String = "NOT_ASSESSED",
    val topicCoverage: String = "NOT_ASSESSED",
    val sourceLinkStatus: String = "NOT_ASSESSED",
    val sourceLinkRejections: Map<String, Int> = emptyMap(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val displayableDraft: Boolean = false,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val protocolComplete: Boolean = false,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val identityLinkStatus: String = "NOT_ASSESSED",
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val semanticSupportStatus: String = "NOT_ASSESSED",
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val sourceDispositions: List<CombinedSourceDisposition> = emptyList(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val sourceAccountingStatus: String = "NOT_ASSESSED",
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val retainedFragments: Int = 0,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val claimMapping: CombinedAnswerMapping? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val assessmentStage: String = "DRAFT",
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val writerAssessment: CombinedAssessmentSnapshot? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val writerAnswerDigest: String = "",
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val writerCharacters: Int = 0,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val locallyAddedCharacters: Int = 0,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val locallyRemovedCharacters: Int = 0,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val displayedCharacters: Int = 0,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val fullyLinkedClaims: Int = 0,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val partialSourceLinks: Int = 0,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val unresolvedSourceLinks: Int = 0
)

/** One NDJSON record per paragraph permits progressive delivery without leaking protocol data. */
internal class CombinedPassageDecoder(
    private val sources: List<CombinedModelResponse>,
    private val evidence: CombinedEvidenceLedger,
    private val maxCharacters: Int,
    private val strictRecords: Boolean = false
) {
    private val pending = StringBuilder()
    private val output = StringBuilder()
    private val seen = mutableListOf<Pair<String, CombinedClaimMatcher.Fingerprint>>()
    private val seenByTerm = mutableMapOf<String, MutableSet<Int>>()
    val passages = mutableListOf<CombinedPassageRecord>()
    val decisions = mutableListOf<CombinedClaimReview>()
    val issues = linkedSetOf<String>()
    var duplicatesRemoved = 0
        private set
    private enum class DeliveryMode { PLAIN_TEXT, NDJSON, JSON_ARRAY }
    private var mode: DeliveryMode? = null
    private var arrayOpened = false
    private var arrayClosed = false
    private var openingFenceRemoved = false
    private var plainRecoveryBlocked = false
    private val sourceIds = evidence.records.associateBy { it.id }
    private val inventory = CombinedClaimSemantics.records(sources)
    private val claimsById = CombinedContentIndexes.get(sources).byId
    private val claimIds = claimsById.keys

    fun accept(delta: String, final: Boolean = false): List<String> =
        dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationTrace.measure("protocol_decode") { decode(delta, final) }

    private fun decode(delta: String, final: Boolean): List<String> {
        pending.append(delta)
        if (mode == null && pending.isNotBlank()) {
            var prefix = pending.toString().trimStart()
            // Providers may split an opening code fence across tokens. Wait for
            // enough bytes to distinguish it from plain inline-code prose.
            if (!final && prefix.length < 3 && "```".startsWith(prefix)) return emptyList()
            if (!openingFenceRemoved && prefix.startsWith("```")) {
                val fenceEnd = pending.indexOf("\n")
                if (fenceEnd < 0 && !final) return emptyList()
                if (fenceEnd >= 0) {
                    pending.delete(0, fenceEnd + 1)
                    openingFenceRemoved = true
                    prefix = pending.toString().trimStart()
                    if (prefix.isEmpty() && !final) return emptyList()
                }
            }
            // A Markdown link or citation also starts with '['. Wait for the
            // first array item before committing to the structured protocol.
            val arrayPrefix = prefix.takeIf { it.startsWith("[") }?.drop(1)?.trimStart()
            if (arrayPrefix != null && arrayPrefix.isEmpty() && !final) return emptyList()
            mode = when {
                arrayPrefix?.firstOrNull() in listOf('{', ']') -> DeliveryMode.JSON_ARRAY
                prefix.startsWith("{") || prefix.startsWith("```") -> DeliveryMode.NDJSON
                else -> DeliveryMode.PLAIN_TEXT
            }
        }
        if (mode == DeliveryMode.PLAIN_TEXT) {
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
        if (mode == DeliveryMode.JSON_ARRAY) {
            consumeArrayRecords(final, deltas)
            require(pending.length.toLong() + output.length <= maxCharacters.toLong() + 65_536) { "COMBINED_RECORD_LIMIT" }
            return deltas
        }
        while (true) {
            val end = pending.indexOf("\n").takeIf { it >= 0 } ?: if (final && pending.isNotEmpty()) pending.length else break
            val line = pending.substring(0, end).trim()
            pending.delete(0, minOf(end + 1, pending.length))
            if (line.isBlank() || line.startsWith("```")) continue
            processRecord(runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull(), deltas)
        }
        require(pending.length.toLong() + output.length <= maxCharacters.toLong() + 65_536) { "COMBINED_RECORD_LIMIT" }
        return deltas
    }

    val answerLength: Int get() = output.length

    fun answerSince(offset: Int): String = output.substring(offset)

    fun answer(): String = output.toString()

    /** Accepts providers that wrap the PASSAGES_V2 objects in a top-level JSON array. */
    private fun consumeArrayRecords(final: Boolean, deltas: MutableList<String>) {
        while (pending.isNotEmpty()) {
            if (arrayClosed) {
                val trailing = pending.toString().trim()
                if (trailing.isEmpty() || trailing == "```") {
                    pending.setLength(0)
                } else if (final || !"```".startsWith(trailing)) {
                    issues += "INVALID_DELIVERY_RECORD"
                    pending.setLength(0)
                }
                break
            }
            var offset = 0
            while (offset < pending.length && (pending[offset].isWhitespace() || pending[offset] == ',')) offset++
            if (!arrayOpened && offset < pending.length && pending[offset] == '[') {
                arrayOpened = true
                offset++
                while (offset < pending.length && pending[offset].isWhitespace()) offset++
            }
            if (offset >= pending.length) {
                pending.setLength(0)
                break
            }
            if (pending[offset] == ']') {
                arrayClosed = true
                pending.delete(0, offset + 1)
                continue
            }
            if (pending[offset] != '{') {
                // Keep a possible partial first token buffered; otherwise reject this malformed item.
                if (!final && offset == pending.length - 1 && pending[offset] == '[') break
                issues += "INVALID_DELIVERY_RECORD"
                val next = (offset until pending.length).firstOrNull { pending[it] == ',' || pending[it] == ']' }
                if (next == null) {
                    if (final) pending.setLength(0) else pending.delete(0, offset)
                    break
                }
                pending.delete(0, next + if (pending[next] == ',') 1 else 0)
                continue
            }
            var depth = 0
            var inString = false
            var escaped = false
            var end = -1
            for (index in offset until pending.length) {
                val char = pending[index]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == '"' -> inString = false
                    }
                } else {
                    when (char) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                end = index
                                break
                            }
                        }
                    }
                }
            }
            if (end < 0) {
                if (final) {
                    issues += "INVALID_DELIVERY_RECORD"
                    pending.setLength(0)
                } else if (offset > 0) {
                    pending.delete(0, offset)
                }
                break
            }
            val record = pending.substring(offset, end + 1)
            pending.delete(0, end + 1)
            processRecord(runCatching { Json.parseToJsonElement(record) as? JsonObject }.getOrNull(), deltas)
        }
        if (final && (!arrayOpened || !arrayClosed)) issues += "INVALID_DELIVERY_RECORD"
    }

    private fun processRecord(row: JsonObject?, deltas: MutableList<String>) {
        if (row == null) {
            issues += "INVALID_DELIVERY_RECORD"
            return
        }
        fun text(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun strings(key: String) = (row[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        if (strictRecords && text("type") == "end") return
        if (text("type") == "decision") {
            val id = text("claim")
            val disposition = text("disposition")
            if (id in claimIds && disposition in setOf("INCLUDED", "MERGED", "QUALIFIED", "DISPUTED", "CORRECTED", "OUT_OF_SCOPE", "UNSUPPORTED", "CONFLICT", "MISSING", "OMITTED_SCOPE", "OMITTED_DUPLICATE", "OMITTED_UNSUPPORTED", "UNRESOLVED") && text("reason").isNotBlank()) {
                decisions.removeAll { it.claimId == id }
                decisions += CombinedClaimReview(id, disposition, text("reason"), finalQuote = text("final_quote"))
            } else {
                issues += "INVALID_CLAIM_DECISION"
            }
            return
        }
        if (text("type") != "passage" || text("text").isBlank()) {
            issues += "INVALID_DELIVERY_RECORD"
            return
        }
        val body = if (strictRecords) text("text") else text("text").trim()
        if (body.contains(COMBINED_RECOVERY_MARKER)) {
            issues += "INTERNAL_RECOVERY_MARKER_REJECTED"
            return
        }
        val separator = if (output.isEmpty()) "" else "\n\n"
        val requested = strings(if (strictRecords) "contributors" else "profiles")
        val profiles = requested.mapNotNull { id -> id.removePrefix("C").toIntOrNull()?.let { sources.getOrNull(it - 1)?.platformUid } }.toSet()
        if (profiles.size != requested.size) issues += "UNKNOWN_PROFILE_REFERENCE"
        val cited = strings(if (strictRecords) "evidence" else "sources")
        if (cited.any { it !in sourceIds }) issues += "UNKNOWN_SOURCE_REFERENCE"
        val validSources = cited.filter { it in sourceIds }.toSet()
        val claims = strings("claims")
        if (claims.any { it !in claimIds }) issues += "UNKNOWN_CLAIM_REFERENCE"
        val retainedClaims = claims.filter { it in claimIds }.toSet()
        val mapping = CombinedAnswerMappings.get(body, sources)
        val mappedClaims = mapping.claims.flatMap { claim -> (claim.aliases + claim.canonicalId + claim.claimId).map { it to claim } }.toMap()
        val linkedClaims = retainedClaims.filterTo(linkedSetOf()) { mappedClaims[it]?.links?.isNotEmpty() == true }
        if (linkedClaims != retainedClaims) issues += "UNESTABLISHED_CONTRIBUTION_LINK"
        // Canonical IDs and aliases resolve the same immutable occurrence set. Prefixes
        // identify wire references, not exclusive ownership of a merged proposition.
        val claimProfiles = linkedClaims.flatMapTo(linkedSetOf()) { id -> claimsById.getValue(id).occurrences.map { it.profileUid } }
        val owners = profiles.intersect(claimProfiles)
        if (owners != profiles) issues += "MISSING_CONTRIBUTION_LINK"
        val normalizedBody = CombinedContentCoverage.normalized(body)
        val fingerprint = CombinedClaimMatcher.fingerprint(body)
        val candidates = fingerprint.terms.flatMapTo(linkedSetOf()) { seenByTerm[it].orEmpty() }
        val duplicate = if (strictRecords) {
            null
        } else {
            candidates.firstOrNull { index ->
                val (normalized, existing) = seen[index]
                normalized == normalizedBody ||
                    CombinedClaimMatcher.match(existing, fingerprint) >= CombinedClaimMatcher.Match.PARAPHRASE &&
                    CombinedClaimMatcher.match(fingerprint, existing) >= CombinedClaimMatcher.Match.PARAPHRASE
            }
        }
        if (duplicate != null) {
            val previous = passages[duplicate]
            passages[duplicate] = previous.copy(profileUids = previous.profileUids + owners, sourceIds = previous.sourceIds + validSources, claimIds = previous.claimIds + retainedClaims)
            if ((validSources - previous.sourceIds).isNotEmpty()) issues += "MERGED_CITATION_AVAILABLE_IN_METADATA"
            duplicatesRemoved++
            return
        }
        // Citations are app-resolved URLs, never model-authored source links.
        val citations = validSources.sorted().joinToString(" ") { id -> "[${evidence.records.indexOfFirst { it.id == id } + 1}](${sourceIds.getValue(id).url})" }
        issues += CombinedResponseLinks.issues(body, evidence.records.mapTo(linkedSetOf()) { it.url })
        val rendered = body + if (citations.isBlank()) "" else "\n\n$citations"
        require(output.length.toLong() + separator.length + rendered.length <= maxCharacters) { "COMBINED_DELIVERY_LIMIT" }
        output.append(separator)
        val start = output.length
        output.append(body)
        val assessment = CombinedEvidenceVerifier.assess("P${passages.size + 1}", body, evidence, validSources)
        passages += CombinedPassageRecord(
            start,
            output.length,
            researchHash(body),
            owners,
            validSources,
            retainedClaims,
            assessment.result,
            passageId = if (strictRecords) text("id") else "P${passages.size + 1}",
            semanticLinkStatus = if (linkedClaims == retainedClaims && linkedClaims.isNotEmpty()) "CONSERVATIVE_LINK_CHECKED" else "UNVERIFIED",
            linkVersion = 1,
            links = mapping.links.mapNotNull { link ->
                val ids = link.claimIds.intersect(retainedClaims)
                val actualOwners = link.profileUids.intersect(profiles)
                if (ids.isEmpty() || actualOwners.isEmpty()) null else link.copy(start = start + link.start, end = start + link.end, profileUids = actualOwners, claimIds = ids)
            }
        )
        if (citations.isNotBlank()) output.append("\n\n$citations")
        val index = seen.size
        seen += normalizedBody to fingerprint
        fingerprint.terms.forEach { seenByTerm.getOrPut(it) { linkedSetOf() }.add(index) }
        deltas += separator + rendered
    }
}
