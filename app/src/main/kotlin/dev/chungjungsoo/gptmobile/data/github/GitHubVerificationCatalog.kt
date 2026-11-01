package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Select relevant evidence, with explicit omission counts. The full encrypted receipts remain authoritative. */
internal object GitHubVerificationCatalog {
    private val words = Regex("[a-zA-Z_][a-zA-Z0-9_]{2,}")
    private val useful = setOf("full_name", "name", "title", "path", "status", "state", "conclusion", "sha", "head_sha", "commit_sha", "draft", "merged", "number", "count", "total_count", "html_url", "default_branch", "head_branch")
    private val internal = setOf("node_id", "external_id", "avatar_url", "gravatar_id", "url", "schema", "operation")

    fun select(catalog: JsonArray, focus: String): List<JsonObject> {
        val terms = words.findAll(focus.lowercase()).map { it.value }.toSet()
        fun score(path: String, value: JsonElement): Int {
            val key = path.substringAfterLast('/')
            val text = "$path $value".lowercase()
            return (if (key in useful) 8 else 0) - (if (key in internal) 12 else 0) + terms.count { it in text }.coerceAtMost(16)
        }
        val ranked = catalog.mapNotNull { it as? JsonObject }.map { receipt ->
            val facts = receipt["facts"] as? JsonObject ?: JsonObject(emptyMap())
            val excerpts = receipt["excerpts"] as? JsonObject ?: JsonObject(emptyMap())
            val selectedFacts = facts.entries.sortedByDescending { score(it.key, it.value) }.take(32).associate { it.toPair() }
            val selectedExcerpts = excerpts.entries.sortedByDescending { score(it.key, it.value) }.take(if (GitHubTaskCoverage.matches(focus)) 0 else 2).associate { it.toPair() }
            JsonObject(
                receipt + mapOf(
                    "facts" to JsonObject(selectedFacts),
                    "excerpts" to JsonObject(selectedExcerpts),
                    "omitted_fact_count" to JsonPrimitive(facts.size - selectedFacts.size),
                    "omitted_excerpt_count" to JsonPrimitive(excerpts.size - selectedExcerpts.size)
                )
            )
        }.sortedByDescending { receipt ->
            (receipt["facts"] as? JsonObject).orEmpty().entries.maxOfOrNull { score(it.key, it.value) } ?: 0
        }
        // Cover each operation before spending the context on repeated reads of one file.
        val groups = ranked.groupBy { "${it["tool"]}:${(it["arguments"] as? JsonObject)?.get("action")}" }
        return groups.values.map { it.first() } + groups.values.flatMap { it.drop(1) }
    }
}
