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
        return observation(summary, JsonPrimitive(call.callId), GitHubEvidenceOrigin.classify(result.dispatched, result.sharedResult, result.executionReceipt) == "CACHED" || source["schema"] == JsonPrimitive("github.repeat.v1"))
    }

    fun fromReceipts(receipts: Map<String, JsonObject>): String = render(
        receipts.values.filter { it["trusted"] == JsonPrimitive(true) && it["verification_eligible"] != JsonPrimitive(false) && it["success"] == JsonPrimitive(true) }
            .mapNotNull { receipt ->
                val source = receipt["result"] as? JsonObject ?: return@mapNotNull null
                val args = receipt["arguments"] as? JsonObject ?: return@mapNotNull null
                if ("view_cursor" in args) return@mapNotNull null
                val summary = (receipt["semantic_summary"] as? JsonObject) ?: GitHubSemanticSummary.from(receipt.text("tool"), args, source)
                observation(summary, receipt["receipt_id"] ?: JsonPrimitive("unknown"), GitHubEvidenceOrigin.of(receipt) == "CACHED" || source["schema"] == JsonPrimitive("github.repeat.v1"))
            }
    )

    fun render(observations: Collection<JsonObject>): String {
        val seen = mutableSetOf<Triple<String, String, String>>()
        val facts = observations.filter { row ->
            val digest = row.text("source_result_sha256")
            if (digest.isBlank()) return@filter true
            val firstObservation = seen.add(Triple(row.text("operation"), row.text("action"), digest))
            firstObservation || row["reused_observation"] != JsonPrimitive(true)
        }
        fun cite(row: JsonObject) = " (receipt `${safe(row.text("receipt_id"))}`)."
        val lines = mutableListOf<String>()
        facts.lastOrNull { it.text("commit_sha").isNotBlank() }?.let { row ->
            lines += "Observed revision: `${row.text("commit_sha")}`" + row.text("branch").takeIf(String::isNotBlank)?.let { " on `${safe(it)}`" }.orEmpty() + cite(row)
        }
        facts.lastOrNull { "open_pull_requests_observed" in it }?.let { row ->
            val coverage = if (row["open_pull_request_inventory_complete"] == JsonPrimitive(true)) "complete captured list" else "captured page; inventory is incomplete"
            lines += "Open pull requests observed: ${row["open_pull_requests_observed"]} ($coverage)" + cite(row)
        }
        facts.lastOrNull { "open_issue_count" in it || "open_pull_request_count" in it }?.let { row ->
            val counts = listOfNotNull(
                row["open_issue_count"]?.let { "$it open issues" },
                row["open_pull_request_count"]?.let { "$it open pull requests" }
            ).joinToString(" and ")
            lines += "Repository counts at observation: $counts. Counts do not establish code or test health" + cite(row)
        }
        facts.lastOrNull { "latest_workflow" in it }?.let { row ->
            val run = row["latest_workflow"] as JsonObject
            lines += "Latest observed workflow: ${safe(run.text("name").ifBlank { "unnamed workflow" })}, status ${safe(run.text("status").ifBlank { "unknown" })}, conclusion ${safe(run.text("conclusion").ifBlank { "not available" })}. This is one workflow observation; skipped is not a passed test suite" + cite(row)
        }
        facts.lastOrNull { "workflow_definitions_observed" in it }?.let { row ->
            val coverage = if (row["workflow_inventory_complete"] == JsonPrimitive(true)) "complete captured inventory" else "incomplete captured inventory"
            lines += "Workflow definitions observed: ${row["workflow_definitions_observed"]} ($coverage). Listing definitions does not run or verify those workflows" + cite(row)
        }
        facts.lastOrNull { it["failure_query_complete"] == JsonPrimitive(true) && it["failed_runs_observed"] == JsonPrimitive(0) }?.let { row ->
            lines += "No failed workflow runs were returned by the complete failure query for `${row.text("commit_sha")}`. Required checks and newly executed tests remain separate observations" + cite(row)
        }
        facts.lastOrNull { "check_counts" in it }?.let { row ->
            val counts = row["check_counts"] as JsonObject
            val coverage = if (row["check_inventory_complete"] == JsonPrimitive(true)) "complete captured check list" else "partial captured check list"
            lines += "Checks: ${counts["successful"]} successful, ${counts["skipped"]} skipped, ${counts["in_progress"]} in progress, ${counts["failed"]} failed and ${counts["other_completed"]} other completed ($coverage)" + row.text("commit_sha").takeIf(String::isNotBlank)?.let { " at `$it`" }.orEmpty() + cite(row)
        }
        facts.lastOrNull { "required_checks_state" in it }?.takeIf { it["required_checks_state"] == JsonPrimitive("UNKNOWN") }?.let { row ->
            lines += "Required-check policy is unknown because protection or rules details were unavailable" + cite(row)
        }
        facts.lastOrNull { it["comparison_observed"] == JsonPrimitive(true) }?.let { row ->
            lines += "Comparison captured: `${row.text("base_sha")}` → `${row.text("head_sha")}`. Source-review completion remains unverified" + cite(row)
        }
        return if (lines.isEmpty()) "" else "**Established observations**\n\n" + lines.joinToString("\n") { "- $it" } + "\n\nThese retained observations do not establish completion of the requested task."
    }

    private fun observation(summary: JsonObject, receiptId: kotlinx.serialization.json.JsonElement, reused: Boolean) =
        JsonObject(summary + mapOf("receipt_id" to receiptId, "reused_observation" to JsonPrimitive(reused)))

    private fun safe(value: String) = value.replace(Regex("[\\p{Cntrl}`<>\\[\\]|]"), " ").take(180)
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
