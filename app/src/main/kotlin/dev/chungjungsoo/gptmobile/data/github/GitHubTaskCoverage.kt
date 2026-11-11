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
    private val stress = Regex("(?i)^(?:please\\s+)?(?:(?:do|run|perform|execute)\\s+(?:a\\s+|the\\s+)?)?(?:(?:massive|full|complete|comprehensive|integration|extended)\\s+)*(?:stress[- ]?test|benchmark|load[- ]?test)\\b")
    private val fullTest = Regex("(?i)^(?:please\\s+)?(?:do|run|perform|execute)\\s+(?:(?:a|the)\\s+)?(?:(?:full|complete|comprehensive|integration|smoke)\\s+)*tests?\\b")
    fun matches(task: String) = !dev.chungjungsoo.gptmobile.data.agent.CurrentRequestContract.from(task).conversational && (testing.containsMatchIn(task.trim()) || stress.containsMatchIn(task.trim()) || fullTest.containsMatchIn(task.trim()))

    /** A diagnostic turn has a finite acquisition budget; explicit history audits keep pagination. */
    fun boundedDiagnostic(task: String): Boolean {
        val text = task.lowercase()
        val history = Regex("\\b(?:all|entire|complete|full)\\s+(?:workflow\\s+|ci\\s+|run\\s+)?history\\b|\\ball\\s+(?:workflow\\s+)?runs\\b").containsMatchIn(text)
        val sessionDiagnosis = listOf("explain", "diagnos", "investigat", "analy", "why").any { it in text } && listOf("this session", "this very session", "connection", "integration", "interrupt", "failure", "failing", "failed", "error").any { it in text }
        val integrationTest = matches(task) && (stress.containsMatchIn(task.trim()) || text.trim() == "test" || listOf("tools", "tool system", "integration", "connection", "this session").any { it in text })
        return !history && (integrationTest || sessionDiagnosis)
    }

    fun rows(receipts: Map<String, JsonObject>): List<JsonObject> = receipts.entries
        .filter { it.value["trusted"] == JsonPrimitive(true) || it.value["success"] == JsonPrimitive(false) && it.value["dispatched"] == JsonPrimitive(false) }
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
            val root = (receipt["result"] as? JsonObject)?.let { (it["data"] as? JsonObject) ?: it }
            val resourceBranch = receipt.text("resource_branch").ifBlank { (root?.get("head_branch") as? JsonPrimitive)?.contentOrNull.orEmpty() }
            val identifiedResource = tool == "github_ci" && (args?.containsKey("run_id") == true || args?.containsKey("job_id") == true)
            listOf(tool, action, resourceBranch.ifBlank { if (identifiedResource) "Resource branch not recorded" else receipt.text("branch") }, receipt.text("source_head_sha"), args?.text("pull_number").orEmpty()) +
                listOf("run_id", "job_id", "workflow_id", "issue_number", "artifact_id").map { args?.text(it).orEmpty() }
        }.map { (key, observations) ->
            val successful = observations.count { it["success"] == JsonPrimitive(true) }
            val errors = observations.mapNotNull { it.text("error_code").takeIf { code -> code.isNotBlank() && code != "null" } }.distinct()
            buildJsonObject {
                put("tool", key[0])
                put("action", key[1])
                put("branch", key[2])
                put("commit_sha", key[3])
                put("pull_number", key[4])
                listOf("run_id", "job_id", "workflow_id", "issue_number", "artifact_id").forEachIndexed { index, field -> if (key[index + 5].isNotBlank()) put(field, key[index + 5]) }
                put("successful_observations", successful)
                put("failed_observations", observations.size - successful)
                val kinds = observations.groupingBy(GitHubExecutionLedger::observationKind).eachCount()
                put("outcome_kinds", buildJsonObject { GitHubExecutionLedger.ObservationKind.entries.forEach { kind -> put(kind.name, kinds.getOrDefault(kind, 0)) } })
                put("remote_completed", kinds.getOrDefault(GitHubExecutionLedger.ObservationKind.REMOTE_API_SUCCESS, 0) + kinds.getOrDefault(GitHubExecutionLedger.ObservationKind.MUTATION_VERIFIED, 0))
                put("mutations_accepted_unverified", kinds.getOrDefault(GitHubExecutionLedger.ObservationKind.MUTATION_ACCEPTED, 0))
                put("mutations_verified", kinds.getOrDefault(GitHubExecutionLedger.ObservationKind.MUTATION_VERIFIED, 0))
                put("local_proposals_prepared", kinds.getOrDefault(GitHubExecutionLedger.ObservationKind.PROPOSAL_PREPARED, 0))
                put("pending_approval", kinds.getOrDefault(GitHubExecutionLedger.ObservationKind.PENDING_APPROVAL, 0))
                put("success_count_scope", "Tool outcome accepted; may be local, cached, suppressed or awaiting approval. Use outcome_kinds for execution semantics.")
                put("error_codes", JsonArray(errors.map(::JsonPrimitive)))
                put("receipt_ids", JsonArray(observations.mapNotNull { it["receipt_id"] }))
                put(
                    "excluded_from_current_verification",
                    JsonArray(
                        observations.filter { it["verification_eligible"] == JsonPrimitive(false) }.map {
                            buildJsonObject {
                                put("receipt_id", it["receipt_id"] ?: JsonPrimitive("unknown"))
                                put("reason", it["exclusion_reason"] ?: JsonPrimitive("AUTHORIZATION_REVALIDATION_REQUIRED"))
                            }
                        }
                    )
                )
            }
        }

    fun render(receipts: Map<String, JsonObject>, task: String = "test all tools", tools: List<dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition> = emptyList(), remainingToolCalls: Int? = null): String = buildString {
        if (GitHubTestChecklist.integration(task)) {
            append(GitHubIntegrationReport.render(task, receipts, tools, remainingToolCalls))
            return@buildString
        }
        val complete = GitHubTestChecklist.apiComplete(task, receipts, tools)
        append(if (complete) "**Task outcome: authorized API coverage completed.** This covers the requested API capabilities, not device health or all application test suites.\n\n" else "**Task outcome: partial verification.** The table records observed tool operations, including failures. It does not establish that every feature or test suite passed.\n\n")
        val rows = rows(receipts)
        if (rows.isEmpty()) {
            append("**Test not executed.** No trusted GitHub operation receipts are available for this task. No success rate or timing measurement is available.\n")
        } else {
            append("| Tool / operation | Branch / SHA / PR | Successful observations | Failed observations | Recorded errors |\n| --- | --- | ---: | ---: | --- |\n")
            val detailed = Regex("(?i)\\b(?:detailed|full|complete|all)\\s+(?:report|results|receipts|coverage)\\b").containsMatchIn(task)
            val visible = if (detailed) rows else rows.sortedByDescending { (it["failed_observations"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0 }.take(12)
            visible.forEach { row ->
                val errors = (row["error_codes"] as? JsonArray).orEmpty().joinToString(", ") { (it as JsonPrimitive).content }
                append("| ${safe(row.text("tool"))} / ${safe(row.text("action"))} | ${safe(listOf(row.text("branch"), row.text("commit_sha").takeUnless { it == "null" }.orEmpty(), row.text("pull_number").takeIf { it.isNotBlank() }?.let { "PR #$it" }.orEmpty()).filter(String::isNotBlank).joinToString(" / ").ifBlank { "Scope not recorded" })} | ${row.text("successful_observations")} | ${row.text("failed_observations")} | ${safe(errors.ifBlank { "None recorded" })} |\n")
            }
            if (visible.size < rows.size) append("\nShowing ${visible.size} of ${rows.size} operation groups, with failures first. The full evidence remains in the conversation export.\n")
        }
        append("\nFile reads establish source inspection. Workflow lists and existing check summaries establish CI observations. Test execution requires a run/job result tied to the tested revision; dispatch acceptance alone is insufficient.\n")
        if (GitHubTestChecklist.integration(task)) {
            append(GitHubTestChecklist.render(receipts, tools, task))
        } else {
            append("\n**Score:** Not measured until the requested test suite has execution results at its exact revision. **Solutions / next actions:** execute the requested tests, retain run/job evidence, resolve recorded failures and keep pending checks explicit.\n")
        }
        if (!complete) append("\n**Remaining scope:** reconcile the requested test checklist with execution receipts, including write operations and device tests where requested. A tool omitted from this table has no trusted receipt in this task; that does not mean it is unavailable.\n")
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun safe(value: String) = value.replace(Regex("[\\p{Cntrl}<>`*\\[\\]]"), " ").replace("|", "\\|")
}
