package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A successful jobs query can still leave job/log dependencies unresolved. */
internal object GitHubSelfTestWorkflowJobs {
    private const val MAX_DEPENDENCY_PAGES = 8
    data class Selection(val job: JsonObject?, val nextRun: JsonObject?, val runs: List<JsonObject> = emptyList())

    fun select(receipts: List<JsonObject>, commitSha: JsonPrimitive?): Selection {
        val trusted = receipts.filter {
            it["trusted"] == JsonPrimitive(true) &&
                it["verification_eligible"] != JsonPrimitive(false) &&
                it["repository_role"] != JsonPrimitive("READ_SOURCE")
        }
        val source = trusted.lastOrNull {
            it["success"] == JsonPrimitive(true) &&
                it.text("tool").substringBefore("__") == "github_context" &&
                (it["arguments"] as? JsonObject)?.text("action") == "status" &&
                GitHubInventoryObservation.planningRoot(it)?.get("commit_sha") == commitSha
        }
        if (source == null && (commitSha != null || trusted.any { it["binding"] != null || it["repository"] != null })) {
            return Selection(null, null)
        }
        // A matching SHA does not confer another task/repository's authority.
        val observations = trusted.filter {
            source == null || it["binding"] == source["binding"] && it["repository"] == source["repository"]
        }
        val runs = GitHubObservedWorkflowRuns.current(observations, commitSha).sortedBy {
            when {
                it.text("status") == "completed" && executed(it) -> 0
                it.text("status") != "completed" -> 1
                else -> 2
            }
        }
        val byRun = runs.associateBy { it["id"] }
        val jobReads = observations.filter {
            it.text("tool").substringBefore("__") == "github_ci" &&
                (it["arguments"] as? JsonObject)?.text("action") == "jobs"
        }
        val logAttempts = observations.filter {
            it.text("tool").substringBefore("__") == "github_ci" &&
                (it["arguments"] as? JsonObject)?.text("action") == "logs"
        }
        val jobs = jobReads.filter { it["success"] == JsonPrimitive(true) }.flatMap { receipt ->
            val args = receipt["arguments"] as? JsonObject ?: return@flatMap emptyList()
            val root = GitHubInventoryObservation.planningRoot(receipt) ?: return@flatMap emptyList()
            (root["jobs"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter { job ->
                val runId = job["run_id"]
                val run = byRun[runId]
                val attempt = job["run_attempt"] ?: root["run_attempt"] ?: args["run_attempt"]
                val runAttempt = run?.get("run_attempt")
                (job["id"] as? JsonPrimitive)?.longOrNull?.let { it > 0 } == true &&
                    (args["run_id"] == null || args["run_id"] == runId) &&
                    (commitSha == null || run != null && (job["head_sha"] == null || job["head_sha"] == commitSha)) &&
                    (commitSha == null || args["expected_head_sha"] == null || args["expected_head_sha"] == commitSha) &&
                    (runAttempt == null || attempt == runAttempt)
            }
        }.associateBy { it["run_id"] to it["id"] }.values
        val available = jobs.filter { candidate ->
            candidate.text("status") == "completed" &&
                executed(candidate) &&
                (candidate["steps"] !is JsonArray || (candidate["steps"] as JsonArray).filterIsInstance<JsonObject>().any { it.text("status") == "completed" && executed(it) }) &&
                logAttempts.none { (it["arguments"] as? JsonObject)?.get("job_id") == candidate["id"] }
        }
        val job = available.firstOrNull { it["steps"] is JsonArray } ?: available.firstOrNull()
        val next = if (job != null || jobReads.any { it["success"] != JsonPrimitive(true) }) {
            null
        } else {
            // The caller retains the accepted call budget and cleanup reserve.
            // Do not strand an already-observed candidate behind a second sample cap.
            runs.firstOrNull { run ->
                // One skipped run can qualify the jobs endpoint. Further skipped
                // runs cannot supply an executed job; discover another run page.
                (jobReads.isEmpty() || run.text("status") != "completed" || executed(run)) &&
                    jobReads.none { receipt ->
                        val args = receipt["arguments"] as? JsonObject
                        args?.get("run_id") == run["id"] &&
                            (run["run_attempt"] == null || args?.get("run_attempt") == run["run_attempt"])
                    }
            }
        }
        return Selection(job, next, runs)
    }

    /** Follow a real selected-revision continuation to satisfy a missing job/log dependency. */
    fun dependencyPage(recipe: JsonObject, tools: List<AgentToolDefinition>, receipts: List<JsonObject>, issuer: JsonObject): Boolean {
        val name = recipe.text("tool")
        val args = recipe["arguments"] as? JsonObject ?: return false
        if (name.substringBefore("__") != "github_ci" || args.text("action") != "runs" || "view_cursor" in args || ((args["page"] as? JsonPrimitive)?.longOrNull ?: 0L) < 2L) return false
        val source = receipts.lastOrNull {
            it["trusted"] == JsonPrimitive(true) &&
                it["success"] == JsonPrimitive(true) &&
                it["verification_eligible"] != JsonPrimitive(false) &&
                it["repository_role"] != JsonPrimitive("READ_SOURCE") &&
                it.text("tool").substringBefore("__") == "github_context" &&
                (it["arguments"] as? JsonObject)?.text("action") == "status"
        } ?: return false
        if (issuer["trusted"] != JsonPrimitive(true) || issuer["success"] != JsonPrimitive(true) || issuer["verification_eligible"] == JsonPrimitive(false) || issuer["repository_role"] == JsonPrimitive("READ_SOURCE") || issuer["binding"] != source["binding"] || issuer["repository"] != source["repository"]) return false
        val sha = (GitHubInventoryObservation.planningRoot(source)?.get("commit_sha") as? JsonPrimitive)
            ?.takeIf { it.content.matches(Regex("[a-fA-F0-9]{40}")) } ?: return false
        if (args["commit_sha"] != sha) return false
        if (("owner" in args || "repo" in args) && "${args.text("owner")}/${args.text("repo")}" != source.text("repository")) return false
        val observations = receipts.filter {
            it["trusted"] == JsonPrimitive(true) &&
                it["verification_eligible"] != JsonPrimitive(false) &&
                it["repository_role"] != JsonPrimitive("READ_SOURCE") &&
                it["binding"] == source["binding"] &&
                it["repository"] == source["repository"]
        }
        val tool = tools.singleOrNull { it.modelVisible && it.name == name } ?: return false
        val actionSchema = (tool.inputSchema["properties"] as? JsonObject)?.get("action") as? JsonObject ?: return false
        val actions = (actionSchema["enum"] as? JsonArray).orEmpty() + listOfNotNull(actionSchema["const"])
        val missing = actions.any { action ->
            action in setOf(JsonPrimitive("job"), JsonPrimitive("logs")) &&
                observations.none {
                    it.text("tool") == name && (it["arguments"] as? JsonObject)?.get("action") == action
                }
        }
        if (!missing ||
            observations.any {
                it.text("tool") == name &&
                    it["success"] != JsonPrimitive(true) &&
                    (it["arguments"] as? JsonObject)?.text("action") in setOf("runs", "jobs", "job", "logs")
            }
        ) {
            return false
        }
        val selection = select(observations, sha)
        if (selection.job != null || selection.nextRun != null) return false
        val pages = observations.count {
            val prior = it["arguments"] as? JsonObject
            it.text("tool") == name &&
                prior?.text("action") == "runs" &&
                prior["commit_sha"] == sha &&
                ((prior["page"] as? JsonPrimitive)?.longOrNull ?: 1L) > 1
        }
        return pages < MAX_DEPENDENCY_PAGES
    }

    private fun executed(row: JsonObject): Boolean = row.text("conclusion") in setOf("success", "failure", "timed_out", "action_required")
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
