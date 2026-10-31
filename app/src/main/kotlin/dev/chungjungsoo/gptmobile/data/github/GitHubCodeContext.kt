package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Revision-bound import/test candidates; lexical links never claim compiler resolution. */
internal object GitHubCodeContext {
    private val imports = Regex("^\\s*(?:import|from|use)\\s+([A-Za-z_][A-Za-z_0-9]*(?:(?:\\.|::)[A-Za-z_][A-Za-z_0-9]*)*(?:\\.\\*)?)")

    fun build(sources: Map<String, String>, treePaths: List<String>, commitSha: String): JsonObject {
        val edges = mutableListOf<JsonObject>()
        val suggested = linkedSetOf<String>()
        var observed = 0
        var unresolved = 0
        for ((path, source) in sources) {
            source.lineSequence().take(50_000).forEachIndexed { index, line ->
                if (line.length > 4000) return@forEachIndexed
                val imported = imports.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
                observed++
                if (observed > 512) return@forEachIndexed
                val suffix = imported.removeSuffix(".*").replace("::", ".").replace('.', '/')
                val wildcard = imported.endsWith(".*")
                val candidates = treePaths.asSequence().filter { candidate ->
                    if (wildcard) {
                        candidate.substringBeforeLast('/').endsWith("/$suffix")
                    } else {
                        candidate.substringBeforeLast('.').let { it == suffix || it.endsWith("/$suffix") }
                    }
                }.take(8).toList()
                if (candidates.isEmpty()) unresolved++
                for (target in candidates) {
                    if (edges.size >= 64) break
                    if (target !in sources) suggested += target
                    edges += buildJsonObject {
                        put("from", path)
                        put("to", target)
                        put("import", imported)
                        put("line", index + 1)
                        put("loaded", target in sources)
                        put("resolution", "same_commit_tree_candidate")
                        put("ambiguous", wildcard || candidates.size > 1)
                    }
                }
            }
        }
        val tests = sources.keys.associateWith { GitHubSearchContext.related(it, treePaths) }
        return buildJsonObject {
            put("schema_version", 1)
            put("commit_sha", commitSha)
            put("compiler_resolution", false)
            put("coverage", "Direct lexical imports in the loaded files only; dynamic references, generated code and transitive dependencies require source/build verification.")
            put("imports_observed", observed)
            put("unresolved_or_external_imports", unresolved)
            put("edges_bounded", edges.size >= 64 || observed > 512)
            put("edges", JsonArray(edges))
            put("test_candidates", JsonObject(tests))
            put(
                "suggested_ranges",
                JsonArray(
                    suggested.take(8).map { path ->
                        buildJsonObject {
                            put("path", path)
                            put("start_line", 1)
                            put("end_line", 200)
                        }
                    }
                )
            )
            put("guidance_paths", JsonArray(treePaths.filter { it.substringAfterLast('/') in setOf("AGENTS.md", "CONTRIBUTING.md", "DEVELOPMENT.md") }.take(12).map(::JsonPrimitive)))
        }
    }
}
