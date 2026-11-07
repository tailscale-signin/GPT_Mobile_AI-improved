package dev.chungjungsoo.gptmobile.presentation.ui.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** The architecture endpoint returns file dependencies, while symbol queries return nodes and edges. */
internal data class RepositoryGraphPresentation(val nodes: List<JsonObject>, val edges: List<JsonObject>) {
    fun page(index: Int, size: Int = 12): RepositoryGraphPresentation {
        require(size > 0)
        val visible = nodes.drop(index.coerceAtLeast(0) * size).take(size)
        val ids = visible.mapTo(mutableSetOf()) { it.graphText("id") }
        return RepositoryGraphPresentation(visible, edges.filter { it.graphText("source") in ids && it.graphText("target") in ids })
    }
}

internal fun repositoryGraphPresentation(result: JsonObject?): RepositoryGraphPresentation {
    if (result == null) return RepositoryGraphPresentation(emptyList(), emptyList())
    val sourceNodes = result.graphObjects("nodes").filter { it.graphText("id").isNotBlank() }.distinctBy { it.graphText("id") }
    if (sourceNodes.isNotEmpty()) {
        val ids = sourceNodes.mapTo(mutableSetOf()) { it.graphText("id") }
        val edges = result.graphObjects("edges")
            .filter { it.graphText("source") in ids && it.graphText("target") in ids }
            .distinctBy { Triple(it.graphText("source"), it.graphText("target"), it.graphText("relation")) }
        return RepositoryGraphPresentation(sourceNodes, edges)
    }
    val files = linkedSetOf<String>()
    val dependencies = mutableSetOf<Pair<String, String>>()
    (result["dependencies"] as? JsonObject).orEmpty().forEach { (source, targets) ->
        if (source.isNotBlank()) {
            files.add(source)
            (targets as? JsonArray).orEmpty().forEach { target ->
                val path = (target as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return@forEach
                files.add(path)
                if (path != source) dependencies.add(source to path)
            }
        }
    }
    result.graphObjects("communities").forEach { community ->
        (community["files"] as? JsonArray).orEmpty().mapNotNullTo(files) { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
    }
    result.graphObjects("hubs").mapNotNullTo(files) { it.graphText("path").takeIf(String::isNotBlank) }
    val degrees = mutableMapOf<String, Int>()
    dependencies.forEach { (source, target) ->
        degrees[source] = (degrees[source] ?: 0) + 1
        degrees[target] = (degrees[target] ?: 0) + 1
    }
    val nodes = files.sortedWith(compareByDescending<String> { degrees[it] ?: 0 }.thenBy { it }).map { path ->
        buildJsonObject {
            put("id", path)
            put("name", path.substringAfterLast('/'))
            put("qualified", path)
            put("path", path)
            put("kind", "file")
        }
    }
    val edges = dependencies.sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second }).map { (source, target) ->
        buildJsonObject {
            put("source", source)
            put("target", target)
            put("relation", "depends_on")
        }
    }
    return RepositoryGraphPresentation(nodes, edges)
}

internal fun JsonObject.graphText(name: String, default: String = ""): String = (get(name) as? JsonPrimitive)?.contentOrNull ?: default
internal fun JsonObject.graphObjects(name: String): List<JsonObject> = (get(name) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
