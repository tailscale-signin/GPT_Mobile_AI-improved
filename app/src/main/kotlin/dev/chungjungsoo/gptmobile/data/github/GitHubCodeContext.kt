package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Revision-bound import/test candidates; lexical links never claim compiler resolution. */
internal object GitHubCodeContext {
    private val imports = Regex("^\\s*(?:import|from|use)\\s+([A-Za-z_][A-Za-z_0-9]*(?:(?:\\.|::)[A-Za-z_][A-Za-z_0-9]*)*(?:\\.\\*)?)")
    private val scriptImports = Regex("^\\s*(?:(?:import|export)\\s+.+?\\s+from\\s*|import\\s*)['\"]([^'\"]+)['\"]")
    private val scriptExtensions = setOf("js", "jsx", "ts", "tsx", "mjs", "cjs", "mts", "cts")

    private fun relativeCandidates(path: String, imported: String, tree: Set<String>): List<String> {
        val segments = path.substringBeforeLast('/', "").split('/').filter(String::isNotEmpty).toMutableList()
        for (segment in imported.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return emptyList() else segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        val target = segments.joinToString("/")
        val candidates = linkedSetOf(target)
        // These are lexical candidates only: package exports and compiler options may differ.
        scriptExtensions.forEach { extension -> candidates += "$target.$extension" }
        scriptExtensions.forEach { extension -> candidates += "$target/index.$extension" }
        if (target.substringAfterLast('.', "") in setOf("js", "jsx", "mjs", "cjs")) {
            val stem = target.substringBeforeLast('.')
            listOf("ts", "tsx", "mts", "cts").forEach { candidates += "$stem.$it" }
        }
        return candidates.filter { it in tree }.take(8)
    }

    fun build(sources: Map<String, String>, treePaths: List<String>, commitSha: String): JsonObject {
        val edges = mutableListOf<JsonObject>()
        val suggested = linkedSetOf<String>()
        var observed = 0
        var unresolved = 0
        var sourceTruncated = false
        val tree = treePaths.toSet()
        val candidatesByImport = mutableMapOf<String, List<String>>()
        for ((path, source) in sources) {
            val script = path.substringAfterLast('.', "") in scriptExtensions
            for ((index, line) in source.lineSequence().withIndex()) {
                if (index >= 50_000 || observed >= 512) {
                    sourceTruncated = true
                    break
                }
                if (line.length > 4000) {
                    sourceTruncated = true
                    continue
                }
                val imported = (if (script) scriptImports else imports).find(line)?.groupValues?.get(1) ?: continue
                observed++
                val suffix = imported.removeSuffix(".*").replace("::", ".").replace('.', '/')
                val wildcard = imported.endsWith(".*")
                val candidates = if (script) {
                    if (imported.startsWith("./") || imported.startsWith("../")) relativeCandidates(path, imported, tree) else emptyList()
                } else {
                    candidatesByImport.getOrPut(imported) {
                        treePaths.asSequence().filter { candidate ->
                            if (wildcard) {
                                candidate.substringBeforeLast('/', "").let { it == suffix || it.endsWith("/$suffix") }
                            } else {
                                candidate.substringBeforeLast('.').let { it == suffix || it.endsWith("/$suffix") }
                            }
                        }.take(8).toList()
                    }
                }
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
            if (observed >= 512) {
                sourceTruncated = true
                break
            }
        }
        val tests = sources.keys.associateWith { GitHubSearchContext.related(it, treePaths) }
        return buildJsonObject {
            put("schema_version", 1)
            put("commit_sha", commitSha)
            put("compiler_resolution", false)
            put("coverage", "Direct lexical imports, including static relative JavaScript/TypeScript imports, in loaded files only. Aliases, package exports, dynamic references, generated code and transitive dependencies require source/build verification.")
            put("imports_observed", observed)
            put("unresolved_or_external_imports", unresolved)
            put("source_scan_truncated", sourceTruncated)
            put("edges_bounded", edges.size >= 64 || sourceTruncated)
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
