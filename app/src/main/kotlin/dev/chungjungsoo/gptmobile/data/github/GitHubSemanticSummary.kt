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
    private const val MAX_SUMMARY_CHARACTERS = 2400

    fun from(operation: String, request: JsonObject, source: JsonObject): JsonObject {
        val data = data(source)
        val canonicalRequest = GitHubActionIdentity.canonicalArguments(operation, request)
        val action = canonicalRequest.text("action")
        val repository = data["repository"] as? JsonObject
        val branch = data["branch"]
        val checks = (data["check_details"] as? JsonObject) ?: (data["checks"] as? JsonObject) ?: data.takeIf { "check_runs" in it }
        val runs = (checks?.get("check_runs") as? JsonArray)?.filterIsInstance<JsonObject>()
        return bounded(
            buildJsonObject {
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
                if (operation.githubOperationFamily() == "github_read" && sha.matches(data.text("commit_sha"))) {
                    val item = (data["items"] as? JsonArray)?.firstOrNull() as? JsonObject
                    val start = (item?.get("start_line") as? JsonPrimitive)?.intOrNull
                    val end = (item?.get("end_line") as? JsonPrimitive)?.intOrNull
                    if (item != null && item.text("path").isNotBlank() && start != null && start > 0 && end != null && end >= start) {
                        put(
                            "source_range",
                            buildJsonObject {
                                put("path", item.text("path").take(256))
                                put("start", start)
                                put("end", end)
                                put("complete", item["range_complete"] == JsonPrimitive(true))
                            }
                        )
                    }
                }
                if (operation.githubOperationFamily() == "github_context" && action in setOf("", "status")) {
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
                if (operation.githubOperationFamily() == "github_context" && action == "branches") {
                    val rows = ((data["branches"] ?: data["items"]) as? JsonArray).orEmpty()
                    val branches = rows.take(4).filterIsInstance<JsonObject>().mapNotNull { row ->
                        val name = row.text("name").takeIf { it.isNotBlank() && it.length <= 256 } ?: return@mapNotNull null
                        buildJsonObject {
                            put("name", name)
                            ((row["commit"] as? JsonObject)?.text("sha") ?: row.text("sha")).takeIf(sha::matches)?.let { put("sha", it) }
                        }
                    }
                    if (branches.isNotEmpty()) put("branch_observations", JsonArray(branches))
                    put("branches_observed", rows.size)
                    put("branch_preview_complete", branches.size == rows.size)
                    put("branch_inventory_complete", data["has_more"] == JsonPrimitive(false) && ((data["page"] as? JsonPrimitive)?.intOrNull ?: 1) == 1)
                }
                if (operation.githubOperationFamily() == "github_ci" && action == "workflows") {
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
                    val total = (checks["total_count"] as? JsonPrimitive)?.intOrNull ?: (data["checks"] as? JsonPrimitive)?.intOrNull
                    total?.let { put("reported_total_checks", it) }
                    (data["page"] as? JsonPrimitive)?.intOrNull?.let { put("remote_page", it) }
                    put("check_inventory_complete", total == runs.size && ((data["page"] as? JsonPrimitive)?.intOrNull ?: 1) == 1 && data["has_more"] != JsonPrimitive(true) && checks["has_more"] != JsonPrimitive(true))
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
                (data["status_details"] as? JsonObject)?.let { statuses ->
                    (statuses["statuses"] as? JsonArray)?.let { put("observed_statuses", it.size) }
                    (statuses["total_count"] as? JsonPrimitive)?.intOrNull?.let { put("reported_total_statuses", it) }
                }
                if (operation.githubOperationFamily() == "github_pr" && action == "list") {
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
                val prefix = if (data === source) "" else "/data"
                val family = operation.githubOperationFamily()
                if (family in setOf("github_issue", "github_pr")) {
                    val collection = listOf("items", "issues", "pull_requests").firstOrNull { data[it] is JsonArray }
                    val rows = collection?.let { data[it] as JsonArray } ?: listOf(data)
                    val observations = rows.take(4).mapIndexedNotNull { index, raw ->
                        val row = raw as? JsonObject ?: return@mapIndexedNotNull null
                        val number = (row["number"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: return@mapIndexedNotNull null
                        buildJsonObject {
                            put("resource", if (family == "github_issue") "issue" else "pull_request")
                            put("number", number)
                            put("pointer", if (collection == null) prefix else "$prefix/$collection/$index")
                            listOf("title", "state", "state_reason").forEach { key -> row.text(key).takeIf(String::isNotBlank)?.let { put(key, it.take(180)) } }
                            row.text("body").takeIf(String::isNotBlank)?.let { body ->
                                // A bounded excerpt retains the reported symptom, never a model diagnosis.
                                put("reported_excerpt", body.take(320))
                                put("excerpt_complete", body.length <= 320)
                            }
                        }
                    }
                    if (observations.isNotEmpty()) {
                        put("resource_observations", JsonArray(observations))
                        put("resources_observed", rows.size)
                        put("resource_inventory_complete", collection == null || data["complete"] == JsonPrimitive(true) || data["has_more"] == JsonPrimitive(false) && ((data["page"] as? JsonPrimitive)?.intOrNull ?: 1) == 1)
                    }
                }
                if (family in setOf("github_read", "github_search") && sha.matches(data.text("commit_sha"))) {
                    val rows = (data["items"] as? JsonArray).orEmpty()
                    val excerpts = rows.take(3).mapIndexedNotNull { index, raw ->
                        val row = raw as? JsonObject ?: return@mapIndexedNotNull null
                        val textKey = listOf("content", "text").firstOrNull { row.text(it).isNotBlank() } ?: return@mapIndexedNotNull null
                        val text = row.text(textKey)
                        if (row.text("path").isBlank()) return@mapIndexedNotNull null
                        buildJsonObject {
                            put("path", row.text("path").take(256))
                            put("pointer", "$prefix/items/$index/$textKey")
                            listOf("start_line", "end_line", "line").forEach { key -> (row[key] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }?.let { put(key, it) } }
                            put("excerpt", text.take(480))
                            put("excerpt_complete", text.length <= 480)
                        }
                    }
                    if (excerpts.isNotEmpty()) put("source_excerpts", JsonArray(excerpts))
                }
                if (family == "github_search") {
                    put(
                        "search_identity",
                        buildJsonObject {
                            for (key in listOf("query", "mode", "path_prefix", "file_extension", "path_suffix", "case_sensitive", "fuzzy_match", "fuzzy_threshold", "min_lines", "max_lines")) {
                                val value = data[key] ?: canonicalRequest[key] ?: continue
                                if (value is JsonPrimitive && value.toString().length <= 512) put(key, value)
                            }
                        }
                    )
                    put("search_complete", data["matches_complete"] == JsonPrimitive(true))
                    listOf("scanned_files", "returned", "interval_start", "interval_end", "total_candidate_files", "stop_reason", "total_deferred_files", "no_progress_attempts").forEach { key -> data[key]?.let { put(key, it) } }
                    (data["deferred_files"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { deferred ->
                        put(
                            "deferred_files",
                            JsonArray(
                                deferred.take(3).filterIsInstance<JsonObject>().map { row ->
                                    buildJsonObject {
                                        listOf("path", "sha", "reason", "attempts").forEach { key -> row[key]?.let { put(key, it) } }
                                    }
                                }
                            )
                        )
                        put("deferred_file_preview_complete", deferred.size <= 3)
                    }
                    val matches = (data["items"] as? JsonArray).orEmpty().take(3).mapIndexedNotNull { index, raw ->
                        val row = raw as? JsonObject ?: return@mapIndexedNotNull null
                        val match = (row["matches"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@mapIndexedNotNull null
                        if (row.text("path").isBlank() || match.text("text").isBlank()) return@mapIndexedNotNull null
                        buildJsonObject {
                            put("path", row.text("path").take(256))
                            put("pointer", "$prefix/items/$index/matches/0/text")
                            match["line"]?.let { put("line", it) }
                            put("excerpt", match.text("text").take(320))
                            put("excerpt_complete", match.text("text").length <= 320)
                        }
                    }
                    if (matches.isNotEmpty()) put("search_observations", JsonArray(matches))
                    if (sha.matches(data.text("commit_sha"))) {
                        val paths = (data["items"] as? JsonArray).orEmpty().mapIndexedNotNull { index, raw ->
                            val row = raw as? JsonObject ?: return@mapIndexedNotNull null
                            val path = row.text("path").takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
                            buildJsonObject {
                                put("path", path.take(384))
                                put("path_complete", path.length <= 384)
                                row.text("sha").takeIf(sha::matches)?.let { put("sha", it) }
                                put("pointer", "$prefix/items/$index")
                            }
                        }
                        if (paths.isNotEmpty()) {
                            put("path_observations", JsonArray(paths.take(8)))
                            put("paths_observed", paths.size)
                            put("path_preview_complete", paths.size <= 8)
                            put("source_read_verified", false)
                        }
                    }
                }
            }
        )
    }

    /** Keep useful observations when an older summary exceeds the replay allowance. */
    internal fun bounded(summary: JsonObject, maxCharacters: Int = MAX_SUMMARY_CHARACTERS): JsonObject {
        if (summary.toString().length <= maxCharacters) return summary
        val retained = summary.toMutableMap()
        for (key in listOf("resource_observations", "source_excerpts", "search_observations", "path_observations", "branch_observations", "deferred_files", "pending_check_names")) {
            val rows = (retained[key] as? JsonArray)?.toMutableList() ?: continue
            while (rows.size > 1 && JsonObject(retained).toString().length > maxCharacters) {
                rows.removeAt(rows.lastIndex)
                retained[key] = JsonArray(rows)
                retained["summary_truncated"] = JsonPrimitive(true)
                if (key == "path_observations") retained["path_preview_complete"] = JsonPrimitive(false)
                if (key == "branch_observations") retained["branch_preview_complete"] = JsonPrimitive(false)
            }
        }
        if (JsonObject(retained).toString().length <= maxCharacters) return JsonObject(retained)
        // Legacy or malformed summaries cannot consume an unbounded replay. Keep
        // identity and the first useful observation, not an all-or-nothing placeholder.
        val priority = listOf("schema", "operation", "action", "commit_sha", "head_sha", "base_sha", "source_result_sha256", "search_identity", "search_complete", "path_observations", "paths_observed", "path_preview_complete", "source_read_verified", "branch_observations", "branch_inventory_complete", "scope", "resource_observations", "source_excerpts", "source_range", "total_deferred_files", "no_progress_attempts", "deferred_files", "search_observations", "check_counts", "required_checks_state")
        val bounded = linkedMapOf<String, kotlinx.serialization.json.JsonElement>("summary_truncated" to JsonPrimitive(true))
        for (key in priority + retained.keys) {
            if (key in bounded) continue
            val original = retained[key] ?: continue
            val value = when {
                key == "path_preview_complete" && "path_observations" !in bounded -> JsonPrimitive(false)
                key == "branch_preview_complete" && "branch_observations" !in bounded -> JsonPrimitive(false)
                else -> original
            }
            if (JsonObject(bounded + (key to value)).toString().length <= maxCharacters) bounded[key] = value
        }
        return JsonObject(bounded)
    }

    internal fun data(source: JsonObject): JsonObject = (source["data"] as? JsonObject)?.let { nested ->
        if (source["schema"] is JsonPrimitive || source.keys.all { it in setOf("data", "execution", "success", "error") }) nested else source
    } ?: source

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
