package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Evidence-only terminal fallback. It never performs work or claims request acceptance. */
internal object GitHubPartialReport {
    fun observe(call: ProviderEvent.ToolCall, result: AgentToolResult): JsonObject? {
        val operation = GitHubOperationTool.operationName(call.name) ?: return null
        if (result.isError || "view_cursor" in call.arguments) return null
        val source = ((result.retainedContent ?: result.content) as? ToolResultContent.Json)?.value as? JsonObject ?: return null
        val summary = (result.executionReceipt?.get("semantic_summary") as? JsonObject) ?: GitHubSemanticSummary.from(operation, call.arguments, source)
        return JsonObject(summary + ("receipt_id" to JsonPrimitive(call.callId)))
    }

    fun fromReceipts(receipts: Map<String, JsonObject>): String = render(
        receipts.values.filter { it["trusted"] == JsonPrimitive(true) && it["verification_eligible"] != JsonPrimitive(false) && it["success"] == JsonPrimitive(true) }
            .mapNotNull { receipt ->
                val source = receipt["result"] as? JsonObject ?: return@mapNotNull null
                val args = receipt["arguments"] as? JsonObject ?: return@mapNotNull null
                if ("view_cursor" in args) return@mapNotNull null
                val summary = (receipt["semantic_summary"] as? JsonObject) ?: GitHubSemanticSummary.from(receipt.text("tool"), args, source)
                JsonObject(summary + ("receipt_id" to (receipt["receipt_id"] ?: JsonPrimitive("unknown"))))
            }
    )

    fun render(observations: Collection<JsonObject>): String {
        fun cite(row: JsonObject) = " (receipt `${safe(row.text("receipt_id"))}`)."
        val lines = mutableListOf<String>()
        observations.lastOrNull { it.text("commit_sha").isNotBlank() }?.let { row ->
            lines += "Observed revision: `${row.text("commit_sha")}`" + row.text("branch").takeIf(String::isNotBlank)?.let { " on `${safe(it)}`" }.orEmpty() + cite(row)
        }
        observations.lastOrNull { "open_pull_requests_observed" in it }?.let { row ->
            val coverage = if (row["open_pull_request_inventory_complete"] == JsonPrimitive(true)) "complete captured list" else "captured page; inventory is incomplete"
            lines += "Open pull requests observed: ${row["open_pull_requests_observed"]} ($coverage)" + cite(row)
        }
        observations.lastOrNull { "check_counts" in it }?.let { row ->
            val counts = row["check_counts"] as JsonObject
            val coverage = if (row["check_inventory_complete"] == JsonPrimitive(true)) "complete captured check list" else "partial captured check list"
            lines += "Checks: ${counts["successful"]} successful, ${counts["skipped"]} skipped, ${counts["in_progress"]} in progress, ${counts["failed"]} failed and ${counts["other_completed"]} other completed ($coverage)" + row.text("commit_sha").takeIf(String::isNotBlank)?.let { " at `$it`" }.orEmpty() + cite(row)
        }
        observations.lastOrNull { "required_checks_state" in it }?.takeIf { it["required_checks_state"] == JsonPrimitive("UNKNOWN") }?.let { row ->
            lines += "Required-check policy is unknown because protection or rules details were unavailable" + cite(row)
        }
        observations.lastOrNull { it["comparison_observed"] == JsonPrimitive(true) }?.let { row ->
            lines += "Comparison captured: `${row.text("base_sha")}` → `${row.text("head_sha")}`. Source-review completion remains unverified" + cite(row)
        }
        return if (lines.isEmpty()) "" else "**Established observations**\n\n" + lines.joinToString("\n") { "- $it" } + "\n\nThese retained observations do not establish completion of the requested task."
    }

    private fun safe(value: String) = value.replace(Regex("[\\p{Cntrl}`<>\\[\\]|]"), " ").take(180)
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
