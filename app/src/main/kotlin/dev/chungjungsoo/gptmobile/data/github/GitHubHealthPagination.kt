package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Collects at most 2,000 checks, retaining explicit incompleteness on any changing inventory. */
internal suspend fun collectGitHubHealth(fetch: suspend (String?) -> JsonObject): JsonObject {
    fun pr(root: JsonObject) = ((root["data"] as? JsonObject)?.get("repository") as? JsonObject)?.get("pullRequest") as? JsonObject
    fun rollup(root: JsonObject): JsonObject? {
        val nodes = (pr(root)?.get("commits") as? JsonObject)?.get("nodes") as? JsonArray
        return ((nodes?.singleOrNull() as? JsonObject)?.get("commit") as? JsonObject)?.get("statusCheckRollup") as? JsonObject
    }
    fun contexts(root: JsonObject) = rollup(root)?.get("contexts") as? JsonObject
    val first = fetch(null)
    val initial = contexts(first) ?: return first
    if ((initial["pageInfo"] as? JsonObject)?.get("hasNextPage") != JsonPrimitive(true)) return first
    val rows = linkedMapOf<String, JsonObject>()
    val cursors = mutableSetOf<String>()
    var page = first
    var complete = false
    for (pageIndex in 0 until 20) {
        val value = contexts(page) ?: return first
        if (pr(page)?.get("headRefOid") != pr(first)?.get("headRefOid") || pr(page)?.get("baseRefOid") != pr(first)?.get("baseRefOid") || value["totalCount"] != initial["totalCount"]) return first
        (value["nodes"] as? JsonArray).orEmpty().forEach { raw ->
            val node = raw as? JsonObject ?: return first
            val key = (node["id"] as? JsonPrimitive)?.contentOrNull ?: listOf(node["__typename"], node["name"], node["context"]).joinToString(":")
            rows[key] = node
        }
        val info = value["pageInfo"] as? JsonObject ?: return first
        if (info["hasNextPage"] == JsonPrimitive(false)) {
            complete = true
            break
        }
        val cursor = (info["endCursor"] as? JsonPrimitive)?.contentOrNull ?: return first
        if (!cursors.add(cursor) || rows.size >= 2000) return first
        page = fetch(cursor)
    }
    if (!complete) return first
    val after = fetch(null)
    if (pr(after)?.get("headRefOid") != pr(first)?.get("headRefOid") || pr(after)?.get("baseRefOid") != pr(first)?.get("baseRefOid") || contexts(after)?.get("totalCount") != initial["totalCount"]) return first
    fun replace(value: JsonObject): JsonObject = JsonObject(
        value.mapValues { (key, child) ->
            when {
                key == "contexts" && child is JsonObject && child.containsKey("totalCount") -> JsonObject(child + mapOf("nodes" to JsonArray(rows.values.toList()), "pageInfo" to JsonObject(mapOf("hasNextPage" to JsonPrimitive(false)))))
                child is JsonObject -> replace(child)
                child is JsonArray -> JsonArray(child.map { if (it is JsonObject) replace(it) else it })
                else -> child
            }
        }
    )
    return replace(after)
}
