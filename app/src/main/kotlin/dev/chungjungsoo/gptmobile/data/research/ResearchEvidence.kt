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
            ResearchSnapshot(
                task = value.text("task").take(8000), phase = value.text("phase"), round = value.number("round"),
                searches = value.number("searches"), attempts = value.number("attempts"),
                engineResponses = value.number("engineResponses"), engineFailures = value.number("engineFailures"),
                sources = value.rows("sources").take(120).map { s ->
                    ResearchSource(s.text("id"), s.text("url"), s.text("title"), s.text("status"), s.text("passage").take(6000), s.number("depth"), s["retrievedAt"]?.jsonPrimitive?.longOrNull ?: 0, s.text("publishedAt"), s.strings("engines"), s.text("fingerprint"), s.text("originId"), s.text("location"))
                },
                claims = value.rows("claims").take(30).map { ResearchClaim(it.text("text"), it.text("sourceId"), it.text("quote"), it.text("verdict")) },
                questions = value.strings("questions").take(8), queries = value.strings("queries").take(12), notes = value.strings("notes").take(20),
                updatedAt = value["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0, complete = value["complete"]?.jsonPrimitive?.booleanOrNull == true
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
