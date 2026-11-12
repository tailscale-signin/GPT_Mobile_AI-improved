package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Bounded facts from the captured source, independent of display-page traversal. */
internal object GitHubSemanticSummary {
    private val sha = Regex("[a-fA-F0-9]{40}")

    fun from(operation: String, request: JsonObject, source: JsonObject): JsonObject {
        val data = data(source)
        val action = GitHubActionIdentity.canonicalArguments(operation, request).text("action")
        val repository = data["repository"] as? JsonObject
        val branch = data["branch"]
        val checks = (data["check_details"] as? JsonObject) ?: (data["checks"] as? JsonObject) ?: data.takeIf { "check_runs" in it }
        val runs = (checks?.get("check_runs") as? JsonArray)?.filterIsInstance<JsonObject>()
        return buildJsonObject {
            put("schema", "github.semantic-summary.v1")
            put("operation", operation.substringBefore("__"))
            put("action", action)
            put("source_result_sha256", GitHubEvidenceBook.digest(source.toString()))
            put("hash_scope", "IMMUTABLE_SOURCE_RESULT")
            put("scope", "Captured observations only; task completion and required-check policy are independently verified.")
            listOf("commit_sha", "head_sha", "base_sha").forEach { key ->
                data.text(key).takeIf(sha::matches)?.let { put(key, it) }
            }
            ((data["head"] as? JsonObject)?.text("sha"))?.takeIf(sha::matches)?.let { put("head_sha", it) }
            val branchName = if (branch is JsonObject) branch.text("name") else (branch as? JsonPrimitive)?.contentOrNull.orEmpty()
            branchName.takeIf { it.isNotBlank() }?.let { put("branch", it.take(160)) }
            (data.text("default_branch").ifBlank { repository?.text("default_branch").orEmpty() }).takeIf { it.isNotBlank() }?.let { put("default_branch", it.take(160)) }
            if (operation.substringBefore("__") == "github_context" && action in setOf("", "status")) {
                listOf("open_issue_count", "open_pull_request_count").forEach { key ->
                    (data[key] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }?.let { put(key, it) }
                }
                (data["latest_workflow"] as? JsonObject)?.let { run ->
                    put(
                        "latest_workflow",
                        buildJsonObject {
                            listOf("id", "name", "status", "conclusion", "head_branch", "head_sha").forEach { key ->
                                run.text(key).takeIf { it.isNotBlank() && it != "null" }?.let { put(key, it.take(160)) }
                            }
                        }
                    )
                }
            }
            if (operation.substringBefore("__") == "github_ci" && action == "workflows") {
                val progress = data["pagination_progress"] as? JsonObject
                val rows = (data["workflows"] ?: data["items"]) as? JsonArray
                val observed = (progress?.get("unique_records_observed") as? JsonPrimitive)?.intOrNull ?: rows?.size
                observed?.let { put("workflow_definitions_observed", it) }
                (data["total_count"] as? JsonPrimitive)?.intOrNull?.let { put("workflow_definitions_total", it) }
                put("workflow_inventory_complete", progress?.get("complete") == JsonPrimitive(true) || data["complete"] == JsonPrimitive(true))
                put("workflow_execution_verified", false)
            }
            if (runs != null) {
                put("observed_checks", runs.size)
                put("check_inventory_complete", (checks["total_count"] as? JsonPrimitive)?.intOrNull == runs.size && data["has_more"] != JsonPrimitive(true) && checks["has_more"] != JsonPrimitive(true))
                put(
                    "check_counts",
                    buildJsonObject {
                        put("successful", runs.count { it.text("status") == "completed" && it.text("conclusion") == "success" })
                        put("skipped", runs.count { it.text("status") == "completed" && it.text("conclusion") == "skipped" })
                        put("in_progress", runs.count { it.text("status") != "completed" })
                        put("failed", runs.count { it.text("conclusion") in setOf("failure", "timed_out", "cancelled", "action_required", "startup_failure", "stale") })
                        put("other_completed", runs.count { it.text("status") == "completed" && it.text("conclusion") !in setOf("success", "skipped", "failure", "timed_out", "cancelled", "action_required", "startup_failure", "stale") })
                    }
                )
                put("pending_check_names", JsonArray(runs.filter { it.text("status") != "completed" }.take(4).map { JsonPrimitive(it.text("name").take(160)) }))
                data.text("state").takeIf { it.isNotBlank() }?.let { put("observed_check_state", it.take(40)) }
            }
            if (operation.substringBefore("__") == "github_pr" && action == "list") {
                val items = (data["items"] ?: data["pull_requests"]) as? JsonArray
                if (items != null && request.text("state") in setOf("", "open")) {
                    put("open_pull_requests_observed", items.size)
                    put("open_pull_request_inventory_complete", data["has_more"] == JsonPrimitive(false) && ((data["page"] as? JsonPrimitive)?.intOrNull ?: 1) == 1 && data["complete"] != JsonPrimitive(false))
                }
            }
            if (action == "changes") {
                val base = data.text("base_sha").ifBlank { request.text("base_sha") }
                val head = data.text("head_sha").ifBlank { data.text("commit_sha").ifBlank { request.text("commit_sha") } }
                if (sha.matches(base) && sha.matches(head)) {
                    put("base_sha", base)
                    put("head_sha", head)
                    put("comparison_observed", true)
                    ((data["files"] ?: data["changed_files"]) as? JsonArray)?.let { put("changed_files_observed", it.size) }
                    put("source_review_verified", false)
                }
            }
            (data["policy"] as? JsonObject)?.let { policy ->
                policy["required_checks_state"]?.let { put("required_checks_state", it) }
            }
            (data["protection"] as? JsonObject)?.takeIf { it["available"] == JsonPrimitive(false) }?.let { put("required_checks_state", "UNKNOWN") }
        }
    }

    internal fun data(source: JsonObject): JsonObject = (source["data"] as? JsonObject)?.let { nested ->
        if (source["schema"] is JsonPrimitive || source.keys.all { it in setOf("data", "execution", "success", "error") }) nested else source
    } ?: source

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
