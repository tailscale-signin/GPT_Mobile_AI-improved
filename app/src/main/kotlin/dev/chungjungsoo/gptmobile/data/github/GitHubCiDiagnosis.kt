package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Bounded evidence grouping, with hypotheses kept distinct from confirmed root causes. */
internal object GitHubCiDiagnosis {
    fun group(excerpts: List<JsonObject>, changedPaths: Set<String>): JsonArray {
        fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
        val symptoms = excerpts.filter { Regex("(?i)error|failed|failure|exception|fatal|timed? out|no space|connection reset").containsMatchIn(it.text("text")) }
        return JsonArray(
            symptoms.groupBy { row ->
                val text = row.text("text")
                when {
                    Regex("(?i)no space left|out of memory|runner.*(?:offline|lost)|connection reset|could not resolve host|rate limit|service unavailable").containsMatchIn(text) -> "INFRASTRUCTURE"
                    Regex("(?i)assertion|test.*fail|compile|unresolved reference|syntax error|type mismatch|lint").containsMatchIn(text) -> "CODE_OR_TEST"
                    Regex("(?i)unauthori[sz]ed|permission denied|authentication failed|forbidden").containsMatchIn(text) -> "ACCESS_OR_CONFIGURATION"
                    else -> "UNKNOWN"
                } to text.replace(Regex("\\b[0-9a-f]{8,40}\\b|\\b\\d+\\b"), "#").take(240)
            }.entries.take(30).map { (key, rows) ->
                val evidenceText = rows.joinToString("\n") { it.text("text") }
                val related = changedPaths.filter { it in evidenceText || it.substringAfterLast('/') in evidenceText }
                buildJsonObject {
                    put("category", key.first)
                    put("confidence", "LOG_PATTERN_HYPOTHESIS_NOT_CONFIRMED_ROOT_CAUSE")
                    put("occurrences_in_retained_excerpt", rows.size)
                    put("log_lines", JsonArray(rows.mapNotNull { it["line"] }))
                    put("excerpt", rows.first().text("text").take(1200))
                    put("matching_changed_paths", JsonArray(related.map(::JsonPrimitive)))
                    put(
                        "repair",
                        when (key.first) {
                            "INFRASTRUCTURE" -> "Check runner capacity and the named dependency/service; retry only after the condition clears."
                            "CODE_OR_TEST" -> "Inspect the cited failure and matching changed files; repair the cause and rerun the affected tests at the new SHA."
                            "ACCESS_OR_CONFIGURATION" -> "Revalidate the operation's account, installation and repository permissions before retrying."
                            else -> "Expand the cited log range and inspect the failed step before selecting a repair."
                        }
                    )
                }
            }
        )
    }
}
