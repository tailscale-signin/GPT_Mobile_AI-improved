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
    val location: String = "Page excerpt"
) {
    val readable: Boolean get() = status in setOf("Read", "Partially read", "Used as evidence") && passage.isNotBlank()
    fun json() = buildJsonObject {
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
    }
}

data class ResearchClaim(val text: String, val sourceId: String, val quote: String, val verdict: String = "Unreviewed") {
    fun json() = buildJsonObject {
        put("text", text)
        put("sourceId", sourceId)
        put("quote", quote)
        put("verdict", verdict)
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
    val queries: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
    val complete: Boolean = false
) {
    fun json() = buildJsonObject {
        put("version", 1)
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
        put("queries", JsonArray(queries.map(::JsonPrimitive)))
        put("notes", JsonArray(notes.map(::JsonPrimitive)))
        put("updatedAt", updatedAt)
        put("complete", complete)
    }

    companion object {
        fun parse(value: JsonObject): ResearchSnapshot? = runCatching {
            require(value["version"]?.jsonPrimitive?.int == 1)
            val sources = value.rows("sources").take(120).mapNotNull { s ->
                val id = s.text("id")
                val url = s.text("url").take(2048)
                if (!id.matches(Regex("S[1-9][0-9]{0,2}")) ||
                    !runCatching {
                        URI(url).let { it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() && it.rawUserInfo == null }
                    }.getOrDefault(false)
                ) {
                    return@mapNotNull null
                }
                val status = s.text("status").takeIf { it in setOf("Discovered", "Read", "Partially read", "Used as evidence", "Blocked", "Failed") } ?: "Failed"
                ResearchSource(
                    id, url, s.text("title").take(240), status, s.text("passage").take(6000), s.number("depth").coerceIn(0, 2),
                    (s["retrievedAt"] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0) ?: 0, s.text("publishedAt").take(80),
                    s.strings("engines").take(20).map { it.take(120) }, s.text("fingerprint").take(64), s.text("originId").take(4), s.text("location").take(240)
                )
            }.distinctBy { it.id }.distinctBy { it.url }
            val claimRows = value.rows("claims").take(30)
            val claims = validatedResearchClaims(claimRows, sources).map { claim ->
                val verdict = claimRows.firstOrNull { it.text("sourceId") == claim.sourceId && it.text("text").trim().take(600) == claim.text && it.text("quote").trim().take(1200) == claim.quote }
                    ?.text("verdict")?.takeIf { it in setOf("Supported", "Contradicted", "Insufficient") } ?: "Unreviewed"
                claim.copy(verdict = verdict)
            }
            ResearchSnapshot(
                task = value.text("task").take(8000), phase = value.text("phase").take(40), round = value.number("round").coerceIn(0, 3),
                searches = value.number("searches").coerceIn(0, 9), attempts = value.number("attempts").coerceIn(0, 60),
                engineResponses = value.number("engineResponses").coerceAtLeast(0), engineFailures = value.number("engineFailures").coerceAtLeast(0),
                sources = sources, claims = claims,
                questions = value.strings("questions").take(8).map { it.take(350) }, queries = value.strings("queries").take(12).map { it.take(500) }, notes = value.strings("notes").take(20).map { it.take(500) },
                updatedAt = (value["updatedAt"] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0) ?: 0, complete = (value["complete"] as? JsonPrimitive)?.booleanOrNull == true
            )
        }.getOrNull()
    }
}

interface ResearchJournal {
    suspend fun load(): ResearchSnapshot?
    suspend fun save(snapshot: ResearchSnapshot)
    fun stopRequested(): Boolean
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
fun validatedResearchClaims(rows: List<JsonObject>, sources: List<ResearchSource>): List<ResearchClaim> = rows.take(30).mapNotNull { row ->
    val source = sources.firstOrNull { it.id == row.text("sourceId") && it.readable } ?: return@mapNotNull null
    val quote = row.text("quote").trim().take(1200)
    val claim = row.text("text").trim().take(600)
    if (quote.length < 20 || quote !in source.passage || claim.isBlank()) return@mapNotNull null
    ResearchClaim(claim, source.id, quote)
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
