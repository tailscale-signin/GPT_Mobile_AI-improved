package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.github.GitHubTargetPolicy
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
        for ((call, result) in exchange.calls.zip(exchange.results)) {
            val key = toolRepeatKey(call)
            if (!result.isError) {
                if (key !in observed) acquired = true
                observed += key
            }
            while (observed.size > 256) observed.remove(observed.first())
        }
        staleRounds = if (acquired) 0 else staleRounds + 1
        recentPageFailures.addLast(exchange.results.any { it.errorCode in pageFailures })
        while (recentPageFailures.size > 8) recentPageFailures.removeFirst()
        return when {
            recentPageFailures.count { it } >= 4 -> "Repeated saved-page failures prevented further progress. Report the retained findings, exact failures and remaining source-recovery actions."
            staleRounds >= 4 -> "Four rounds added no new successful operation. Report the retained findings and remaining blockers without repeating the investigation."
            else -> null
        }
    }

    /** Give unused read actions one bounded correction; the original guard still ends another stall. */
    fun replan(exchanges: List<AgentToolExchange>, tools: Collection<AgentToolDefinition>): String? {
        if (alternateReadReplanUsed) return null
        val attempted = exchanges.flatMap { it.calls }.mapTo(hashSetOf()) { it.name to (it.arguments["action"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
        val alternatives = tools.filter { it.modelVisible && GitHubOperationTool.operationName(it.name) in setOf("github_context", "github_ci", "github_pr", "github_read", "github_search") }
            .flatMap { tool ->
                val properties = tool.inputSchema["properties"] as? JsonObject
                val actions = ((properties?.get("action") as? JsonObject)?.get("enum") as? JsonArray).orEmpty()
                actions.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { action ->
                    tool.name to action !in attempted &&
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

    private val pageFailures = setOf("SAVED_VIEW_UNAVAILABLE", "SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_CURSOR_ALTERED")
}
