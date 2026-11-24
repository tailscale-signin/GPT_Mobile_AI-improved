package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.github.GitHubAcceptanceDiagnostics
import dev.chungjungsoo.gptmobile.data.github.GitHubAcceptanceWork
import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import dev.chungjungsoo.gptmobile.data.github.GitHubIssueRemediation
import dev.chungjungsoo.gptmobile.data.github.GitHubRequestCompletion
import dev.chungjungsoo.gptmobile.data.github.githubOperationFamily
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A schema-selection hint from accepted progress, never an execution plan or authorization. */
internal object GitHubRequirementToolFocus {
    data class Focus(val identity: String, val query: String, val operations: List<String>, val actions: Map<String, String>, val criterion: String, val stage: String)

    fun next(checkpoint: JsonObject?): Focus? {
        if (checkpoint == null || checkpoint["completion_verified"] == JsonPrimitive(true)) return null
        return fromRows(GitHubAcceptanceWork.outstanding(checkpoint).filterNot(GitHubAcceptanceWork::deferred))
    }

    internal fun fromRows(rows: List<JsonObject>): Focus? {
        val intake = rows.firstOrNull { it.text("id") == GitHubRequestCompletion.id(GitHubIssueRemediation.INVENTORY_REQUIREMENT) }
        if (intake != null) return focus(intake, "ISSUE_INVENTORY", "List the complete open issue inventory", listOf("github_issue"), mapOf("github_issue" to "list"))
        // Specific unfinished issue phases are actionable before the encompassing
        // bulk objective. An independently blocked group does not hide another group.
        val phases = rows.mapNotNull { row ->
            val phase = (row["issue_phases"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().firstOrNull { state(it) != "PASS" }
            phase?.let { row to it }
        }.sortedBy { (_, phase) -> state(phase) == "BLOCKED" }
        for ((row, phase) in phases) {
            when (phase.text("stage")) {
                "DIAGNOSIS" -> return focus(row, "DIAGNOSIS", "Inspect this issue report and pinned source", listOf("github_issue", "github_read", "github_search"))
                "CHANGE" -> return focus(row, "CHANGE", "Prepare and commit the issue repair or targeted diagnostics", listOf("github_stage_patch", "github_commit", "github_read"))
                "TEST" -> return focus(row, "TEST", "Inspect workflow runs and test jobs at the published revision", listOf("github_ci", "github_context"))
                "CLOSURE" -> return focus(row, "CLOSURE", "Read the issue, apply the authorized guarded update, and read back its state", listOf("github_issue"))
            }
        }
        for (row in rows) {
            val id = row.text("id")
            val operation = id.substringBefore(':').githubOperationFamily()
            val action = id.substringAfter(':', "")
            if (operation in GitHubOperationTool.names && action.isNotBlank() && GitHubActionSchema.project(GitHubOperationTool.definition(operation), action) != null) {
                return focus(row, "OPERATION:$operation:$action", "$operation $action", listOf(operation), mapOf(operation to action))
            }
            val criterion = row.text("criterion").ifBlank { row.text("requirement") }.take(2048)
            GitHubAcceptanceWork.step(row)?.let { step ->
                return focus(row, "ACCEPTANCE_GAP", step.action, step.tools)
            }
            when (GitHubAcceptanceDiagnostics.category(criterion)) {
                "CI_OBSERVATION", "CI_VERIFICATION" -> return focus(row, "CI", "Observe workflow runs, jobs and exact revision checks", listOf("github_ci", "github_context"))
                "BUILD_REVISION_BINDING" -> return focus(row, "REVISION", "Read the current repository revision and branch", listOf("github_context"), mapOf("github_context" to "resolve"))
                "REPOSITORY_GRAPH" -> return focus(row, "GRAPH", "Inspect the selected repository graph", listOf("repository_graph", "repository_workspace"))
                "FIXTURE_LIFECYCLE" -> return focus(row, "FIXTURE", "Inspect retained owned fixture recovery before cleanup", listOf("github_context", "github_commit"), mapOf("github_context" to "recovery"))
                "REPOSITORY_LIFECYCLE" -> return focus(row, "REPOSITORY", "Observe the authorized repository lifecycle operation", listOf("github_repository"))
                "AUTHORIZED_CATALOG" -> return focus(row, "CATALOG", "Inspect the authorized tool inventory", emptyList())
                else -> {
                    val operations = GitHubOperationTool.taskTools(criterion).toList()
                    if (operations.isNotEmpty()) return focus(row, "REQUIREMENT", criterion, operations)
                }
            }
        }
        return null
    }

    private fun focus(row: JsonObject, step: String, query: String, operations: List<String>, actions: Map<String, String> = emptyMap()) =
        Focus(GitHubEvidenceBook.digest(row.text("id") + "|" + step + "|" + query), query, operations, actions, row.text("id"), step)
    private fun state(row: JsonObject) = row.text("state").ifBlank { row.text("acceptance_state").ifBlank { row.text("status") } }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
