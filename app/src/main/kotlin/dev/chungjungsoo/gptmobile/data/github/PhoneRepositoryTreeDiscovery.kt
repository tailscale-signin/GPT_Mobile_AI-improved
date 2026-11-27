package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A truncated recursive response is a useful skeleton, never a complete repository. */
internal object PhoneRepositoryTreeDiscovery {
    private const val MAX_PATHS = 20_000

    fun initial(response: JsonObject, treeSha: String): JsonObject {
        val entries = requireNotNull(response["tree"] as? JsonArray)
        val truncated = response["truncated"] == JsonPrimitive(true)
        return buildJsonObject {
            put("tree", JsonArray(entries.take(MAX_PATHS).map { project(it.jsonObject) }))
            put("tree_sha", treeSha)
            put("truncated", truncated || entries.size > MAX_PATHS)
            put("tree_capacity_reached", entries.size > MAX_PATHS)
            if (truncated) put("tree_frontier", JsonArray(listOf(directory("", treeSha))))
        }
    }

    suspend fun advance(
        retained: JsonObject,
        fetch: suspend (String) -> JsonObject,
        retryFailed: Boolean = false,
        checkpoint: suspend (JsonObject) -> Unit
    ): JsonObject {
        if (retained["truncated"] != JsonPrimitive(true) || retained["tree_capacity_reached"] == JsonPrimitive(true) || (!retryFailed && retained["tree_discovery_blocked"] == JsonPrimitive(true))) return retained
        val frontier = (retained["tree_frontier"] as? JsonArray)?.map { it.jsonObject }?.toMutableList()
            ?: mutableListOf(directory("", retained.text("tree_sha")))
        val entries = requireNotNull(retained["tree"] as? JsonArray).associateByTo(linkedMapOf()) { it.jsonObject.text("path") }
        var result = if (retryFailed) JsonObject(retained - "tree_discovery_blocked") else retained
        repeat(4) {
            currentCoroutineContext().ensureActive()
            if (frontier.isEmpty()) return result
            val next = frontier.first()
            val listing = GitHubRepositoryIndex.checkedPinnedTree(fetch(next.text("sha")), next.text("sha"))
            val children = requireNotNull(listing["tree"] as? JsonArray)
            // A non-recursive listing must identify each immediate child. If it
            // is also truncated, keep the old usable skeleton and exact frontier.
            if (listing["truncated"] != JsonPrimitive(false) || children.any {
                    val name = it.jsonObject.text("path")
                    name.isBlank() || name in setOf(".", "..") || name.any { c -> c == '/' || c == '\\' || c.isISOControl() }
                }
            ) {
                result = JsonObject(result + ("tree_discovery_blocked" to JsonPrimitive(true)))
                checkpoint(result)
                return result
            }
            val offset = (next["offset"] as? JsonPrimitive)?.intOrNull ?: 0
            require(offset in 0..children.size)
            val end = minOf(children.size, offset + 1024)
            val additions = children.subList(offset, end).map { child ->
                val row = child.jsonObject
                val path = listOf(next.text("path"), row.text("path")).filter(String::isNotBlank).joinToString("/")
                require(path.length <= 4096 && row.text("sha").matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")))
                JsonObject(project(row) + ("path" to JsonPrimitive(path)))
            }
            if ((entries.keys + additions.map { it.text("path") }).size > MAX_PATHS) {
                result = JsonObject(result + ("tree_capacity_reached" to JsonPrimitive(true)))
                checkpoint(result)
                return result
            }
            frontier.removeAt(0)
            additions.forEach { row ->
                entries[row.text("path")] = row
                if (row.text("type") == "tree" && row.text("mode") == "040000") frontier += directory(row.text("path"), row.text("sha"))
            }
            if (end < children.size) frontier.add(0, JsonObject(next + ("offset" to JsonPrimitive(end))))
            result = buildJsonObject {
                put("tree", JsonArray(entries.values.toList()))
                put("tree_sha", retained.getValue("tree_sha"))
                put("truncated", frontier.isNotEmpty())
                put("tree_frontier", JsonArray(frontier))
                put("pending_directories", frontier.size)
                put("tree_capacity_reached", false)
            }
            checkpoint(result)
        }
        return result
    }

    private fun directory(path: String, sha: String) = buildJsonObject {
        put("path", path)
        put("sha", sha)
        put("offset", 0)
    }

    private fun project(row: JsonObject) = JsonObject(row.filterKeys { it in setOf("path", "sha", "size", "type", "mode") })
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
