package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Display only metadata from a receipt already admitted for the selected repository/account. */
data class RepositoryTaskContextReceipt(val current: Boolean, val summary: List<String>, val sources: List<String>) {
    companion object {
        fun from(value: JsonObject?, selectedSha: String): RepositoryTaskContextReceipt? {
            if (value?.workspaceText("schema") != "repository.task_context.v1") return null
            val source = value.workspaceText("source_sha") ?: return null
            if (!source.isWorkspaceSha() || !selectedSha.isWorkspaceSha()) return null
            val current = source == selectedSha && value["source_current"] != JsonPrimitive(false)
            if (!current) return RepositoryTaskContextReceipt(false, listOf("Earlier source ${source.take(12)} · refresh context for ${selectedSha.take(12)}"), emptyList())
            fun objects(key: String) = (value[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().take(128)
            val ranges = objects("selected_ranges")
            val documents = objects("documents")
            val tests = (value["test_candidates"] as? JsonArray).orEmpty().take(128).filter { candidate ->
                val path = when (candidate) {
                    is JsonObject -> candidate.workspaceText("path")
                    is JsonPrimitive -> candidate.takeIf { it.isString }?.content
                    else -> null
                }
                path?.isWorkspacePath() == true
            }
            val coverage = value["coverage"] as? JsonObject
            val cache = value["cache"] as? JsonObject
            fun number(key: String) = (value[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
            val variant = value.workspaceText("variant")?.takeIf { it in setOf("CURRENT", "REFERENCE", "TASK", "SEMANTIC") } ?: "Unknown variant"
            val tokenCount = number("token_count")?.takeIf { value.workspaceText("token_count_status") == "MEASURED_SELECTED_TOKENIZER" }
            val tokenScope = if (value.workspaceText("token_scope") == "CONTEXT_WITH_USER_MESSAGE_FRAMING") " including user message framing" else ""
            val summary = buildList {
                add("${variant.lowercase().replaceFirstChar(Char::uppercase)} · source ${source.take(12)}")
                if (variant == "SEMANTIC") {
                    add(
                        when {
                            value["semantic_applied"] == JsonPrimitive(true) || value.workspaceText("retrieval") == "LOCAL_SEMANTIC_AND_LEXICAL" -> "Local similarity and lexical ranking applied."
                            value.workspaceText("semantic_fallback") == "SESSION_ONLY_MEMORY_POLICY" -> "Local similarity was skipped for session-only memory; lexical ranking applied."
                            else -> "Local similarity was unavailable or deferred; lexical ranking applied."
                        }
                    )
                }
                add("Prepared selection; final request admission may trim or reject this context.")
                add("${ranges.size} source ranges · ${documents.size} document sections · ${tests.size} test candidates")
                add("${(number("bytes") ?: number("content_bytes"))?.toString() ?: "Unknown"} bytes · ${tokenCount?.let { "$it measured tokens$tokenScope" } ?: "token count unavailable"}")
                number("preparation_ms")?.let { add("Preparation: $it ms") }
                if (coverage != null) {
                    add("Loaded ${coverage.workspaceCount("loaded_files") ?: "?"} of ${coverage.workspaceCount("eligible_files") ?: "?"} eligible files · ${if (coverage.workspaceFlag("exhaustive")) "complete coverage" else "selected coverage"}")
                    coverage.workspaceCount("selected_source_bytes")?.let { bytes ->
                        add("Prepared source excerpts: $bytes bytes · ${coverage.workspaceCount("selected_source_lines") ?: "?"} lines")
                    }
                    val callers = coverage.workspaceCount("caller_files") ?: 0
                    val configuration = coverage.workspaceCount("configuration_files") ?: 0
                    if (callers > 0 || configuration > 0) add("Task neighborhood: $callers caller candidates · $configuration build configuration files; relationships require source verification.")
                }
                if (cache != null) add("${cache.workspaceCount("cached_files") ?: "?"} cached source files · ${if (cache.workspaceFlag("cache_hit")) "cached selection reused" else "selection prepared"}")
                if (tests.isNotEmpty()) add("Test candidates are source references. Their presence does not mean the tests ran or passed.")
            }
            val sources = (ranges + documents).mapNotNull { row ->
                val path = row.workspaceText("path")?.takeIf { it.isWorkspacePath() } ?: return@mapNotNull null
                val blob = row.workspaceText("blob_sha")?.takeIf { it.isWorkspaceSha() } ?: return@mapNotNull null
                val start = row.workspaceCount("start_line")?.takeIf { it > 0 } ?: return@mapNotNull null
                val end = row.workspaceCount("end_line")?.takeIf { it >= start } ?: return@mapNotNull null
                "$path:$start–$end · ${blob.take(12)}"
            }.distinct().take(40)
            return RepositoryTaskContextReceipt(true, summary, sources)
        }
    }
}
