package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Compact repository-tree projection used by AI tools. Large raw recursive
 * trees never need to be sent to the model.
 */
object GitHubRepositoryIndex {
    private val sourceExtensions = setOf(
        "kt", "kts", "java", "xml", "gradle", "md", "json", "yml", "yaml",
        "toml", "properties", "py", "js", "ts", "tsx", "jsx", "c", "cc",
        "cpp", "h", "hpp", "rs", "go", "sh"
    )

    /** Documentation discovery keeps blob provenance and reports incomplete GitHub trees. */
    fun documentation(treeResponse: JsonObject, page: Int, pageSize: Int = 30): JsonObject {
        require(page in 1..1000 && pageSize in 1..100)
        fun rank(path: String): Int {
            val lower = path.lowercase(java.util.Locale.ROOT)
            return when {
                lower == "llms.txt" -> 0
                lower == "readme.md" || lower == "readme.rst" || lower == "readme" -> 1
                lower == "llms-full.txt" -> 2
                else -> 3
            }
        }
        val documents = treeResponse["tree"]?.jsonArray.orEmpty().filterIsInstance<JsonObject>().filter { item ->
            val path = item["path"]?.jsonPrimitive?.content.orEmpty().lowercase(java.util.Locale.ROOT)
            val name = path.substringAfterLast('/')
            val regular = item["mode"]?.jsonPrimitive?.content in setOf("100644", "100755")
            regular &&
                item["type"]?.jsonPrimitive?.content == "blob" &&
                (
                    name in setOf("llms.txt", "llms-full.txt", "readme", "readme.md", "readme.rst") ||
                        (path.startsWith("docs/") && extension(path) in setOf("md", "rst", "txt", "mdx"))
                    )
        }.sortedWith(compareBy({ rank(it["path"]?.jsonPrimitive?.content.orEmpty()) }, { it["path"]?.jsonPrimitive?.content.orEmpty() }))
        return buildJsonObject {
            put(
                "documents",
                JsonArray(
                    documents.drop((page - 1) * pageSize).take(pageSize).map { item ->
                        JsonObject(item.filterKeys { it in setOf("path", "sha", "size") })
                    }
                )
            )
            put("page", page)
            put("has_more", page * pageSize < documents.size)
            put("total_indexed", documents.size)
            put("truncated", treeResponse["truncated"] ?: JsonPrimitive(false))
        }
    }

    fun compact(treeResponse: JsonObject, page: Int, pageSize: Int = 120): JsonObject {
        val all = treeResponse["tree"]?.jsonArray.orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { item ->
                item["type"]?.jsonPrimitive?.content == "tree" ||
                    extension(item["path"]?.jsonPrimitive?.content.orEmpty()) in sourceExtensions
            }
        val start = (page - 1) * pageSize
        return buildJsonObject {
            put("entries", buildJsonArray { all.drop(start).take(pageSize).forEach { add(project(it)) } })
            put("page", page)
            put("has_more", start + pageSize < all.size)
            put("total_indexed", all.size)
            put("truncated", treeResponse["truncated"] ?: JsonPrimitive(false))
        }
    }

    fun related(treeResponse: JsonObject, targetPath: String, limit: Int = 30): JsonArray {
        val directory = targetPath.substringBeforeLast('/', "")
        val stem = targetPath.substringAfterLast('/').substringBeforeLast('.').lowercase()
        val tokens = stem.split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
        val candidates = treeResponse["tree"]?.jsonArray.orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter {
                it["type"]?.jsonPrimitive?.content == "blob" &&
                    extension(it["path"]?.jsonPrimitive?.content.orEmpty()) in sourceExtensions
            }
            .map { item ->
                val path = item["path"]?.jsonPrimitive?.content.orEmpty()
                val lower = path.lowercase()
                val score =
                    (if (directory.isNotBlank() && path.startsWith("$directory/")) 4 else 0) +
                        tokens.count(lower::contains) * 2 +
                        (if ("/test" in lower || "/androidtest" in lower) 1 else 0)
                score to item
            }
            .filter { it.first > 0 && it.second["path"]?.jsonPrimitive?.content != targetPath }
            .sortedByDescending { it.first }
            .take(limit)

        return buildJsonArray {
            candidates.forEach { (score, item) ->
                add(
                    buildJsonObject {
                        put("score", score)
                        item["path"]?.let { put("path", it) }
                        item["sha"]?.let { put("sha", it) }
                        item["size"]?.let { put("size", it) }
                    }
                )
            }
        }
    }

    private fun project(item: JsonObject): JsonObject = buildJsonObject {
        listOf("path", "type", "mode", "sha", "size").forEach { key -> item[key]?.let { put(key, it) } }
    }

    private fun extension(path: String): String = path.substringAfterLast('.', "").lowercase()
}
