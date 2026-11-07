package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Planning observations, not proof of new execution. A newer attempt supersedes an old result. */
internal object GitHubObservedWorkflowRuns {
    fun current(receipts: Collection<JsonObject>, commitSha: JsonPrimitive?): List<JsonObject> {
        if (commitSha == null || !commitSha.content.matches(Regex("[a-fA-F0-9]{40}"))) return emptyList()
        data class Observation(val run: JsonObject, val order: Int)
        val candidates = receipts.flatMapIndexed { index, receipt ->
            if (receipt["trusted"] != JsonPrimitive(true) || receipt["success"] != JsonPrimitive(true) || receipt["verification_eligible"] == JsonPrimitive(false) || receipt.text("tool").substringBefore("__") != "github_ci") return@flatMapIndexed emptyList()
            val arguments = receipt["arguments"] as? JsonObject ?: return@flatMapIndexed emptyList()
            val action = arguments.text("action")
            val envelope = receipt["result"] as? JsonObject ?: return@flatMapIndexed emptyList()
            val root = (envelope["data"] as? JsonObject) ?: envelope
            val runs = when (action) {
                "runs" -> (root["workflow_runs"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                "run", "status" -> if (arguments["run_id"] == root["id"] && "run_id" in arguments && "job_id" !in arguments) listOf(root) else emptyList()
                else -> emptyList()
            }
            runs.filter { (it["id"] as? JsonPrimitive)?.longOrNull?.let { id -> id > 0 } == true }
                .map { Observation(it, index) }
        }
        // Select the latest observation before checking its revision. A current
        // mismatched or incomplete record cannot resurrect an older green/failed run.
        return candidates.groupBy { it.run["id"] }.values.map { versions ->
            // An attempt-less newer readback is not permission to reuse a stale
            // terminal observation. A positively identified newer attempt still
            // outranks a late response from an older, identified attempt.
            val latest = versions.maxBy { it.order }
            if ((latest.run["run_attempt"] as? JsonPrimitive)?.longOrNull == null) {
                latest
            } else {
                versions.maxWith(compareBy<Observation> { (it.run["run_attempt"] as? JsonPrimitive)?.longOrNull ?: 0L }.thenBy { it.order })
            }
        }.sortedByDescending { it.order }.map { it.run }.filter { it["head_sha"] == commitSha }
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
