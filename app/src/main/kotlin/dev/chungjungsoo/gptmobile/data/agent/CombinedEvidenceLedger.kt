package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.research.ResearchCorpus
import dev.chungjungsoo.gptmobile.data.research.SourceIdentity
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.net.URI
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class CombinedWorkIdentity(
    val id: String,
    val canonicalUrl: String,
    val doi: String = "",
    val pmid: String = "",
    val pmcid: String = "",
    val version: String = "",
    val mirrors: Set<String> = emptySet(),
    val independenceGroup: String = id,
    val identityMethod: String = "CANONICAL_URL_ONLY"
)

@Serializable
data class CombinedEvidenceExcerpt(
    val id: String,
    val text: String,
    val digest: String = researchHash(text),
    val retrieval: String = "TITLE_ONLY",
    val toolEventIds: Set<String> = emptySet(),
    val permissionScopes: Set<String> = emptySet()
) {
    val readable: Boolean get() = retrieval in setOf("READABLE_PASSAGE", "FULL_TEXT_AVAILABLE") && text.isNotBlank()
}

@Serializable
data class CombinedEvidenceRecord(
    val id: String,
    val url: String,
    val title: String = "",
    val publishedAt: String = "",
    val passage: String = "",
    val retrieval: String = "DISCOVERY_ONLY",
    val toolEventIds: Set<String> = emptySet(),
    val profileUids: Set<String> = emptySet(),
    val work: CombinedWorkIdentity? = null,
    val excerpts: List<CombinedEvidenceExcerpt> = emptyList(),
    val retrievedAt: Long? = null,
    val permissionScopes: Set<String> = emptySet(),
    val ancestryIds: Set<String> = emptySet()
) {
    val readable: Boolean get() = retrieval in setOf("READABLE_PASSAGE", "FULL_TEXT_AVAILABLE") && passage.isNotBlank()
    val dated: Boolean get() = runCatching { java.time.LocalDate.parse(publishedAt.take(10)) }.isSuccess
    val article: Boolean get() = runCatching { URI(url).path.orEmpty().trim('/').isNotEmpty() }.getOrDefault(false)
    val retainedExcerpts: List<CombinedEvidenceExcerpt> get() = excerpts.ifEmpty {
        listOf(CombinedEvidenceExcerpt("$id:${researchHash(passage).take(16)}", passage, retrieval = retrieval, toolEventIds = toolEventIds, permissionScopes = permissionScopes))
    }
}

