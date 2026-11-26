package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Bounded commit history is already answer-ready evidence; it needs no prose model or raw signatures. */
internal object GitHubLookupReport {
    fun render(task: String, receipts: Map<String, JsonObject>, now: Long, epoch: Long, repository: String?, branch: String?): Pair<String, JsonObject>? {
        val request = GitHubLookupRoute.request(task)?.takeIf { it.route == GitHubLookupRoute.COMMITS } ?: return null
        if (repository.isNullOrBlank() || branch.isNullOrBlank()) return null
        val wanted = request.requestedCount ?: request.pageSize ?: 30
        if (wanted !in 1..100) return null
        val candidate = GitHubStatusEvidence.selected(receipts, repository).entries.lastOrNull { (_, row) ->
            val arguments = row["arguments"] as? JsonObject ?: return@lastOrNull false
            row["trusted"] == JsonPrimitive(true) &&
                row["success"] == JsonPrimitive(true) &&
                row["verification_eligible"] != JsonPrimitive(false) &&
                row["epoch"] == JsonPrimitive(epoch) &&
                row["branch"] == JsonPrimitive(branch) &&
                row.text("tool").githubOperationFamily() == "github_context" &&
                arguments["action"] == JsonPrimitive("commits") &&
                arguments.keys.all { it in setOf("action", "owner", "repo", "ref", "commit_sha", "page", "per_page", "response_detail", "verify_live") } &&
                (arguments["page"] ?: JsonPrimitive(1)) == JsonPrimitive(1) &&
                (row["observed_at_ms"] as? JsonPrimitive)?.longOrNull?.let { it in 1..now } == true
        } ?: return null
        val receipt = candidate.value
        val data = GitHubInventoryObservation.planningRoot(receipt) ?: return null
        val inventory = GitHubInventoryObservation.of(receipt) ?: return null
        if (inventory["record_keys_complete"] != JsonPrimitive(true) || inventory["pagination_inconsistent"] == JsonPrimitive(true) || inventory["upstream_changed_during_traversal"] == JsonPrimitive(true)) return null
        val head = GitHubInventoryQuery.commitSha(data["commit_sha"] ?: receipt["source_head_sha"]) ?: return null
        val arguments = receipt["arguments"] as JsonObject
        if (listOfNotNull(arguments["commit_sha"], GitHubInventoryQuery.commitSha(arguments["ref"]), receipt["source_head_sha"]).any { GitHubInventoryQuery.commitSha(it) != head }) return null
        val field = inventory.text("field")
        if (field !in setOf("items", "commits", "recent_commits")) return null
        val rows = (data[field] as? JsonArray)?.filterIsInstance<JsonObject>() ?: return null
        if ((inventory["fetched"] as? JsonPrimitive)?.intOrNull != rows.size || rows.any { GitHubInventoryQuery.commitSha(it["sha"]) == null || it["commit"] !is JsonObject }) return null
        val ended = (inventory["has_more"] ?: data["has_more"]) == JsonPrimitive(false)
        if (request.exhaustive && !ended || rows.size < wanted && !ended) return null
        val shown = if (request.exhaustive) rows else rows.take(wanted)
        val ids = JsonArray(listOf(JsonPrimitive(candidate.key)))
        val requirements = JsonArray(
            GitHubRequestCompletion.requirements(task).map { requirement ->
                buildJsonObject {
                    put("id", GitHubRequestCompletion.id(requirement))
                    put("requirement", requirement)
                    put("status", "PASSED")
                    put("acceptance_state", "PASS")
                    put("evidence_receipt_ids", ids)
                }
            }
        )
        val text = buildString {
            append("# Recent commits\n\n")
            append("Showing ").append(shown.size).append(" commits from `").append(head.content).append("`.\n\n")
            if (shown.isEmpty()) {
                append("The captured first page contains no commits.\n")
            } else {
                append("| Commit | Subject | Author | Date |\n| --- | --- | --- | --- |\n")
                shown.forEach { row ->
                    val commit = row.getValue("commit") as JsonObject
                    val author = commit["author"] as? JsonObject
                    val subject = (commit.text("subject").ifBlank { commit.text("message") }).lineSequence().firstOrNull().orEmpty()
                    val sha = row.text("sha")
                    val url = row.text("html_url").takeIf { value ->
                        runCatching { java.net.URI(value).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && value.length <= 2048 } }.getOrDefault(false)
                    }
                    val reference = url?.let { "[${sha.take(12)}](${it.replace("(", "%28").replace(")", "%29")})" } ?: "`${sha.take(12)}`"
                    append("| ").append(reference).append(" | ").append(cell(subject)).append(" | ").append(cell(author?.text("name").orEmpty())).append(" | ").append(cell(author?.text("date").orEmpty())).append(" |\n")
                }
            }
            if (!ended || shown.size < rows.size) append("\nThis is the requested bounded history; earlier commits remain available.\n")
        }
        return text to buildJsonObject {
            put("schema", "github.lookup-report.v1")
            put("task_outcome", "COMPLETED")
            put("completion_verified", true)
            put("answer_verified", true)
            put("claims_verified", true)
            put("partial", false)
            put("resume_required", false)
            put("head_sha", head)
            put("returned_count", shown.size)
            put("full_history_verified", ended && shown.size == rows.size)
            put("completion_scope", if (request.exhaustive) "COMPLETE_CAPTURED_COMMIT_HISTORY" else "REQUESTED_BOUNDED_COMMIT_HISTORY")
            put("requirements", requirements)
            put("remaining_requirements", JsonArray(emptyList()))
            put("evidence_receipt_ids", ids)
            put("observed_at_ms", receipt.getValue("observed_at_ms"))
            put("verified_at_ms", now)
        }
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun cell(value: String): String = value.take(240).replace(Regex("[\\r\\n\\t]"), " ").replace("\\", "\\\\").replace("|", "\\|").replace("`", "\\`").replace("[", "\\[").replace("]", "\\]").ifBlank { "—" }
}
