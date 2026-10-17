package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.research.ResearchCorpus
import dev.chungjungsoo.gptmobile.data.research.SourceIdentity
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class CombinedEvidenceRecord(
    val id: String,
    val url: String,
    val title: String = "",
    val publishedAt: String = "",
    val passage: String = "",
    val retrieval: String = "DISCOVERY_ONLY",
    val toolEventIds: Set<String> = emptySet(),
    val profileUids: Set<String> = emptySet()
) {
    val readable: Boolean get() = retrieval == "READABLE_PASSAGE" && passage.length >= 80
    val dated: Boolean get() = runCatching { java.time.LocalDate.parse(publishedAt.take(10)) }.isSuccess
    val article: Boolean get() = runCatching { URI(url).path.orEmpty().trim('/').isNotEmpty() }.getOrDefault(false)
}

/** Canonical source identity is shared; search snippets never become read/verified evidence. */
@Serializable
data class CombinedEvidenceLedger(
    val records: List<CombinedEvidenceRecord> = emptyList(),
    val failedTools: List<String> = emptyList(),
    val trustedDateObservation: Map<String, String> = emptyMap()
) {
    companion object {
        fun freeze(corpus: ResearchCorpus?, events: List<ToolEvent>, runProfiles: Map<String, String>): CombinedEvidenceLedger {
            val rows = mutableListOf<CombinedEvidenceRecord>()
            fun add(url: String, title: String, date: String, text: String, readable: Boolean, event: ToolEvent? = null, id: String? = null) {
                val canonical = runCatching { SourceIdentity.canonicalResearchUrl(url) }.getOrNull().orEmpty()
                if (!canonical.startsWith("https://") && !canonical.startsWith("http://")) return
                if (runCatching { URI(canonical).userInfo != null }.getOrDefault(true)) return
                rows += CombinedEvidenceRecord(id ?: "E" + researchHash(canonical).take(20), canonical, title, date, text, if (readable) "READABLE_PASSAGE" else "DISCOVERY_ONLY", setOfNotNull(event?.eventId), setOfNotNull(event?.runId?.let(runProfiles::get)))
            }
            corpus?.sources.orEmpty().forEach { add(it.canonicalUrl.ifBlank { it.url }, it.title, it.publishedAt, it.passage, it.readable, id = it.id) }
            events.filter { !it.isError && it.status == "COMPLETED" }.forEach { event ->
                val tool = event.toolName.lowercase()
                val reader = tool in setOf("read_url", "firecrawl_scrape", "tavily_extract", "web_fetch_exa", "free_search__fetch", "free_search__read_doc", "deep_research_read_paper")
                val search = listOf("search", "news").any(tool::contains)
                if (!reader && !search) return@forEach
                val args = runCatching { Json.parseToJsonElement(event.arguments) as? JsonObject }.getOrNull()
                val requestedUrl = (args?.get("url") as? JsonPrimitive)?.contentOrNull.orEmpty()
                fun walk(value: JsonElement, depth: Int = 0) {
                    if (depth > 16) return
                    when (value) {
                        is JsonArray -> value.forEach { walk(it, depth + 1) }
                        is JsonObject -> {
                            fun text(vararg keys: String): String = keys.firstNotNullOfOrNull { (value[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.orEmpty()
                            val url = text("url", "canonicalUrl", "link", "source_url")
                            val body = text("content", "text", "passage", "raw_content")
                            val nested = runCatching { Json.parseToJsonElement(body) }.getOrNull()
                            if (nested is JsonObject || nested is JsonArray) {
                                walk(nested, depth + 1)
                            } else if (url.isNotBlank() || reader && requestedUrl.isNotBlank() && body.length >= 80) {
                                add(url.ifBlank { requestedUrl }, text("title", "name"), text("publishedAt", "published_at", "publishedDate", "date", "publication_date"), body, reader && body.length >= 80, event)
                            }
                            value.values.filter { it is JsonObject || it is JsonArray }.forEach { walk(it, depth + 1) }
                        }
                        is JsonPrimitive -> {
                            val body = value.contentOrNull.orEmpty()
                            val nested = runCatching { Json.parseToJsonElement(body) }.getOrNull()
                            if (nested is JsonObject || nested is JsonArray) {
                                walk(nested, depth + 1)
                            } else if (reader && requestedUrl.isNotBlank() && body.length >= 80) {
                                add(requestedUrl, "", "", body, true, event)
                            }
                        }
                        else -> Unit
                    }
                }
                val result = event.result.orEmpty()
                val payload = runCatching { Json.parseToJsonElement(result) }.getOrNull()
                if (payload != null) walk(payload)
                // Native read_url can return plain page text; bind it to the actual dispatched URL.
                if (reader && payload == null && result.length >= 80) {
                    if (requestedUrl.isNotBlank()) add(requestedUrl, "", "", result, true, event)
                }
            }
            val canonical = rows.groupBy { it.url }.values.map { versions ->
                val best = versions.maxBy { if (it.readable) it.passage.length else 0 }
                best.copy(id = "E" + researchHash(best.url).take(20), title = versions.firstOrNull { it.title.isNotBlank() }?.title.orEmpty(), publishedAt = versions.firstOrNull { it.publishedAt.isNotBlank() }?.publishedAt.orEmpty(), toolEventIds = versions.flatMap { it.toolEventIds }.toSet(), profileUids = versions.flatMap { it.profileUids }.toSet())
            }
            val dateEvent = events.filter { !it.isError && it.status == "COMPLETED" && it.connectionUidSnapshot == null && it.toolName == "current_date" }.maxByOrNull { it.completedAt ?: 0 }
            val datePayload = dateEvent?.result?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            val nestedDate = (datePayload?.get("text") as? JsonPrimitive)?.contentOrNull?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: datePayload
            val date = listOf("date", "time", "zone").mapNotNull { key -> (nestedDate?.get(key) as? JsonPrimitive)?.contentOrNull?.let { key to it } }.toMap()
            return CombinedEvidenceLedger(canonical, events.filter { it.isError }.map { it.toolName + ":" + (it.error ?: "FAILED") }.distinct(), date)
        }
    }
}
