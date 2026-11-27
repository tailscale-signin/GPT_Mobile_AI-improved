package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Spend the existing reserve on missing test proof for a published change, never new implementation. */
internal object GitHubVerificationReserve {
    private data class Target(val receiptId: String, val sha: String, val repository: String, val binding: String, val suffix: String)
    private data class Run(val id: Long, val attempt: Int, val completed: Boolean)

    fun next(tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>, remaining: Int, cleanupReserve: Int): ProviderEvent.ToolCall? {
        if (remaining <= cleanupReserve) return null
        val target = target(receipts) ?: return null
        val ci = tools.singleOrNull { it.modelVisible && it.name == "github_ci${target.suffix}" } ?: return null
        val scoped = receipts.values.filter { it["binding"] == JsonPrimitive(target.binding) && it.text("repository").equals(target.repository, true) }
        val ciReads = scoped.filter { it["tool"] == JsonPrimitive(ci.name) }
        val observations = ciReads.filter(::eligible).mapNotNull(GitHubInventoryObservation::planningRoot)
        val observedAttempts = observations.flatMap { root ->
            listOf(root) + (root["workflow_runs"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        }.mapNotNull { row ->
            val id = ((row["run_id"] ?: row["id"]) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
            val attempt = listOf("run_attempt", "expected_run_attempt").mapNotNull { key ->
                (row[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it > 0 }
            }.maxOrNull() ?: return@mapNotNull null
            id to attempt
        }.groupBy({ it.first }, { it.second }).mapValues { (_, attempts) -> attempts.max() }
        val runs = observations.flatMap { root ->
            val rows = (root["workflow_runs"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: listOf(root)
            rows.mapNotNull { row ->
                if (!row.text("head_sha").equals(target.sha, true)) return@mapNotNull null
                val id = ((row["run_id"] ?: row["id"]) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
                val attempt = (row["run_attempt"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
                if (row.text("status").isBlank()) return@mapNotNull null
                Run(id, attempt, row.text("status") == "completed")
            }
        }.groupBy { it.id }.map { (_, values) -> values.last { it.attempt == values.maxOf { run -> run.attempt } } }
        fun call(action: String, selectors: JsonObject): ProviderEvent.ToolCall? {
            val repository = target.repository.split('/')
            val arguments = buildJsonObject {
                put("action", action)
                put("owner", repository[0])
                put("repo", repository[1])
                put("verify_live", true)
                put("per_page", 100)
                selectors.forEach { (key, value) -> put(key, value) }
            }
            val id = "verification-reserve-" + GitHubEvidenceBook.digest("${target.binding}|${target.sha}|$arguments").take(24)
            if (scoped.any { it["call_id"] == JsonPrimitive(id) } || ToolArgumentValidator.errors(arguments, ci.inputSchema).isNotEmpty()) return null
            return ProviderEvent.ToolCall(id, ci.name, arguments)
        }
        for (run in runs.sortedBy { it.id }) {
            // An accepted rerun already supersedes its previous successful attempt,
            // even before GitHub exposes the new attempt's run/job observation.
            if (!run.completed || run.attempt != observedAttempts[run.id]) continue
            val attempted = ciReads.any { receipt ->
                val args = receipt["arguments"] as? JsonObject
                args?.text("action") == "jobs" &&
                    args["run_id"] == JsonPrimitive(run.id) &&
                    args["run_attempt"] == JsonPrimitive(run.attempt) &&
                    args["expected_head_sha"] == JsonPrimitive(target.sha)
            }
            val pages = observations.filter { it["run_id"] == JsonPrimitive(run.id) && it["run_attempt"] == JsonPrimitive(run.attempt) && it.text("head_sha").equals(target.sha, true) }
            // An existing failed/partial observation is reported, not polled again.
            // Pagination still uses its exact issued continuation in the normal runner.
            if (attempted || GitHubWorkflowJobEvidence.completeJobs(pages) != null) continue
            call(
                "jobs",
                buildJsonObject {
                    put("run_id", run.id)
                    put("run_attempt", run.attempt)
                    put("expected_head_sha", target.sha)
                }
            )?.let { return it }
        }
        val runsAlreadyRead = ciReads.any { receipt ->
            val args = receipt["arguments"] as? JsonObject
            args?.text("action") == "runs" && args["head_sha"] == JsonPrimitive(target.sha)
        }
        // Do not spend the last call discovering a run whose jobs cannot then be read.
        if (runs.isNotEmpty() || runsAlreadyRead || remaining - cleanupReserve < 2) return null
        return call("runs", buildJsonObject { put("head_sha", target.sha) })
    }

    /** A deterministic draft supplies observed receipt identities; the ordinary verifier still decides every criterion. */
    fun candidate(receipts: Map<String, JsonObject>): String? {
        val target = target(receipts) ?: return null
        val tests = receipts.filter { (_, receipt) ->
            eligible(receipt) &&
                receipt["binding"] == JsonPrimitive(target.binding) &&
                receipt.text("repository").equals(target.repository, true) &&
                receipt.text("tool") == "github_ci${target.suffix}" &&
                (receipt["arguments"] as? JsonObject)?.text("action") == "jobs" &&
                GitHubInventoryObservation.planningRoot(receipt)?.text("head_sha").equals(target.sha, true)
        }.keys.toList().takeLast(8)
        if (tests.isEmpty()) return null
        return "Published implementation receipt `${target.receiptId}` identifies revision `${target.sha}`. " +
            "Workflow job observations at that revision are retained in receipts ${tests.joinToString(", ") { "`$it`" }}. " +
            "Verify their full results and latest attempts against each accepted requirement. These observations alone do not establish that all criteria passed."
    }

    private fun target(receipts: Map<String, JsonObject>): Target? {
        if (GitHubPendingApproval.snapshot(receipts).isNotEmpty() ||
            receipts.values.any {
                GitHubExecutionLedger.receiptState(it) in setOf(GitHubExecutionLedger.ReceiptState.UNKNOWN_OUTCOME, GitHubExecutionLedger.ReceiptState.DISPATCHED)
            }
        ) {
            return null
        }
        val implementation = receipts.entries.lastOrNull { (_, receipt) -> eligible(receipt) && GitHubIssueRemediation.publishedRevision(receipt) != null } ?: return null
        val row = implementation.value
        val sha = GitHubIssueRemediation.publishedRevision(row) ?: return null
        val repository = row.text("repository").takeIf { it.matches(Regex("[^/\\s]+/[^/\\s]+")) } ?: return null
        val binding = row.text("binding").takeIf(String::isNotBlank) ?: return null
        val head = receipts.values.lastOrNull { eligible(it) && it["binding"] == JsonPrimitive(binding) && it.text("repository").equals(repository, true) && it["head_observation"] == JsonPrimitive(true) }
        if (head != null && !head.text("source_head_sha").equals(sha, true)) return null
        val tool = row.text("tool")
        return Target(implementation.key, sha, repository, binding, tool.indexOf("__").takeIf { it >= 0 }?.let(tool::substring).orEmpty())
    }

    private fun eligible(receipt: JsonObject): Boolean = receipt["trusted"] == JsonPrimitive(true) &&
        receipt["success"] == JsonPrimitive(true) &&
        receipt["verification_eligible"] != JsonPrimitive(false) &&
        receipt["repository_role"] != JsonPrimitive("READ_SOURCE")
    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.content.orEmpty()
}
