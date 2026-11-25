package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Only source-derived topology is shared. Each request computes its own permissions, coverage, paging and result. */
internal data class PhoneMapTopology(val nodes: List<JsonObject>, val edges: List<JsonObject>, val truncated: Boolean, val unresolved: Int) {
    suspend fun save(store: GitHubRepositoryStateStore, scope: String, key: String) {
        suspend fun saveRows(kind: String, rows: List<JsonObject>) {
            var offset = 0
            while (offset < rows.size) {
                val end = minOf(rows.size, offset + PAGE_SIZE)
                store.write(scope, "$key:$kind:$offset", buildJsonObject { put("rows", JsonArray(rows.subList(offset, end))) })
                offset = end
            }
        }
        saveRows("nodes", nodes)
        saveRows("edges", edges)
        // Publishing the manifest last makes interrupted writes invisible to readers.
        store.write(
            scope,
            key,
            buildJsonObject {
                put("nodes", nodes.size)
                put("edges", edges.size)
                put("truncated", truncated)
                put("unresolved", unresolved)
            }
        )
    }

    companion object {
        private const val PAGE_SIZE = 256

        fun key(context: GitHubTaskSnapshot, cached: Map<String, JsonObject>, paths: JsonArray?): String {
            val identity = buildString {
                appendLine(context.connectionUid)
                appendLine(context.treeSha)
                appendLine(PhoneRepositoryGraph.PARSER_VERSION)
                appendLine(paths.orEmpty().filterIsInstance<JsonPrimitive>().take(8).distinct().sortedBy { it.toString() })
                for ((path, source) in cached.toSortedMap()) {
                    append(JsonPrimitive(path))
                    append(source["sha"])
                    val shard = source["phone_graph"] as? JsonObject
                    append(shard?.get("source_hash"))
                    appendLine(shard?.get("complete"))
                }
            }
            return "phone-graph:topology-v1:" + GitHubRepositoryStateStore.digest(identity)
        }

        suspend fun load(store: GitHubRepositoryStateStore, scope: String, key: String): PhoneMapTopology? {
            val manifest = store.read(scope, key) ?: return null
            val nodes = (manifest["nodes"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..40_000 } ?: return null
            val edges = (manifest["edges"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..60_000 } ?: return null
            val truncated = (manifest["truncated"] as? JsonPrimitive)?.booleanOrNull ?: return null
            val unresolved = (manifest["unresolved"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: return null
            suspend fun rows(kind: String, count: Int): List<JsonObject>? {
                val result = ArrayList<JsonObject>(count)
                while (result.size < count) {
                    val page = store.read(scope, "$key:$kind:${result.size}")?.get("rows") as? JsonArray ?: return null
                    if (page.size != minOf(PAGE_SIZE, count - result.size) || page.any { it !is JsonObject }) return null
                    result.addAll(page.filterIsInstance<JsonObject>())
                }
                return result
            }
            return PhoneMapTopology(rows("nodes", nodes) ?: return null, rows("edges", edges) ?: return null, truncated, unresolved)
        }
    }
}