/** Source identity and received text are evidence records, not a certification that their claims are true. */
@Serializable
data class CombinedEvidenceLedger(
    val records: List<CombinedEvidenceRecord> = emptyList(),
    val failedTools: List<String> = emptyList(),
    val trustedDateObservation: Map<String, String> = emptyMap(),
    val version: Int = 2,
    val supportEdges: List<CombinedSupportEdge> = emptyList()
) {
    fun mergedWith(other: CombinedEvidenceLedger): CombinedEvidenceLedger = copy(
        records = canonicalize(records + other.records),
        failedTools = (failedTools + other.failedTools).distinct(),
        trustedDateObservation = trustedDateObservation + other.trustedDateObservation,
        supportEdges = (supportEdges + other.supportEdges).distinctBy { it.id }
    )

    companion object {
        private fun level(value: String): Int = when (value) {
            "FULL_TEXT_AVAILABLE" -> 3
            "READABLE_PASSAGE" -> 2
            "SEARCH_SNIPPET" -> 1
            else -> 0
        }

        private fun normalizedDoi(value: String): String = value.trim().lowercase(Locale.ROOT)
            .removePrefix("https://doi.org/").removePrefix("http://doi.org/").removePrefix("https://dx.doi.org/").removePrefix("doi:")
            .trim().trimEnd('.', ',', ';').takeIf { it.matches(Regex("10\\.[0-9]{4,9}/[^\\s]+")) }.orEmpty()

        private fun identity(url: String, doi: String = "", pmid: String = "", pmcid: String = "", version: String = ""): CombinedWorkIdentity {
            val uri = URI(url)
            val host = uri.host.orEmpty().lowercase(Locale.ROOT)
            val normalizedDoi = normalizedDoi(doi.ifBlank { if (host in setOf("doi.org", "dx.doi.org")) uri.path.removePrefix("/") else "" })
            val normalizedPmid = pmid.trim().takeIf { it.matches(Regex("[0-9]+")) }
                ?: uri.path.trim('/').takeIf { host == "pubmed.ncbi.nlm.nih.gov" && it.matches(Regex("[0-9]+")) }.orEmpty()
            val normalizedPmcid = pmcid.uppercase(Locale.ROOT).takeIf { it.matches(Regex("PMC[0-9]+")) }
                ?: if (host in setOf("pmc.ncbi.nlm.nih.gov", "www.ncbi.nlm.nih.gov", "ncbi.nlm.nih.gov")) Regex("/articles/(PMC[0-9]+)", RegexOption.IGNORE_CASE).find(uri.path)?.groupValues?.get(1)?.uppercase(Locale.ROOT).orEmpty() else ""
            val group = when {
                normalizedDoi.isNotBlank() -> "doi:$normalizedDoi"
                normalizedPmid.isNotBlank() -> "pmid:$normalizedPmid"
                normalizedPmcid.isNotBlank() -> "pmcid:$normalizedPmcid"
                else -> "url:${researchHash(url)}"
            }
            return CombinedWorkIdentity(
                id = group + version.takeIf(String::isNotBlank)?.let { "@version:$it" }.orEmpty(),
                canonicalUrl = url,
                doi = normalizedDoi,
                pmid = normalizedPmid,
                pmcid = normalizedPmcid,
                version = version,
                mirrors = setOf(url),
                independenceGroup = group,
                identityMethod = if (normalizedDoi.isNotBlank() || normalizedPmid.isNotBlank() || normalizedPmcid.isNotBlank()) "EXPLICIT_IDENTIFIER" else "CANONICAL_URL_ONLY"
            )
        }

        /** Crosswalk only explicit identifiers. Similar titles never establish the same study. */
        private fun canonicalize(rows: List<CombinedEvidenceRecord>): List<CombinedEvidenceRecord> {
            val byUrl = rows.groupBy { it.url to it.work?.version.orEmpty() }.values.map { versions ->
                val best = versions.maxWith(compareBy<CombinedEvidenceRecord> { level(it.retrieval) }.thenBy { it.passage.length })
                val explicit = versions.mapNotNull { it.work }.maxByOrNull { listOf(it.doi, it.pmid, it.pmcid).count(String::isNotBlank) }
                best.copy(
                    title = versions.firstOrNull { it.title.isNotBlank() }?.title.orEmpty(),
                    publishedAt = versions.firstOrNull { it.publishedAt.isNotBlank() }?.publishedAt.orEmpty(),
                    toolEventIds = versions.flatMapTo(linkedSetOf()) { it.toolEventIds },
                    profileUids = versions.flatMapTo(linkedSetOf()) { it.profileUids },
                    work = explicit ?: identity(best.url),
                    excerpts = versions.flatMap { it.retainedExcerpts }.groupBy { it.digest to it.retrieval }.values.map { copies ->
                        copies.first().copy(toolEventIds = copies.flatMapTo(linkedSetOf()) { it.toolEventIds }, permissionScopes = copies.flatMapTo(linkedSetOf()) { it.permissionScopes })
                    },
                    permissionScopes = versions.flatMapTo(linkedSetOf()) { it.permissionScopes },
                    ancestryIds = versions.flatMapTo(linkedSetOf()) { it.ancestryIds },
                    retrievedAt = versions.mapNotNull { it.retrievedAt }.maxOrNull()
                )
            }
            val parent = IntArray(byUrl.size) { it }
            fun root(index: Int): Int {
                var current = index
                while (parent[current] != current) current = parent[current]
                return current
            }
            val identifiers = mutableMapOf<String, Int>()
            val conflicting = mutableSetOf<Int>()
            byUrl.forEachIndexed { index, row ->
                val work = row.work ?: return@forEachIndexed
                listOfNotNull(work.doi.takeIf(String::isNotBlank)?.let { "doi:$it" }, work.pmid.takeIf(String::isNotBlank)?.let { "pmid:$it" }, work.pmcid.takeIf(String::isNotBlank)?.let { "pmcid:$it" }).forEach { id ->
                    val old = identifiers.putIfAbsent(id, index)
                    if (old != null) {
                        val roots = setOf(root(index), root(old))
                        val works = byUrl.indices.filter { root(it) in roots }.mapNotNull { byUrl[it].work }
                        val consistent = listOf(works.map { it.doi }, works.map { it.pmid }, works.map { it.pmcid }).all { values -> values.filter(String::isNotBlank).distinct().size <= 1 }
                        if (consistent) {
                            parent[root(index)] = root(old)
                        } else {
                            conflicting += index
                            conflicting += old
                        }
                    }
                }
            }
            val groups = byUrl.indices.groupBy(::root)
            return byUrl.mapIndexed { index, row ->
                val group = groups.getValue(root(index)).map(byUrl::get)
                val works = group.mapNotNull { it.work }
                val doi = works.map { it.doi }.firstOrNull(String::isNotBlank).orEmpty()
                val pmid = works.map { it.pmid }.firstOrNull(String::isNotBlank).orEmpty()
                val pmcid = works.map { it.pmcid }.firstOrNull(String::isNotBlank).orEmpty()
                val work = identity(row.url, doi, pmid, pmcid, row.work?.version.orEmpty())
                row.copy(work = work.copy(mirrors = group.mapTo(linkedSetOf()) { it.url }, identityMethod = if (index in conflicting) "CONFLICTING_IDENTIFIERS_UNRESOLVED" else work.identityMethod))
            }
        }

        fun freeze(corpus: ResearchCorpus?, events: List<ToolEvent>, runProfiles: Map<String, String>): CombinedEvidenceLedger {
            val rows = mutableListOf<CombinedEvidenceRecord>()
            fun add(url: String, title: String, date: String, text: String, retrieval: String, event: ToolEvent? = null, metadata: JsonObject? = null, retrievedAt: Long? = null) {
                val canonical = runCatching { SourceIdentity.canonicalResearchUrl(url) }.getOrNull().orEmpty()
                if (!canonical.startsWith("https://") && !canonical.startsWith("http://")) return
                if (runCatching { URI(canonical).userInfo != null }.getOrDefault(true)) return
                fun field(key: String): String = (metadata?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
                val work = identity(canonical, field("doi"), field("pmid"), field("pmcid"), field("version"))
                val id = "E" + researchHash(canonical + work.version.takeIf(String::isNotBlank)?.let { "@$it" }.orEmpty()).take(20)
                val scopes = setOfNotNull(event?.let { "${it.connectionUidSnapshot ?: "NATIVE"}:${runProfiles[it.runId].orEmpty()}" })
                val eventIds = setOfNotNull(event?.eventId)
                val blocked = Regex("(?is)^(?:access denied|forbidden|403 forbidden|checking your browser|verify you are human|just a moment[.!]?)\\b|<title>\\s*(?:just a moment|access denied|403 forbidden|attention required)").containsMatchIn(text.trim().take(4096)) ||
                    field("status").lowercase(Locale.ROOT) in setOf("blocked", "failed", "error") ||
                    field("status_code") in setOf("401", "403", "429")
                val receivedLevel = if (blocked) "UNREADABLE_RESPONSE" else retrieval
                rows += CombinedEvidenceRecord(
                    id, canonical, title, date, text, receivedLevel, eventIds, setOfNotNull(event?.runId?.let(runProfiles::get)), work,
                    listOf(CombinedEvidenceExcerpt("$id:${researchHash(text).take(16)}", text, retrieval = receivedLevel, toolEventIds = eventIds, permissionScopes = scopes)),
                    event?.completedAt ?: retrievedAt, scopes, setOf("work:${work.independenceGroup}")
                )
            }
            corpus?.sources.orEmpty().forEach { source ->
                val metadata = kotlinx.serialization.json.buildJsonObject {
                    put("doi", JsonPrimitive(source.doi))
                    put("pmid", JsonPrimitive(source.pmid))
                    put("pmcid", JsonPrimitive(source.pmcid))
                    put("version", JsonPrimitive(source.versionId))
                }
                add(
                    source.canonicalUrl.ifBlank { source.url },
                    source.title,
                    source.publishedAt,
                    source.passage,
                    if (source.readable && source.passage.isNotBlank()) {
                        "READABLE_PASSAGE"
                    } else if (source.passage.isNotBlank()) {
                        "SEARCH_SNIPPET"
                    } else {
                        "TITLE_ONLY"
                    },
                    metadata = metadata,
                    retrievedAt = source.retrievedAt.takeIf { it > 0 }
                )
                if (source.readable) {
                    source.bodyChunks.filter(String::isNotBlank).forEach { chunk ->
                        add(source.canonicalUrl.ifBlank { source.url }, source.title, source.publishedAt, chunk, "READABLE_PASSAGE", metadata = metadata, retrievedAt = source.retrievedAt.takeIf { it > 0 })
                    }
                }
            }
            events.filter { !it.isError && it.status == "COMPLETED" }.forEach { event ->
                val tool = event.toolName.lowercase(Locale.ROOT)
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
                            val body = text("content", "text", "passage", "raw_content", "markdown", "full_text", "extracted_content", "excerpt", "snippet", "description", "abstract")
                            val nested = runCatching { Json.parseToJsonElement(body) }.getOrNull()
                            if (nested is JsonObject || nested is JsonArray) {
                                walk(nested, depth + 1)
                            } else if (url.isNotBlank() || reader && requestedUrl.isNotBlank() && body.isNotBlank()) {
                                val retrieval = when {
                                    reader && body.isNotBlank() && text("full_text_available") == "true" -> "FULL_TEXT_AVAILABLE"
                                    reader && body.isNotBlank() -> "READABLE_PASSAGE"
                                    body.isNotBlank() -> "SEARCH_SNIPPET"
                                    else -> "TITLE_ONLY"
                                }
                                add(url.ifBlank { requestedUrl }, text("title", "name"), text("publishedAt", "published_at", "publishedDate", "date", "publication_date"), body, retrieval, event, value)
                            }
                            value.values.filter { it is JsonObject || it is JsonArray }.forEach { walk(it, depth + 1) }
                        }
                        is JsonPrimitive -> {
                            val body = value.contentOrNull.orEmpty()
                            val nested = runCatching { Json.parseToJsonElement(body) }.getOrNull()
                            if (nested is JsonObject || nested is JsonArray) {
                                walk(nested, depth + 1)
                            } else if (reader && requestedUrl.isNotBlank() && body.isNotBlank()) {
                                add(requestedUrl, "", "", body, "READABLE_PASSAGE", event)
                            }
                        }
                        else -> Unit
                    }
                }
                val result = event.result.orEmpty()
                val payload = runCatching { Json.parseToJsonElement(result) }.getOrNull()
                if (payload != null) walk(payload)
                if (reader && payload == null && result.isNotBlank() && requestedUrl.isNotBlank()) add(requestedUrl, "", "", result, "READABLE_PASSAGE", event)
            }
            val dateEvent = events.filter { !it.isError && it.status == "COMPLETED" && it.connectionUidSnapshot == null && it.toolName == "current_date" }.maxByOrNull { it.completedAt ?: 0 }
            val datePayload = dateEvent?.result?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            val nestedDate = (datePayload?.get("text") as? JsonPrimitive)?.contentOrNull?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: datePayload
            val date = listOf("date", "time", "zone").mapNotNull { key -> (nestedDate?.get(key) as? JsonPrimitive)?.contentOrNull?.let { key to it } }.toMap()
            return CombinedEvidenceLedger(canonicalize(rows), (events.filter { it.isError || it.status == "FAILED" }.map { it.toolName + ":" + (it.error ?: "FAILED") } + rows.filter { it.retrieval == "UNREADABLE_RESPONSE" }.map { "UNREADABLE_RESPONSE:${it.id}" }).distinct(), date)
        }
    }
}
