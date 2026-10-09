package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun <T> roundRobin(buckets: List<List<T>>): List<T> = buildList {
    for (rank in 0 until (buckets.maxOfOrNull { it.size } ?: 0)) {
        for (bucket in buckets) bucket.getOrNull(rank)?.let(::add)
    }
}

internal data class SearchMergeResult(
    val sources: List<JsonObject>,
    val retainedSources: List<JsonObject>,
    val contributions: Map<Int, Int>,
    val uniqueGroups: Map<Int, Int>,
    val duplicatesByOwner: Map<Int, Int>,
    val candidateCount: Int,
    val urlDuplicates: Int,
    val contentDuplicates: Int,
    val shadowContentDuplicates: Int,
    val degradedContentChecking: Boolean
)

internal object SearchResultMerger {
    private data class Group(val representative: SearchCandidate, val labels: LinkedHashSet<JsonPrimitive>, val aliases: MutableList<JsonObject> = mutableListOf())

    fun merge(buckets: List<List<SearchCandidate>>, maxResults: Int, totalResults: Int, policy: SearchMergePolicy, checkActive: () -> Unit = {}): SearchMergeResult {
        val candidates = roundRobin(buckets)
        val groups = mutableListOf<Group>()
        val byUrl = mutableMapOf<String, Group>()
        var urlDuplicates = 0
        var contentDuplicates = 0
        var shadowDuplicates = 0
        var comparisons = 0
        var degraded = candidates.size > policy.maxCandidates
        val duplicatesByOwner = mutableMapOf<Int, Int>()
        fun labels(candidate: SearchCandidate): List<JsonPrimitive> =
            ((candidate.source["engines"] as? JsonArray).orEmpty().filterIsInstance<JsonPrimitive>() + listOfNotNull(candidate.source["engine"] as? JsonPrimitive, JsonPrimitive(candidate.providerLabel))).distinct()
        for (candidate in candidates.take(policy.maxCandidates)) {
            checkActive()
            var reason = "url"
            var score: Double? = null
            var group = byUrl[candidate.url.key].takeIf { policy.dedupeUrls }
            if (group != null) {
                urlDuplicates++
            } else if (policy.contentMode != SearchContentMode.OFF) {
                for (existing in groups) {
                    if (comparisons >= policy.maxContentComparisons) {
                        degraded = true
                        break
                    }
                    comparisons++
                    val match = candidate.fingerprint.match(existing.representative.fingerprint, policy) ?: continue
                    if (policy.contentMode == SearchContentMode.SHADOW) {
                        shadowDuplicates++
                    } else {
                        group = existing
                        reason = "similar_content"
                        score = match
                        contentDuplicates++
                    }
                    break
                }
            }
            if (group == null) {
                group = Group(candidate, LinkedHashSet(labels(candidate)))
                groups += group
            } else {
                duplicatesByOwner[candidate.ownerIndex] = (duplicatesByOwner[candidate.ownerIndex] ?: 0) + 1
                group.labels.addAll(labels(candidate))
                group.aliases += buildJsonObject {
                    candidate.source.forEach { (key, value) -> put(key, value) }
                    put("engine", candidate.source["engine"] ?: JsonPrimitive(candidate.providerLabel))
                    put("selectionId", candidate.selectionId)
                    put("providerRank", candidate.providerRank)
                    put("duplicateReason", reason)
                    score?.let { put("matchScore", it) }
                }
            }
            // Register content aliases too; approximate comparisons always use the original representative.
            byUrl[candidate.url.key] = group
        }
        val selected = roundRobin(buckets.indices.map { owner -> groups.filter { it.representative.ownerIndex == owner }.take(maxResults) }).take(totalResults)
        fun payload(group: Group, retain: Boolean): JsonObject = buildJsonObject {
            val representative = group.representative.source.toMutableMap()
            // Fill only absent metadata; never invent or combine conflicting snippets.
            for (field in listOf("title", "snippet", "publishedDate", "publisher", "language")) {
                val value = (representative[field] as? JsonPrimitive)?.content.orEmpty()
                if (value.isBlank() || (field == "title" && value == group.representative.url.navigationUrl)) {
                    group.aliases.firstNotNullOfOrNull { (it[field] as? JsonPrimitive)?.takeIf { item -> item.isString && item.content.isNotBlank() } }?.let { representative[field] = it }
                }
            }
            representative.filterKeys { it != "similarSources" }.forEach { (key, value) -> put(key, value) }
            put("engine", group.representative.source["engine"] ?: JsonPrimitive(group.representative.providerLabel))
            put("engines", JsonArray(group.labels.toList()))
            put("similarSourceCount", group.aliases.mapNotNull { (it["url"] as? JsonPrimitive)?.content }.distinct().count { it != group.representative.url.navigationUrl })
            if (retain && group.aliases.isNotEmpty()) put("similarSources", JsonArray(group.aliases))
        }
        return SearchMergeResult(selected.map { payload(it, false) }, selected.map { payload(it, true) }, selected.groupingBy { it.representative.ownerIndex }.eachCount(), groups.groupingBy { it.representative.ownerIndex }.eachCount(), duplicatesByOwner, candidates.size, urlDuplicates, contentDuplicates, shadowDuplicates, degraded)
    }
}
