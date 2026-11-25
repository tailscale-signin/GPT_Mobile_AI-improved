package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The caller has already admitted the account and selected revision. Only read retained blobs. */
internal object GitHubCompactTaskContext {
    suspend fun prepare(context: GitHubTaskSnapshot, tree: JsonObject?, query: String, cachedBlob: suspend (String) -> JsonObject?): JsonObject {
        val hints = GitHubTaskContextBundle.cachedPathHints("${context.owner}/${context.repo}", context.ref, context.commitSha, tree, query)
        val paths = hints["source_read_candidates"] as JsonArray
        val path = (paths.firstOrNull() as? JsonPrimitive)?.contentOrNull
        val entry = (tree?.get("tree") as? JsonArray)?.asSequence()?.filterIsInstance<JsonObject>()
            ?.firstOrNull { path != null && it["path"] == JsonPrimitive(path) && it["type"] == JsonPrimitive("blob") && it["mode"] !in setOf(JsonPrimitive("120000"), JsonPrimitive("160000")) }
        val sha = (entry?.get("sha") as? JsonPrimitive)?.contentOrNull
        val size = (entry?.get("size") as? JsonPrimitive)?.longOrNull
        // A compact lookup can inspect one small text blob. Other candidates remain pointers.
        val window = if (path != null && sha != null && sha.matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")) && size != null && size in 1..65_536L) {
            val retained = cachedBlob(sha)?.takeIf { it["sha"] == JsonPrimitive(sha) }
            (retained?.get("content") as? JsonPrimitive)?.contentOrNull?.takeIf { it.length <= 65_536 && it.isNotBlank() }?.let { sourceWindow(path, sha, query, it) }
        } else {
            null
        }
        return buildJsonObject {
            put("repository", hints.getValue("repository"))
            put("branch", hints.getValue("branch"))
            put("commit_sha", hints.getValue("commit_sha"))
            put("source_sha", hints.getValue("source_sha"))
            put("tree_reused", tree != null)
            put("source_read_candidates", if (window == null) paths else JsonArray(paths.drop(1)))
            window?.let { put("source_window", it) }
        }
    }

    private fun sourceWindow(path: String, sha: String, query: String, source: String): JsonObject? {
        val lines = GitHubRepositorySession.sourceLines(source)
        val location = Regex("""(?:${Regex.escape(path)}|${Regex.escape(path.substringAfterLast('/'))}):(\d+)""").find(query)
        val terms = Regex("""[\p{L}\p{N}_]{3,}""").findAll(query.take(2048)).map { it.value }.distinct().take(16).toList()
        val start = if (location != null) {
            val line = location.groupValues[1].toIntOrNull()?.takeIf { it in 1..lines.size } ?: return null
            line - 1
        } else {
            lines.indices.maxByOrNull { index -> terms.count { lines[index].contains(it, ignoreCase = true) } } ?: return null
        }
        val excerpt = mutableListOf<String>()
        var bytes = 0
        for (line in lines.subList(start, minOf(lines.size, start + 6))) {
            if (line.length > 384) break
            val nextBytes = line.toByteArray(Charsets.UTF_8).size + if (excerpt.isEmpty()) 0 else 1
            if (bytes + nextBytes > 384) break
            excerpt.add(line)
            bytes += nextBytes
        }
        if (excerpt.all(String::isBlank)) return null
        val window = buildJsonObject {
            put("path", path)
            put("blob_sha", sha)
            put("start_line", start + 1)
            put("end_line", start + excerpt.size)
            put("excerpt", excerpt.joinToString("\n"))
        }
        return window.takeIf { it.toString().toByteArray(Charsets.UTF_8).size <= 896 }
    }
}
