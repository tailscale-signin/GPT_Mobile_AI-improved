package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.github.GitHubSemanticSummary
import dev.chungjungsoo.gptmobile.data.github.GitHubTargetPolicy
import dev.chungjungsoo.gptmobile.data.github.githubOperationFamily
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Bounded working state; bulk receipts remain in the durable task/evidence stores. */
internal class GitHubInvestigationProgress {
    private val observed = linkedSetOf<String>()
    private val recentPageFailures = ArrayDeque<Boolean>()
    private var staleRounds = 0
    private var alternateReadReplanUsed = false

    fun observe(exchange: AgentToolExchange): String? {
        var acquired = false
        val calls = exchange.calls.associateBy { it.callId }
        for (result in exchange.results) {
            val call = calls[result.callId] ?: continue
            val payload = (result.content as? ToolResultContent.Json)?.value as? JsonObject
            if (result.isError || payload?.get("schema") == JsonPrimitive("github.repeat.v1")) continue
            val source = ((result.executionReceipt?.get("saved_view_source_sha256") ?: result.executionReceipt?.get("source_result_sha256")) as? JsonPrimitive)?.contentOrNull
            val cursor = (call.arguments["view_cursor"] as? JsonPrimitive)?.contentOrNull
            val key = resolvedRevision(call, result) ?: if (source != null && source.matches(Regex("[a-f0-9]{64}"))) {
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
            staleRounds >= 4 -> "Four rounds added no new evidence. Report the retained findings and remaining blockers without repeating the investigation."
            else -> null
        }
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

    private val pageFailures = setOf("SAVED_VIEW_UNAVAILABLE", "SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_CURSOR_ALTERED")
    private val revision = Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")
}
