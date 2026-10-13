package dev.chungjungsoo.gptmobile.data.research

import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Public research artifacts never enter personal memory. */
data class ResearchSource(
    val id: String,
    val url: String,
    val title: String,
    val status: String = "Discovered",
    val passage: String = "",
    val depth: Int = 0,
    val retrievedAt: Long = 0,
    val publishedAt: String = "",
    val engines: List<String> = emptyList(),
    val fingerprint: String = "",
    val originId: String = "",
    val location: String = "Page excerpt",
    val doi: String = "",
    val pmid: String = "",
    val pmcid: String = "",
    val canonicalUrl: String = SourceIdentity.canonicalResearchUrl(url),
    val host: String = runCatching { java.net.URI(canonicalUrl).host.orEmpty() }.getOrDefault(""),
    val publicationYear: Int? = null,
    val titleFingerprint: String = title.trim().lowercase().takeIf(String::isNotBlank)?.let(::researchHash).orEmpty(),
    val contentFingerprint: String = passage.trim().lowercase().takeIf(String::isNotBlank)?.let(::researchHash).orEmpty(),
    val retrievalQuality: String = when (status) {
        "Read", "Used as evidence" -> "FULL_TEXT"
        "Partially read" -> "FULL_TEXT"
        "Blocked" -> "BLOCKED"
        "Failed" -> "EMPTY"
        else -> "LEAD"
    },
    val equivalenceGroup: String = SourceIdentity.create(url, title, passage, doi, pmid, pmcid, publicationYear).equivalenceKey,
    val paperId: String = "",
    val versionId: String = "",
    val providerReceipts: List<String> = emptyList(),
    val reviewStatus: String = "unknown",
    val publicationType: String = "unknown",
    val isPreprint: Boolean? = null,
    val isRetracted: Boolean? = null,
    val contentLicense: String? = null,
    val bodyChunks: List<String> = emptyList(),
    val accessUrl: String = "",
    val failureReason: String = ""
) {
    val readable: Boolean get() = originId.isBlank() && retrievalQuality == "FULL_TEXT" && status in setOf("Read", "Partially read", "Used as evidence") && passage.isNotBlank()
    fun json() = buildJsonObject {
        put("bodyChunks", JsonArray(bodyChunks.map(::JsonPrimitive)))
        put("accessUrl", accessUrl)
        put("failureReason", failureReason.take(240))
        put("id", id)
        put("url", url)
        put("title", title)
        put("status", status)
        put("passage", passage)
        put("depth", depth)
        put("retrievedAt", retrievedAt)
        put("publishedAt", publishedAt)
        put("engines", JsonArray(engines.map(::JsonPrimitive)))
        put("fingerprint", fingerprint)
        put("originId", originId)
        put("location", location)
        put("doi", doi)
        put("pmid", pmid)
        put("pmcid", pmcid)
        put("canonicalUrl", canonicalUrl)
        put("host", host)
        publicationYear?.let { put("publicationYear", it) }
        put("titleFingerprint", titleFingerprint)
        put("contentFingerprint", contentFingerprint)
        put("retrievalQuality", retrievalQuality)
        put("equivalenceGroup", equivalenceGroup)
        put("paperId", paperId)
        put("versionId", versionId)
        put("providerReceipts", JsonArray(providerReceipts.map(::JsonPrimitive)))
        put("reviewStatus", reviewStatus)
        put("publicationType", publicationType)
        isPreprint?.let { put("isPreprint", it) }
        isRetracted?.let { put("isRetracted", it) }
        contentLicense?.let { put("contentLicense", it) }
    }
}

data class ResearchClaim(val text: String, val sourceId: String, val quote: String, val verdict: String = "Unreviewed", val questionId: String = "", val relevance: String = "Unknown", val exactQuoteVerified: Boolean = false) {
    fun json() = buildJsonObject {
        put("text", text)
        put("sourceId", sourceId)
        put("quote", quote)
        put("verdict", verdict)
        put("questionId", questionId)
        put("relevance", relevance)
        put("exactQuoteVerified", exactQuoteVerified)
    }
}

