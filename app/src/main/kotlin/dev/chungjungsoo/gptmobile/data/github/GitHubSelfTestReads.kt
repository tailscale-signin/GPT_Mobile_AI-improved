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
    fun next(tools: List<AgentToolDefinition>, receipts: List<JsonObject>): ProviderEvent.ToolCall? = available(tools, receipts).firstOrNull()

    /** Compute dependency recipes once per checklist, rather than once per capability row. */
    fun available(tools: List<AgentToolDefinition>, receipts: List<JsonObject>): List<ProviderEvent.ToolCall> {
        val ready = mutableListOf<ProviderEvent.ToolCall>()
        val retiredRead = GitHubSelfTestFixtures.retiredReadPredicate(receipts.mapIndexed { index, receipt -> index.toString() to receipt }.toMap())
        // Supplemental source repositories cannot supply identifiers or completed
        // reads for smoke tests executed in the selected workspace.
        val observations = receipts.filter {
            it["trusted"] == JsonPrimitive(true) &&
                it["verification_eligible"] != JsonPrimitive(false) &&
                it["repository_role"] != JsonPrimitive("READ_SOURCE") &&
                !retiredRead(it)
        }
        GitHubPolicyReadRecovery.next(tools, observations)?.let { ready += it }
        ready += GitHubSelfTestGraph.available(tools, observations)
        // Old receipts may retain a count and only the first inventory row. Reacquire
        // their exact read once under today's authorization; never synthesize the lost rows.
        observations.forEachIndexed { index, receipt ->
            if (receipt["success"] != JsonPrimitive(true) || (receipt["result"] as? JsonObject)?.get("observationTruncated") != JsonPrimitive(true)) return@forEachIndexed
            val args = receipt["arguments"] as? JsonObject ?: return@forEachIndexed
            val name = receipt.text("tool")
            if (args.text("action") !in setOf("runs", "workflows", "jobs", "commits", "list", "tags", "branches") || "view_cursor" in args || !dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isReplayableRead(name, args)) return@forEachIndexed
            val id = "inventory-source-" + GitHubEvidenceBook.digest("${receipt.text("binding")}|${receipt.text("receipt_id")}|$name|$args").take(24)
            if (observations.any { it.text("call_id") == id || it.text("call_id").startsWith("$id-") } ||
                observations.drop(index + 1).any { it.text("tool") == name && (it["arguments"] == args || GitHubActionIdentity.matches(name, args, it)) }
            ) {
                return@forEachIndexed
            }
            val tool = tools.firstOrNull { it.modelVisible && it.name == name && ToolArgumentValidator.errors(args, it.inputSchema).isEmpty() } ?: return@forEachIndexed
            ready += ProviderEvent.ToolCall(id, tool.name, args)
        }
        fun roots(tool: String, action: String? = null) = observations.filter {
            it["success"] == JsonPrimitive(true) &&
                it.text("tool").substringBefore("__") == tool &&
                (action == null || (it["arguments"] as? JsonObject)?.text("action") == action)
        }.mapNotNull(GitHubInventoryObservation::planningRoot).asReversed()
        fun records(tool: String, action: String, vararg fields: String) = roots(tool, action).flatMap { root ->
            fields.firstNotNullOfOrNull { root[it] as? JsonArray }.orEmpty().filterIsInstance<JsonObject>()
        }
        val status = roots("github_context", "status").firstOrNull()
        val sha = (status?.get("commit_sha") as? JsonPrimitive)?.takeIf { it.content.matches(Regex("[a-fA-F0-9]{40}")) }
        val branch = status?.get("branch") ?: status?.get("ref")
        val workflowJobs = GitHubSelfTestWorkflowJobs.select(observations, sha)
        val currentRuns = workflowJobs.runs
        val run = currentRuns.firstOrNull { it.text("status") == "completed" && it.text("conclusion") in setOf("success", "failure", "timed_out", "action_required") }
            ?: currentRuns.firstOrNull { it.text("status") != "completed" }
            ?: currentRuns.firstOrNull()
        // Failure analysis has its own prerequisite. A successful run selected
        // for ordinary inspection must not hide a different observed failed run.
        val failedRun = currentRuns.firstOrNull { it.text("status") == "completed" && it.text("conclusion") in setOf("failure", "timed_out") }
        val job = workflowJobs.job
        val needsJobDependency = tools.any { tool ->
            if (!tool.modelVisible || tool.name.substringBefore("__") != "github_ci") return@any false
            val action = (tool.inputSchema["properties"] as? JsonObject)?.get("action") as? JsonObject
            val actions = (action?.get("enum") as? JsonArray).orEmpty() + listOfNotNull(action?.get("const"))
            actions.any action@{ value ->
                val name = (value as? JsonPrimitive)?.contentOrNull
                if (name !in setOf("job", "logs")) return@action false
                val prior = observations.filter { it.text("tool") == tool.name && (it["arguments"] as? JsonObject)?.text("action") == name }
                prior.isEmpty() || name == "logs" && prior.size == 1 && prior.single().text("error_code") == "GITHUB_NOT_FOUND_OR_HIDDEN"
            }
        }
        val jobsRun = if (needsJobDependency) workflowJobs.nextRun else run
        val pr = records("github_pr", "list", "pull_requests", "items").firstOrNull()
            ?: listOf("headers", "context").firstNotNullOfOrNull { action ->
                roots("github_pr", action).firstNotNullOfOrNull { root ->
                    ((root["pull_request"] as? JsonObject) ?: root).takeIf { it["number"] is JsonPrimitive }
                }
            }
        val issue = records("github_issue", "list", "items", "issues").firstOrNull { "pull_request" !in it }
        val release = records("github_release", "list", "items", "releases").firstOrNull()
            ?: roots("github_release", "status").firstNotNullOfOrNull { root ->
                ((root["release"] as? JsonObject) ?: root).takeIf { it["tag_name"] is JsonPrimitive }
            }
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
            listOf("capabilities", "status").forEach { action ->
                GitHubRepositoryReadiness.read(action, tools, observations)?.arguments?.let { add("github_repository" to it) }
            }
            if (sha != null) {
                recipe("github_context", "commit", "commit_sha" to sha)
                recipe("github_context", "commit_search", "query" to JsonPrimitive("hash:${sha.content}"))
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
            (release?.get("tag_name") ?: tag?.get("name"))?.let { recipe("github_release", "detail", "tag_name" to it) }
            // Actions inventory includes deleted and generated workflows. Discover definitions
            // from the pinned source tree instead of assuming the first live ID exists there.
            if (sha != null) recipe("github_ci", "definitions", "commit_sha" to sha)
            run?.get("id")?.let { id ->
                listOf("run", "status", "artifacts").forEach { recipe("github_ci", it, "run_id" to id, "expected_head_sha" to sha) }
                if (sha != null) {
                    add(
                        "github_watch_checks" to buildJsonObject {
                            put("run_id", id)
                            put("commit_sha", sha)
                            put("wait_timeout_seconds", 1)
                        }
                    )
                }
            }
            jobsRun?.get("id")?.let { id -> recipe("github_ci", "jobs", "run_id" to id, "run_attempt" to jobsRun["run_attempt"], "expected_head_sha" to sha) }
            if (sha != null) {
                // The native failure query supports an exact revision without a run ID.
                // Its no_failed_run response exercises the query, not failure repair.
                recipe("github_ci", "failure", "run_id" to failedRun?.get("id"), "expected_head_sha" to sha)
            }
            job?.get("id")?.let { id ->
                recipe("github_ci", "job", "job_id" to id, "run_id" to job["run_id"], "run_attempt" to job["run_attempt"], "expected_head_sha" to sha)
                recipe("github_ci", "logs", "job_id" to id, "run_id" to job["run_id"], "run_attempt" to job["run_attempt"], "expected_head_sha" to sha, "start_line" to JsonPrimitive(1), "end_line" to JsonPrimitive(40))
            }
            pr?.get("number")?.let { id -> listOf("headers", "context", "comments", "reviews", "threads").forEach { recipe("github_pr", it, "pull_number" to id) } }
            issue?.get("number")?.let { id -> listOf("headers", "context", "comments", "history").forEach { recipe("github_issue", it, "issue_number" to id) } }
        }
        for ((name, recipe) in recipes) {
            val tool = tools.firstOrNull { it.modelVisible && it.name.substringBefore("__") == name && ToolArgumentValidator.errors(recipe, it.inputSchema).isEmpty() } ?: continue
            // A live-required capability should make its first admissible read
            // live. Re-reading every cached inventory page wastes the fixture
            // and verification budget without increasing capability coverage.
            val args = if (GitHubTestChecklist.requiresLiveTransport(name, recipe.text("action")) && (tool.inputSchema["properties"] as? JsonObject)?.containsKey("verify_live") == true) {
                JsonObject(recipe + ("verify_live" to JsonPrimitive(true)))
            } else {
                recipe
            }
            val prior = observations.filter { it.text("tool") == tool.name && (it["arguments"] as? JsonObject)?.text("action") == args.text("action") }
            if (prior.isNotEmpty()) {
                val additionalJobs = name == "github_ci" &&
                    args.text("action") == "jobs" &&
                    needsJobDependency &&
                    workflowJobs.nextRun?.get("id") == args["run_id"] &&
                    prior.all { it["success"] == JsonPrimitive(true) }
                if (prior.any { !additionalJobs && it["success"] == JsonPrimitive(true) || it["arguments"] == args || GitHubActionIdentity.matches(tool.name, args, it) }) continue
                // Correct legacy planner arguments once. Authorization failures never
                // unlock another attempt, and absent logs get only one alternate job.
                val correctable = when (name to args.text("action")) {
                    "github_release" to "status", "github_ci" to "definitions" -> prior.all { it.text("error_code") == "INVALID_ARGUMENTS" }
                    "github_ci" to "jobs" -> additionalJobs
                    "github_ci" to "logs" -> prior.size == 1 && prior.single().text("error_code") == "GITHUB_NOT_FOUND_OR_HIDDEN"
                    else -> false
                }
                if (!correctable) continue
            }
            if (ToolArgumentValidator.errors(args, tool.inputSchema).isNotEmpty()) continue
            ready += ProviderEvent.ToolCall("checklist-" + GitHubEvidenceBook.digest("${tool.name}:$args").take(24), tool.name, args)
        }
        return ready.sortedBy(::priority)
    }

    fun isDependency(call: ProviderEvent.ToolCall): Boolean = priority(call) <= 1

    private fun priority(call: ProviderEvent.ToolCall): Int = when {
        call.callId.startsWith("policy-read-") -> 0
        call.callId.startsWith("inventory-source-") -> 0
        call.name.substringBefore("__") == "github_issue" && call.arguments.text("action") == "list" -> 1
        call.name.substringBefore("__") == "github_release" && call.arguments.text("action") in setOf("tags", "list") -> 1
        call.name.substringBefore("__") == "github_ci" && call.arguments.text("action") == "jobs" -> 1
        call.name.substringBefore("__") == "github_ci" && call.arguments.text("action") == "definitions" -> 3
        else -> 2
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
