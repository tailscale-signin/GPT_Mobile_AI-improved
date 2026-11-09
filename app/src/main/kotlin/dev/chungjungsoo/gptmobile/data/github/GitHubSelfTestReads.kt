package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Dependency-aware smoke reads. Identifiers retain the resource type that supplied them. */
internal object GitHubSelfTestReads {
    fun next(tools: List<AgentToolDefinition>, receipts: List<JsonObject>): ProviderEvent.ToolCall? {
        val retiredRead = GitHubSelfTestFixtures.retiredReadPredicate(receipts.mapIndexed { index, receipt -> index.toString() to receipt }.toMap())
        val observations = receipts.filter { it["trusted"] == JsonPrimitive(true) && it["verification_eligible"] != JsonPrimitive(false) && !retiredRead(it) }
        fun roots(tool: String, action: String? = null) = observations.filter {
            it["success"] == JsonPrimitive(true) &&
                it.text("tool").substringBefore("__") == tool &&
                (action == null || (it["arguments"] as? JsonObject)?.text("action") == action)
        }.mapNotNull { (it["result"] as? JsonObject)?.let { root -> (root["data"] as? JsonObject) ?: root } }.asReversed()
        fun records(tool: String, action: String, vararg fields: String) = roots(tool, action).flatMap { root ->
            fields.firstNotNullOfOrNull { root[it] as? JsonArray }.orEmpty().filterIsInstance<JsonObject>()
        }
        val status = roots("github_context", "status").firstOrNull()
        val sha = (status?.get("commit_sha") as? JsonPrimitive)?.takeIf { it.content.matches(Regex("[a-fA-F0-9]{40}")) }
        val branch = status?.get("branch") ?: status?.get("ref")
        val currentRuns = GitHubObservedWorkflowRuns.current(observations, sha)
        val run = currentRuns.firstOrNull { it.text("status") == "completed" && it.text("conclusion") !in setOf("skipped", "neutral") }
            ?: currentRuns.firstOrNull()
        // Failure analysis has its own prerequisite. A successful run selected
        // for ordinary inspection must not hide a different observed failed run.
        val failedRun = currentRuns.firstOrNull { it.text("status") == "completed" && it.text("conclusion") in setOf("failure", "timed_out") }
        val runId = run?.get("id")
        val logAttempts = observations.filter { it.text("tool").substringBefore("__") == "github_ci" && (it["arguments"] as? JsonObject)?.text("action") == "logs" }
        val jobs = records("github_ci", "jobs", "jobs").filter { candidate ->
            (runId == null || candidate["run_id"] == runId) &&
                candidate.text("status") == "completed" &&
                candidate.text("conclusion") !in setOf("skipped", "neutral", "cancelled") &&
                (candidate["steps"] !is JsonArray || (candidate["steps"] as JsonArray).filterIsInstance<JsonObject>().any { it.text("status") == "completed" && it.text("conclusion") !in setOf("skipped", "neutral", "cancelled") }) &&
                logAttempts.none { (it["arguments"] as? JsonObject)?.get("job_id") == candidate["id"] }
        }
        val job = jobs.firstOrNull { it["steps"] is JsonArray } ?: jobs.firstOrNull()
        val pr = records("github_pr", "list", "pull_requests", "items").firstOrNull()
        val issue = records("github_issue", "list", "items", "issues").firstOrNull { "pull_request" !in it }
        val release = records("github_release", "list", "items", "releases").firstOrNull()
        val tag = records("github_release", "tags", "items", "tags").firstOrNull()
        val paths = records("github_search", "search", "matches", "results", "files", "items")
        val path = paths.firstNotNullOfOrNull { it["path"] as? JsonPrimitive }
        val recipes = buildList<Pair<String, JsonObject>> {
            fun recipe(tool: String, action: String, vararg fields: Pair<String, JsonElement?>) {
                add(
                    tool to buildJsonObject {
                        put("action", action)
                        if (tool in setOf("github_issue", "github_release") && action in setOf("list", "tags") && tools.any { it.name.substringBefore("__") == tool && (it.inputSchema["properties"] as? JsonObject)?.containsKey("per_page") == true }) put("per_page", 100)
                        if (tool == "github_issue" && action == "list" && tools.any { it.name.substringBefore("__") == tool && (it.inputSchema["properties"] as? JsonObject)?.containsKey("direction") == true }) {
                            put("sort", "created")
                            put("direction", "desc")
                        }
                        fields.forEach { (key, value) -> value?.let { put(key, it) } }
                    }
                )
            }
            listOf("resolve", "index", "efficiency", "capabilities", "repository", "recovery").forEach { recipe("github_context", it) }
            if (sha != null) {
                recipe("github_context", "commit", "commit_sha" to sha)
                recipe("github_context", "commit_search", "query" to sha)
                recipe("github_context", "changes", "base_sha" to sha, "commit_sha" to sha)
            }
            if (branch != null) listOf("branch", "branch_dependencies").forEach { recipe("github_context", it, "branch" to branch) }
            // The programmer workspace is pinned to one repository; account-wide discovery is outside its scope.
            recipe("github_search", "search", "query" to JsonPrimitive("README"), "mode" to JsonPrimitive("paths"), "commit_sha" to sha)
            if (path != null) {
                recipe(
                    "github_read",
                    "ranges",
                    "commit_sha" to sha,
                    "ranges" to JsonArray(
                        listOf(
                            buildJsonObject {
                                put("path", path)
                                put("start_line", 1)
                                put("end_line", 40)
                            }
                        )
                    )
                )
                // Exercise aliases explicitly on the observed path, without scanning the repository again.
                listOf("search_repository", "search_code").forEach { recipe("github_search", it, "query" to path, "mode" to JsonPrimitive("paths"), "path_suffix" to path, "commit_sha" to sha) }
            }
            recipe("github_issue", "list", "state" to JsonPrimitive("all"))
            listOf("tags", "list").forEach { recipe("github_release", it) }
            (release?.get("tag_name") ?: tag?.get("name"))?.let { recipe("github_release", "status", "tag_name" to it) }
            tag?.get("name")?.let { recipe("github_release", "tag", "tag_name" to it) }
            release?.get("tag_name")?.let { recipe("github_release", "detail", "tag_name" to it) }
            // Actions inventory includes deleted and generated workflows. Discover definitions
            // from the pinned source tree instead of assuming the first live ID exists there.
            if (sha != null) recipe("github_ci", "definitions", "commit_sha" to sha)
            run?.get("id")?.let { id ->
                listOf("run", "status", "jobs", "artifacts").forEach { recipe("github_ci", it, "run_id" to id, "expected_head_sha" to sha) }
            }
            failedRun?.get("id")?.let { id -> recipe("github_ci", "failure", "run_id" to id, "expected_head_sha" to sha) }
            job?.get("id")?.let { id ->
                recipe("github_ci", "job", "job_id" to id, "run_id" to job["run_id"], "expected_head_sha" to sha?.takeIf { runId != null && job["run_id"] == runId })
                recipe("github_ci", "logs", "job_id" to id, "run_id" to job["run_id"], "expected_head_sha" to sha?.takeIf { runId != null && job["run_id"] == runId }, "start_line" to JsonPrimitive(1), "end_line" to JsonPrimitive(40))
            }
            pr?.get("number")?.let { id -> listOf("headers", "context", "comments", "reviews").forEach { recipe("github_pr", it, "pull_number" to id) } }
            issue?.get("number")?.let { id -> listOf("headers", "context", "comments", "history").forEach { recipe("github_issue", it, "issue_number" to id) } }
        }
        for ((name, args) in recipes) {
            val tool = tools.firstOrNull { it.modelVisible && it.name.substringBefore("__") == name } ?: continue
            val prior = observations.filter { it.text("tool") == tool.name && (it["arguments"] as? JsonObject)?.text("action") == args.text("action") }
            if (prior.isNotEmpty()) {
                if (prior.any { it["success"] == JsonPrimitive(true) || it["arguments"] == args || GitHubActionIdentity.matches(tool.name, args, it) }) continue
                // Correct legacy planner arguments once. Authorization failures never
                // unlock another attempt, and absent logs get only one alternate job.
                val correctable = when (name to args.text("action")) {
                    "github_release" to "status", "github_ci" to "definitions" -> prior.all { it.text("error_code") == "INVALID_ARGUMENTS" }
                    "github_ci" to "logs" -> prior.size == 1 && prior.single().text("error_code") == "GITHUB_NOT_FOUND_OR_HIDDEN"
                    else -> false
                }
                if (!correctable) continue
            }
            if (ToolArgumentValidator.errors(args, tool.inputSchema).isNotEmpty()) continue
            return ProviderEvent.ToolCall("checklist-" + GitHubEvidenceBook.digest("${tool.name}:$args").take(24), tool.name, args)
        }
        return null
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