/** Redacted execution history. Raw tool arguments and credentials are never stored here. */
data class ResearchToolEvent(
    val query: String,
    val engine: String,
    val reader: String = "",
    val argumentsDigest: String = "",
    val outcome: String,
    val sourceIds: List<String> = emptyList(),
    val failureReason: String = "",
    val latencyMs: Long = 0,
    val cacheHit: Boolean = false,
    val shared: Boolean = false,
    val recordedAt: Long = 0
) {
    fun json() = buildJsonObject {
        put("query", query.take(500))
        put("engine", engine.take(120))
        put("reader", reader.take(120))
        put("argumentsDigest", argumentsDigest.take(64))
        put("outcome", outcome.take(40))
        put("sourceIds", JsonArray(sourceIds.take(120).map { JsonPrimitive(it.take(64)) }))
        put("failureReason", failureReason.take(240))
        put("latencyMs", latencyMs.coerceAtLeast(0))
        put("cacheHit", cacheHit)
        put("shared", shared)
        put("recordedAt", recordedAt.coerceAtLeast(0))
    }
}

data class ResearchSnapshot(
    val task: String,
    val phase: String = "Planning",
    val round: Int = 0,
    val searches: Int = 0,
    val attempts: Int = 0,
    val engineResponses: Int = 0,
    val engineFailures: Int = 0,
    val sources: List<ResearchSource> = emptyList(),
    val claims: List<ResearchClaim> = emptyList(),
    val questions: List<String> = emptyList(),
    val questionPlan: List<ResearchQuestion> = emptyList(),
    val planningStatus: String = "Legacy unknown",
    val extractedSourceVersions: Set<String> = emptySet(),
    val cancellationOrigin: String = "UNKNOWN",
    val queries: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
    val complete: Boolean = false,
    val toolEvents: List<ResearchToolEvent> = emptyList(),
    val targetSources: Int = 0,
    val frontierCursors: Map<String, String> = emptyMap(),
    val delegationPlan: dev.chungjungsoo.gptmobile.data.delegation.DelegationPlanCheckpoint? = null
) {
    fun json() = buildJsonObject {
        put("version", 4)
        delegationPlan?.let { put("delegationPlan", kotlinx.serialization.json.Json.encodeToJsonElement(dev.chungjungsoo.gptmobile.data.delegation.DelegationPlanCheckpoint.serializer(), it)) }
        put("targetSources", targetSources)
        put("frontierCursors", JsonObject(frontierCursors.mapValues { JsonPrimitive(it.value) }))
        put("task", task)
        put("phase", phase)
        put("round", round)
        put("searches", searches)
        put("attempts", attempts)
        put("engineResponses", engineResponses)
        put("engineFailures", engineFailures)
        put("sources", JsonArray(sources.map { it.json() }))
        put("claims", JsonArray(claims.map { it.json() }))
        put("questions", JsonArray(questions.map(::JsonPrimitive)))
        put("questionPlan", JsonArray(questionPlan.map { it.json() }))
        put("planningStatus", planningStatus)
        put("extractedSourceVersions", JsonArray(extractedSourceVersions.map(::JsonPrimitive)))
        put("cancellationOrigin", cancellationOrigin)
        put("queries", JsonArray(queries.map(::JsonPrimitive)))
        put("notes", JsonArray(notes.map(::JsonPrimitive)))
        put("toolEvents", JsonArray(toolEvents.takeLast(200).map { it.json() }))
        put("updatedAt", updatedAt)
        put("complete", complete)
    }

    companion object {
        fun parse(value: JsonObject): ResearchSnapshot? = runCatching {
            val version = value["version"]?.jsonPrimitive?.int
            require(version in 1..4)
            val sources = value.rows("sources").mapNotNull { s ->
                val id = s.text("id")
                val url = s.text("url").take(2048)
                if (!id.matches(Regex("S(?:[1-9][0-9]{0,9}|[a-f0-9]{16})")) ||
                    !runCatching {
                        URI(url).let { it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() && it.rawUserInfo == null }
                    }.getOrDefault(false)
                ) {
                    return@mapNotNull null
                }
                val status = s.text("status").takeIf { it in setOf("Discovered", "Metadata only", "Read", "Partially read", "Used as evidence", "Blocked", "Failed") } ?: "Failed"
                val source = ResearchSource(
                    id, url, s.text("title").take(240), status, s.text("passage").take(6000), s.number("depth").coerceIn(0, 2),
                    (s["retrievedAt"] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0) ?: 0, s.text("publishedAt").take(80),
                    s.strings("engines").take(20).map { it.take(120) }, s.text("fingerprint").take(64), s.text("originId").take(64), s.text("location").take(240)
                )
                source.copy(
                    doi = SourceIdentity.normalizeDoi(s.text("doi")),
                    pmid = s.text("pmid").takeIf { it.matches(Regex("[0-9]{1,12}")) }.orEmpty(),
                    pmcid = s.text("pmcid").uppercase().takeIf { it.matches(Regex("PMC[0-9]{1,12}")) }.orEmpty(),
                    canonicalUrl = SourceIdentity.canonicalResearchUrl(s.text("canonicalUrl").ifBlank { source.url }),
                    host = s.text("host").takeIf { it.matches(Regex("[A-Za-z0-9.-]{1,253}")) }?.lowercase().orEmpty().ifBlank { SourceIdentity.create(source.url).host },
                    publicationYear = (s["publicationYear"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1500..3000 },
                    titleFingerprint = s.text("titleFingerprint").takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: source.titleFingerprint,
                    contentFingerprint = s.text("contentFingerprint").takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: source.contentFingerprint,
                    retrievalQuality = s.text("retrievalQuality").takeIf { it in setOf("FULL_TEXT", "ABSTRACT", "METADATA", "BLOCKED", "EMPTY", "LEAD") }
                        ?: when (status) {
                            "Read", "Used as evidence" -> "FULL_TEXT"
                            "Partially read" -> "FULL_TEXT"
                            "Blocked" -> "BLOCKED"
                            "Failed" -> "EMPTY"
                            else -> "LEAD"
                        },
                    equivalenceGroup = s.text("equivalenceGroup").takeIf { it.length <= 512 }
                        ?: SourceIdentity.create(source.url, source.title, source.passage).equivalenceKey,
                    paperId = s.text("paperId").take(512), versionId = s.text("versionId").take(700),
                    providerReceipts = s.strings("providerReceipts").take(30), reviewStatus = s.text("reviewStatus").ifBlank { "unknown" },
                    publicationType = s.text("publicationType").take(80).ifBlank { "unknown" },
                    isPreprint = (s["isPreprint"] as? JsonPrimitive)?.booleanOrNull,
                    isRetracted = (s["isRetracted"] as? JsonPrimitive)?.booleanOrNull,
                    contentLicense = s.text("contentLicense").take(1000).takeIf(String::isNotBlank),
                    accessUrl = s.text("accessUrl"),
                    failureReason = s.text("failureReason").take(240),
                    bodyChunks = s.strings("bodyChunks").onEach { require(it.matches(Regex("[a-f0-9]{64}\\.chunk"))) }
                )
            }.distinctBy { source ->
                when {
                    source.paperId.isNotBlank() -> "${source.paperId}:${source.versionId}"
                    source.doi.isNotBlank() -> "doi:${source.doi}"
                    source.pmid.isNotBlank() -> "pmid:${source.pmid}"
                    source.pmcid.isNotBlank() -> "pmcid:${source.pmcid}"
                    else -> source.canonicalUrl.ifBlank { source.url }
                }
            }
            val claimRows = value.rows("claims")
            val claims = validatedResearchClaims(claimRows, sources).map { claim ->
                val verdict = claimRows.firstOrNull { it.text("sourceId") == claim.sourceId && it.text("text").trim().take(600) == claim.text && it.text("quote").trim().take(1200) == claim.quote }
                    ?.text("verdict")?.takeIf { it in setOf("Supported", "Contradicted", "Insufficient") } ?: "Unreviewed"
                val row = claimRows.first { it.text("sourceId") == claim.sourceId && it.text("text").trim() == claim.text && it.text("quote").trim() == claim.quote }
                claim.copy(verdict = verdict, questionId = row.text("questionId"), relevance = row.text("relevance").takeIf { it in setOf("Relevant", "Background", "Irrelevant") } ?: "Unknown", exactQuoteVerified = (row["exactQuoteVerified"] as? JsonPrimitive)?.booleanOrNull == true)
            }
            ResearchSnapshot(
                delegationPlan = value["delegationPlan"]?.let { kotlinx.serialization.json.Json.decodeFromJsonElement(dev.chungjungsoo.gptmobile.data.delegation.DelegationPlanCheckpoint.serializer(), it) },
                frontierCursors = (value["frontierCursors"] as? JsonObject)?.mapValues { (_, cursor) -> (cursor as JsonPrimitive).content.also { require(it.toIntOrNull()?.let { page -> page > 0 } == true) } }.orEmpty(),
                task = value.text("task").take(8000), targetSources = value.number("targetSources").coerceAtLeast(0), phase = value.text("phase").take(40), round = value.number("round").coerceAtLeast(0),
                searches = value.number("searches").coerceAtLeast(0), attempts = value.number("attempts").coerceAtLeast(0),
                engineResponses = value.number("engineResponses").coerceAtLeast(0), engineFailures = value.number("engineFailures").coerceAtLeast(0),
                sources = sources, claims = claims,
                questionPlan = parseQuestions((value["questionPlan"] as? JsonArray) ?: (value["questions"] as? JsonArray) ?: JsonArray(emptyList())),
                planningStatus = value.text("planningStatus").ifBlank { "Legacy unknown" },
                extractedSourceVersions = value.strings("extractedSourceVersions").toSet(),
                cancellationOrigin = value.text("cancellationOrigin").ifBlank { "UNKNOWN" },
                questions = parseQuestions((value["questions"] as? JsonArray) ?: JsonArray(emptyList())).map { it.text }, queries = value.strings("queries").map { it.take(500) }, notes = value.strings("notes").take(20).map { it.take(500) },
                updatedAt = (value["updatedAt"] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0) ?: 0, complete = (value["complete"] as? JsonPrimitive)?.booleanOrNull == true,
                toolEvents = value.rows("toolEvents").takeLast(200).mapNotNull { event ->
                    val outcome = event.text("outcome").takeIf { it in setOf("SUCCESS", "PARTIAL", "EMPTY", "BLOCKED", "AUTH_FAILED", "RATE_LIMITED", "INVALID_RESPONSE", "BUDGET_EXHAUSTED", "CIRCUIT_OPEN", "FAILED") }
                        ?: return@mapNotNull null
                    ResearchToolEvent(
                        query = event.text("query").take(500), engine = event.text("engine").take(120), reader = event.text("reader").take(120),
                        argumentsDigest = event.text("argumentsDigest").takeIf { it.matches(Regex("[0-9a-f]{64}")) }.orEmpty(), outcome = outcome,
                        sourceIds = event.strings("sourceIds").filter { it.matches(Regex("S(?:[1-9][0-9]{0,9}|[a-f0-9]{16})")) }.distinct().take(120),
                        failureReason = event.text("failureReason").take(240), latencyMs = (event["latencyMs"] as? JsonPrimitive)?.longOrNull?.coerceIn(0, 86_400_000) ?: 0,
                        cacheHit = (event["cacheHit"] as? JsonPrimitive)?.booleanOrNull == true, shared = (event["shared"] as? JsonPrimitive)?.booleanOrNull == true,
                        recordedAt = (event["recordedAt"] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0) ?: 0
                    )
                }
            )
        }.getOrNull()
    }
}

fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonObject.number(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray).orEmpty().filterIsInstance<JsonPrimitive>().mapNotNull { it.contentOrNull }
fun JsonObject.rows(key: String): List<JsonObject> = (this[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
fun researchHash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
fun researchTerms(text: String): Set<String> = Regex("[\\p{L}\\p{N}]{3,}").findAll(text.lowercase()).map { it.value }.filterNot { it in setOf("the", "and", "for", "with", "that", "this", "from", "what", "which") }.toSet()
fun researchDomains(value: String): List<String> = value.split(',', '\n', ' ').map { it.trim().lowercase().removePrefix("www.") }.filter { it.matches(Regex("[a-z0-9-]+(?:\\.[a-z0-9-]+)+")) }.distinct().take(20)
fun researchDomainAllowed(url: String, include: List<String>, exclude: List<String>): Boolean {
    val host = runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull() ?: return false
    fun matches(domain: String) = host == domain || host.endsWith(".$domain")
    return exclude.none(::matches) && (include.isEmpty() || include.any(::matches))
}

/** An exact quote is necessary but does not itself establish entailment. */
fun validatedResearchClaims(rows: List<JsonObject>, sources: List<ResearchSource>): List<ResearchClaim> = rows.mapNotNull { row ->
    val source = sources.firstOrNull { it.id == row.text("sourceId") && it.readable } ?: return@mapNotNull null
    val quote = row.text("quote").trim()
    val claim = row.text("text").trim()
    if (quote.length !in 20..1200 || quote !in source.passage || claim.isBlank() || claim.length > 600) return@mapNotNull null
    ResearchClaim(claim, source.id, quote, questionId = row.text("questionId"), exactQuoteVerified = source.paperId.isBlank())
}.distinctBy { it.text to it.sourceId }

/** Bounded overlap flags likely copies; it never proves that publishers are independent. */
fun relatedResearchPassages(left: String, right: String): Boolean {
    fun shingles(text: String): Set<String> = Regex("[\\p{L}\\p{N}]+")
        .findAll(text.lowercase()).map { it.value }.take(1200).toList()
        .windowed(4).map { it.joinToString(" ") }.toSet()
    val a = shingles(left)
    val b = shingles(right)
    if (a.size < 20 || b.size < 20) return false
    return a.intersect(b).size.toDouble() / minOf(a.size, b.size) >= 0.85
}

/** Captured versions stay distinct; known versions of one work share one target slot. */
fun Collection<ResearchSource>.readableWorkCount(): Int = filter { it.readable }.distinctBy { source ->
    when {
        source.doi.isNotBlank() -> "doi:${source.doi}"
        source.paperId.startsWith("arxiv:") -> source.paperId.replace(Regex("v[0-9]+$"), "")
        else -> source.equivalenceGroup.ifBlank { source.canonicalUrl }
    }
}.size

/** Expiration invalidates dependent verification, never the entire saved corpus. */
internal fun ResearchSnapshot.withFreshEvidence(nowMillis: Long, maxAgeMillis: Long = 86_400_000): ResearchSnapshot {
    val expired = sources.filter { it.readable && nowMillis - it.retrievedAt !in 0..maxAgeMillis }.map { it.id }.toSet()
    if (expired.isEmpty()) return this
    return copy(
        complete = false,
        sources = sources.map { if (it.id in expired) it.copy(status = "Discovered", passage = "", bodyChunks = emptyList()) else it },
        claims = claims.map { if (it.sourceId in expired) it.copy(verdict = "Unreviewed", exactQuoteVerified = false, relevance = "Unknown") else it },
        extractedSourceVersions = extractedSourceVersions.filterNot { key -> expired.any { key.startsWith("$it:") } }.toSet(),
        notes = (notes + "${expired.size} expired sources need refresh; other completed evidence is retained.").distinct()
    )
}
