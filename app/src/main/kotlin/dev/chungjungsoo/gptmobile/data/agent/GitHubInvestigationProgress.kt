package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.github.GitHubExecutionContinuation
import dev.chungjungsoo.gptmobile.data.github.GitHubSearchFrontier
import dev.chungjungsoo.gptmobile.data.github.GitHubSemanticSummary
import dev.chungjungsoo.gptmobile.data.github.GitHubTargetPolicy
import dev.chungjungsoo.gptmobile.data.github.githubOperationFamily
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Bounded working state; bulk receipts remain in the durable task/evidence stores. */
internal class GitHubInvestigationProgress(private val acceptedTask: String = "") {
    private val observed = linkedSetOf<String>()
    private val requiredSearchCalls = linkedSetOf<JsonObject>()
    private val requiredSearchEnds = linkedMapOf<String, Int>()
    private val recentPageFailures = ArrayDeque<Boolean>()
    private var staleRounds = 0
    private var alternateReadReplanUsed = false
    private val ciProgress = GitHubCiReadProgress()
    private val checklistDiscovery = GitHubChecklistDiscoveryProgress(acceptedTask)

    fun observe(exchange: AgentToolExchange): String? {
        var acquired = false
        var requiredCoverageAdvanced = false
        val calls = exchange.calls.associateBy { it.callId }
        for (result in exchange.results) {
            val call = calls[result.callId] ?: continue
            val payload = (result.content as? ToolResultContent.Json)?.value as? JsonObject
            if (result.isError || payload?.get("schema") == JsonPrimitive("github.repeat.v1")) continue
            // Required checklist pages/schema handoffs may finish their bounded work,
            // but neither they nor answered questions reset source-acquisition staleness.
            if (call.name == ProgressiveToolCatalog.NAME) {
                if (checklistDiscovery.advances(call, result)) requiredCoverageAdvanced = true
                continue
            }
            if (call.name.githubOperationFamily() == "github_decide") continue
            // Native search pagination is traversal evidence. A changed response hash,
            // query or cursor alone must not restart the useful-source progress budget.
            val raw = ((result.retainedContent ?: result.content) as? ToolResultContent.Json)?.value as? JsonObject
            val data = raw?.let(GitHubSemanticSummary::data)
            val ciAdvanced = ciProgress.observe(call, result)
            if (ciAdvanced != null) {
                if (ciAdvanced) acquired = true
                continue
            }
            if (call.name.githubOperationFamily() == "github_search" && "view_cursor" !in call.arguments && data?.get("items") is JsonArray) {
                if (advancesRequiredCoverage(call, result, data)) requiredCoverageAdvanced = true
                val scope = listOf(call.name.substringAfter("__", ""), call.arguments["owner"], call.arguments["repo"], data["commit_sha"])
                for (row in (data["items"] as JsonArray).filterIsInstance<JsonObject>()) {
                    val path = (row["path"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: continue
                    val key = "search-source|$scope|$path|${row["sha"]}"
                    if (observed.add(key)) acquired = true
                    for (match in (row["matches"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()) {
                        val line = (match["line"] as? JsonPrimitive)?.contentOrNull ?: continue
                        if (observed.add("$key|$line|${match["text"]}")) acquired = true
                    }
                }
                while (observed.size > 256) observed.remove(observed.first())
                continue
            }
            val source = ((result.executionReceipt?.get("saved_view_source_sha256") ?: result.executionReceipt?.get("source_result_sha256")) as? JsonPrimitive)?.contentOrNull
            val cursor = (call.arguments["view_cursor"] as? JsonPrimitive)?.contentOrNull
            val key = resolvedRevision(call, result) ?: mutableObservation(call, result) ?: if (source != null && source.matches(Regex("[a-f0-9]{64}"))) {
                // Cursors may use another byte budget for exactly the same start position.
                val offset = cursor?.split(':')?.getOrNull(1) ?: "initial"
                val ranges = (payload?.get("items") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().joinToString { row ->
                    "${row["sha"]}:${row["range_offset"]}:${(row["content"] as? JsonPrimitive)?.content?.length}"
                }
                "${call.name}|$source|$offset|$ranges"
            } else {
                toolRepeatKey(call)
            }
            if (observed.add(key)) acquired = true
            while (observed.size > 256) observed.remove(observed.first())
        }
        staleRounds = if (acquired) 0 else staleRounds + 1
        recentPageFailures.addLast(exchange.results.any { it.errorCode in pageFailures })
        while (recentPageFailures.size > 8) recentPageFailures.removeFirst()
        return when {
            recentPageFailures.count { it } >= 4 -> "Repeated saved-page failures prevented further progress. Report the retained findings, exact failures and remaining source-recovery actions."
            staleRounds >= 4 && !requiredCoverageAdvanced -> "Four rounds added no new source or result evidence. Retain the exact search frontier; replan the next task-relevant source read or report the concrete remaining blocker. Cursor movement alone is not source inspection."
            else -> null
        }
    }

    /** Required traversal can continue its issued frontier without becoming useful-source progress. */
    private fun advancesRequiredCoverage(call: ProviderEvent.ToolCall, result: AgentToolResult, data: JsonObject): Boolean {
        if (!GitHubExecutionContinuation.requiresExhaustiveSearch(acceptedTask, call.arguments)) return false
        val issued = requiredSearchCalls.remove(JsonObject(mapOf("tool" to JsonPrimitive(call.name), "arguments" to call.arguments)))
        val next = GitHubSearchFrontier.continuation(call.name, call.arguments, result.executionReceipt)?.takeIf { recipe ->
            val arguments = recipe["arguments"] as JsonObject
            val frontier = result.executionReceipt?.get("search_frontier") as JsonObject
            val key = "${call.name}|${arguments["owner"]}|${arguments["repo"]}|${frontier["commit_sha"]}|${frontier["search_id"]}|${frontier["query_identity_sha256"]}"
            val end = (arguments["index_cursor"] as JsonPrimitive).intOrNull ?: return@takeIf false
            (end > (requiredSearchEnds[key] ?: -1)).also { advanced -> if (advanced) requiredSearchEnds[key] = end }
        }
        if (next != null) requiredSearchCalls += next
        while (requiredSearchCalls.size > 16) requiredSearchCalls.remove(requiredSearchCalls.first())
        while (requiredSearchEnds.size > 16) requiredSearchEnds.remove(requiredSearchEnds.keys.first())
        if (!issued) return false
        if (next != null) return true
        // A complete final page may have no next recipe. It must close the exact
        // issued pinned search interval; a repeated or newly invented cursor cannot.
        val terminal = GitHubSearchFrontier.capture(call.name, call.arguments, data, emptyList()) ?: return false
        return terminal["complete"] == JsonPrimitive(true) &&
            terminal["has_more"] == JsonPrimitive(false) &&
            terminal["commit_sha"] == call.arguments["commit_sha"] &&
            terminal["search_id"] == call.arguments["search_id"] &&
            terminal["interval_start"] == call.arguments["index_cursor"] &&
            terminal["interval_end"] == terminal["total_candidate_files"] &&
            terminal["interval_end"] != terminal["interval_start"]
    }

    /** Give unused read actions one bounded correction; the original guard still ends another stall. */
    fun replan(exchanges: List<AgentToolExchange>, tools: Collection<AgentToolDefinition>): String? {
        if (alternateReadReplanUsed) return null
        fun surface(name: String) = name.githubOperationFamily() + "__" + name.substringAfter("__", "")
        val attempted = exchanges.flatMap { it.calls }.mapTo(hashSetOf()) { surface(it.name) to (it.arguments["action"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
        val alternatives = tools.filter { it.modelVisible && GitHubOperationTool.operationName(it.name) in setOf("github_context", "github_ci", "github_pr", "github_read", "github_search") }
            .flatMap { tool ->
                val properties = tool.inputSchema["properties"] as? JsonObject
                val actions = ((properties?.get("action") as? JsonObject)?.get("enum") as? JsonArray).orEmpty()
                actions.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { action ->
                    surface(tool.name) to action !in attempted &&
                        GitHubOperationTool.nativeArguments(tool.name, JsonObject(mapOf("action" to JsonPrimitive(action))))
                            ?.get("action")?.let { (it as? JsonPrimitive)?.contentOrNull }?.let(GitHubTargetPolicy::isReadAction) == true
                }.map { "${tool.name} action=$it" }
            }.take(8)
        if (alternatives.isEmpty()) return null
        alternateReadReplanUsed = true
        resume()
        return "The last investigation steps added no new evidence. Replan once using an unread action required by the accepted task, such as ${alternatives.joinToString()}. " +
            "Retain successful receipts and scoped failures. Do not repeat failed or suppressed arguments, invent identifiers, renew the budget, or repeat a write. " +
            "An unavailable source does not block independent authorized reads. If no remaining requirement has a usable read, report the exact unresolved requirements."
    }

    fun resume() {
        staleRounds = 0
        recentPageFailures.clear()
    }

    /** Restore accepted-task progress from durable receipts; Continue is not a new investigation. */
    fun restore(exchanges: List<AgentToolExchange>) {
        val calls = hashSetOf<String>()
        exchanges.forEach { exchange ->
            val fresh = exchange.calls.filter { calls.add(it.callId) }
            if (fresh.isNotEmpty()) observe(AgentToolExchange(fresh, exchange.results.filter { result -> fresh.any { it.callId == result.callId } }))
        }
    }

    private fun mutableObservation(call: ProviderEvent.ToolCall, result: AgentToolResult): String? {
        // Saved pages have an immutable source/offset identity below. Cursor-only
        // reads are not implicit status requests, even on the context tool.
        if ("view_cursor" in call.arguments) return null
        val operation = call.name.githubOperationFamily()
        val action = (call.arguments["action"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (operation != "github_context" || action !in setOf("", "status", "checks", "efficiency", "capabilities")) return null
        val raw = GitHubContinuationMemory.payload(result) ?: return null
        val summary = (result.executionReceipt?.get("semantic_summary") as? JsonObject)
            ?: GitHubSemanticSummary.from(operation, call.arguments, raw)
        // Observation timestamps, response digests and presentation options can
        // change while the repository, selected revision and check facts do not.
        val facts = JsonObject(summary.filterKeys { it !in setOf("source_result_sha256", "hash_scope", "scope", "observed_at_ms", "observed_at", "timestamp") })
        val resource = result.executionReceipt?.get("resource_context") as? JsonObject
        val scope = listOf("owner", "repo", "branch", "ref", "commit_sha").map { resource?.get(it) ?: call.arguments[it] }
        return "observation|$operation|${call.name.substringAfter("__", "")}|$scope|$facts"
    }

    /** A new receipt, live flag or display projection is not a new resolved source. */
    private fun resolvedRevision(call: ProviderEvent.ToolCall, result: AgentToolResult): String? {
        if (call.name.githubOperationFamily() != "github_context" || call.arguments["action"] != JsonPrimitive("resolve")) return null
        val raw = ((result.retainedContent ?: result.content) as? ToolResultContent.Json)?.value as? JsonObject ?: return null
        val data = GitHubSemanticSummary.data(raw)
        fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
        val resource = result.executionReceipt?.get("resource_context") as? JsonObject
        val owner = data.text("owner").ifBlank { resource?.text("owner").orEmpty() }
        val repository = data.text("repo").ifBlank { resource?.text("repo").orEmpty() }
        val commit = data.text("commit_sha")
        val tree = data.text("tree_sha")
        if (owner.isBlank() || repository.isBlank() || !commit.matches(revision) || !tree.matches(revision)) return null
        if (resource != null && listOf("owner" to owner, "repo" to repository).any { (key, value) -> resource.text(key).let { it.isNotBlank() && !it.equals(value, true) } }) return null
        val snapshot = data["snapshot"] as? JsonObject
        val scope = listOf("accountId", "connectionUid", "conversationId", "taskId").map { snapshot?.get(it) }
        return "resolved|${call.name.substringAfter("__", "")}|${owner.lowercase(java.util.Locale.ROOT)}/${repository.lowercase(java.util.Locale.ROOT)}|$scope|${data.text("persistent_scope")}|${data.text("ref")}|${commit.lowercase(java.util.Locale.ROOT)}|${tree.lowercase(java.util.Locale.ROOT)}"
    }

    private val pageFailures = setOf("SAVED_VIEW_UNAVAILABLE", "SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_CURSOR_ALTERED", "OUTPUT_LIMIT")
    private val revision = Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")
}
