package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Historical operation coverage; never upgrades an API observation into executed test coverage. */
internal object GitHubTaskCoverage {
    private val testing = Regex("(?i)^(?:please\\s+)?(?:test\\b|do\\s+testing\\b|run\\s+(?:all\\s+)?tests?\\b|(?:verify|validate)\\s+(?:all\\s+)?(?:tools|functionality|integrations)\\b)")
    private val stress = Regex("(?i)^(?:please\\s+)?(?:(?:do|run|perform|execute)\\s+(?:a\\s+|the\\s+)?)?(?:stress[- ]?test|benchmark|load[- ]?test)\\b")
    fun matches(task: String) = testing.containsMatchIn(task) || stress.containsMatchIn(task)

    fun rows(receipts: Map<String, JsonObject>): List<JsonObject> = receipts.entries
        .filter { it.value["trusted"] == JsonPrimitive(true) }
        .distinctBy { it.value.text("call_id").ifBlank { it.value.text("receipt_id").ifBlank { it.key } } }
        .map { it.value }
        .groupBy { receipt ->
            val args = receipt["arguments"] as? JsonObject
            val tool = receipt.text("tool").substringBefore("__")
            val action = args?.text("action").orEmpty().ifBlank {
                when {
                    args?.containsKey("view_cursor") == true -> "saved page"
                    tool == "github_read" -> "source read"
                    tool == "github_search" -> args?.text("mode").orEmpty().ifBlank { "search" }
                    else -> "default"
                }
            }
            tool to action
        }.map { (key, observations) ->
            val successful = observations.count { it["success"] == JsonPrimitive(true) }
            val errors = observations.mapNotNull { it.text("error_code").takeIf { code -> code.isNotBlank() && code != "null" } }.distinct()
            buildJsonObject {
                put("tool", key.first)
                put("action", key.second)
                put("successful_observations", successful)
                put("failed_observations", observations.size - successful)
                put("error_codes", JsonArray(errors.map(::JsonPrimitive)))
                put("receipt_ids", JsonArray(observations.mapNotNull { it["receipt_id"] }))
            }
        }

    fun render(receipts: Map<String, JsonObject>): String = buildString {
        append("**Task outcome: partial verification.** The table records observed tool operations, including failures. It does not establish that every feature or test suite passed.\n\n")
        val rows = rows(receipts)
        if (rows.isEmpty()) {
            append("**Test not executed.** No trusted GitHub operation receipts are available for this task. No success rate or timing measurement is available.\n")
        } else {
            append("| Tool / operation | Successful observations | Failed observations | Recorded errors |\n| --- | ---: | ---: | --- |\n")
            rows.forEach { row ->
                val errors = (row["error_codes"] as? JsonArray).orEmpty().joinToString(", ") { (it as JsonPrimitive).content }
                append("| ${safe(row.text("tool"))} / ${safe(row.text("action"))} | ${row.text("successful_observations")} | ${row.text("failed_observations")} | ${safe(errors.ifBlank { "None recorded" })} |\n")
            }
        }
        append("\nFile reads establish source inspection. Workflow lists and existing check summaries establish CI observations. Test execution requires a run/job result tied to the tested revision; dispatch acceptance alone is insufficient.\n")
        append("\n**Remaining scope:** reconcile the requested test checklist with execution receipts, including write operations and device tests where requested. A tool omitted from this table has no trusted receipt in this task; that does not mean it is unavailable.\n")
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun safe(value: String) = value.replace(Regex("[\\p{Cntrl}<>`*\\[\\]]"), " ").replace("|", "\\|")
}
