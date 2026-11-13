package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A successful jobs query can still leave job/log dependencies unresolved. */
internal object GitHubSelfTestWorkflowJobs {
    data class Selection(val job: JsonObject?, val nextRun: JsonObject?, val runs: List<JsonObject> = emptyList())

    fun select(receipts: List<JsonObject>, commitSha: JsonPrimitive?): Selection {
        val trusted = receipts.filter {
            it["trusted"] == JsonPrimitive(true) && it["verification_eligible"] != JsonPrimitive(false)
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
                jobReads.none { receipt ->
                    val args = receipt["arguments"] as? JsonObject
                    args?.get("run_id") == run["id"] &&
                        (run["run_attempt"] == null || args?.get("run_attempt") == run["run_attempt"])
                }
            }
        }
        return Selection(job, next, runs)
    }

    private fun executed(row: JsonObject): Boolean = row.text("conclusion") in setOf("success", "failure", "timed_out", "action_required")
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
