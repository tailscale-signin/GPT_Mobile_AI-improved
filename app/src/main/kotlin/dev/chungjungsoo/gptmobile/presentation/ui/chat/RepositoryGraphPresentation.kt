package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import java.net.URLEncoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** The architecture endpoint returns file dependencies, while symbol queries return nodes and edges. */
internal data class RepositoryGraphPresentation(
    val nodes: List<JsonObject>,
    val edges: List<JsonObject>,
    val externalConnections: Int = 0,
    val omittedNodes: Int = 0
) {
    fun search(query: String): RepositoryGraphPresentation {
        val term = query.trim()
        if (term.isEmpty()) return this
        val matches = nodes.filter { node -> listOf("qualified", "name", "path").any { node.graphText(it).contains(term, ignoreCase = true) } }
        return copy(nodes = matches)
    }

    fun relationships(id: String): List<JsonObject> = edges.filter { it.graphText("source") == id || it.graphText("target") == id }

    /** Put the inspected source beside its actual neighbors, retaining explicit off-diagram counts. */
    fun neighborhood(centerId: String?, size: Int = 12): RepositoryGraphPresentation {
        require(size > 0)
        if (nodes.isEmpty()) return this
        val byId = nodes.associateBy { it.graphText("id") }
        val center = centerId?.takeIf { it in byId } ?: nodes.first().graphText("id")
        val selected = linkedSetOf(center)
        val pending = ArrayDeque<String>().apply { add(center) }
        while (pending.isNotEmpty() && selected.size < size) {
            val current = pending.removeFirst()
            for (edge in relationships(current)) {
                val other = if (edge.graphText("source") == current) edge.graphText("target") else edge.graphText("source")
                if (other in byId && selected.add(other)) pending.add(other)
                if (selected.size == size) break
            }
        }
        nodes.forEach { if (selected.size < size) selected.add(it.graphText("id")) }
        return window(selected.mapNotNull(byId::get))
    }

    private fun window(visible: List<JsonObject>): RepositoryGraphPresentation {
        val ids = visible.mapTo(mutableSetOf()) { it.graphText("id") }
        val shown = edges.filter { it.graphText("source") in ids && it.graphText("target") in ids }
        val outside = edges.count { (it.graphText("source") in ids) xor (it.graphText("target") in ids) }
        return RepositoryGraphPresentation(visible, shown, externalConnections + outside, nodes.size - visible.size)
    }

    fun page(index: Int, size: Int = 12): RepositoryGraphPresentation {
        require(size > 0)
        val visible = nodes.drop(index.coerceAtLeast(0) * size).take(size)
        return window(visible)
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
        val external = (result["external_connections"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.coerceAtLeast(0) ?: 0
        return RepositoryGraphPresentation(sourceNodes, edges, externalConnections = external)
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

/** Candidate relationships must remain visually distinct from observed structure. */
internal fun JsonObject.graphCandidate(): Boolean = graphText("relation").contains("candidate", ignoreCase = true) ||
    graphText("confidence").lowercase() in setOf("candidate", "lexical", "heuristic", "low", "uncertain")

/** Source links use the selected immutable revision, including folders and known symbol ranges. */
internal fun repositoryGraphSourceUrl(context: GitHubConversationContext, node: JsonObject): String? {
    val path = node.graphText("path")
    if (path.isBlank() || path.split('/').any { it in setOf("", ".", "..") } || path.any { it.code < 32 || it.code == 127 || it == '\\' }) return null
    if (!context.commitSha.matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}"))) return null
    fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    val directory = node.graphText("kind") == "directory"
    val start = (node["line"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
        ?: (node["start_line"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
    val end = (node["end_line"] as? JsonPrimitive)?.intOrNull?.takeIf { start != null && it > start }
    val anchor = if (directory || start == null) "" else "#L$start" + end?.let { "-L$it" }.orEmpty()
    return "https://github.com/${encode(context.owner)}/${encode(context.repo)}/${if (directory) "tree" else "blob"}/${context.commitSha}/${path.split('/').joinToString("/", transform = ::encode)}$anchor"
}
