package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Deterministic task observations survive claim extraction and replay compaction. */
internal object GitHubFailureSummary {
    fun render(receipts: Map<String, JsonObject>): String {
        val failures = receipts.values.filter { it["trusted"] == JsonPrimitive(true) && it["success"] == JsonPrimitive(false) }
            .distinctBy { it["call_id"] ?: it["receipt_id"] }
            .groupingBy { (it["error_code"] as? JsonPrimitive)?.content?.takeUnless { code -> code == "null" || code.isBlank() } ?: "UNCLASSIFIED" }.eachCount()
        if (failures.isEmpty()) return ""
        return buildString {
            append("\n\n## Recorded operation failures\n\nThese are observed failures during this task; they do not establish the cause of an earlier interruption. Recovery actions below are recommendations.\n\n| Failure | Observations | Recommended action |\n| --- | ---: | --- |\n")
            failures.toSortedMap().forEach { (code, count) ->
                val action = when (code) {
                    "SAVED_VIEW_UNAVAILABLE" -> "Reopen the immutable source using its retained source-recovery call."
                    "SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_CURSOR_ALTERED" -> "Use the exact issued correction call and originating tool."
                    "INVALID_ARGUMENTS" -> "Discover exact paths and validate executable arguments; omit recovery envelopes."
                    "BUDGET_EXHAUSTED" -> "Resume unfinished work with a fresh bounded allowance; preserve completed receipts."
                    else -> "Inspect the retained error receipt and correct its cause before retrying."
                }
                append("| ").append(code.replace(Regex("[^A-Za-z0-9_]"), "").take(80)).append(" | ").append(count).append(" | ").append(action).append(" |\n")
            }
        }
    }
}
