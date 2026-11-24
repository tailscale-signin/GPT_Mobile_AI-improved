package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Rank observations for the accepted request without inspecting or replaying large raw tool bodies. */
internal class GitHubEvidenceRelevance(task: String) {
    private val terms = GitHubTaskContextBundle.terms(task.take(6000))
    private val numbers = Regex("""(?:#|\b(?:issue|pr|pull request)\s+)(\d+)\b""", RegexOption.IGNORE_CASE)
        .findAll(task.take(6000)).map { it.groupValues[1] }.toSet()

    fun score(arguments: JsonObject?, summary: JsonObject?): Int {
        if (terms.isEmpty() && numbers.isEmpty()) return 0
        val matched = mutableSetOf<String>()
        var selectedNumber = false
        fun inspect(row: JsonObject?) {
            if (row == null) return
            for (key in listOf("issue_number", "pull_number", "number")) {
                if ((row[key] as? JsonPrimitive)?.contentOrNull in numbers) selectedNumber = true
            }
            for (key in listOf("path", "path_prefix", "query", "symbol", "title", "excerpt", "reported_excerpt", "action")) {
                val value = (row[key] as? JsonPrimitive)?.contentOrNull ?: continue
                matched += GitHubTaskContextBundle.terms(value.take(480), 64).filter { it in terms }
            }
        }
        inspect(arguments)
        inspect(summary)
        inspect(summary?.get("source_range") as? JsonObject)
        for (key in listOf("resource_observations", "source_excerpts", "path_observations", "search_observations")) {
            (summary?.get(key) as? JsonArray)?.take(4)?.forEach { inspect(it as? JsonObject) }
        }
        return matched.size.coerceAtMost(24) + if (selectedNumber) 32 else 0
    }
}
