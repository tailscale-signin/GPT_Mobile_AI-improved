package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Exact short status intents only: an audit or a repair keeps the full engineering workflow. */
internal object GitHubStatusRequest {
    /** Four scoped reads; recovery/indexing and pagination cannot displace the checks read. */
    fun nextCall(tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>): ProviderEvent.ToolCall? {
        val steps = listOf("github_context" to "status", "github_context" to "checks", "github_pr" to "list", "github_context" to "commits")
        for ((operation, action) in steps) {
            val tool = tools.firstOrNull { it.name.substringBefore("__") == operation } ?: continue
            if (receipts.values.any { it["tool"] == JsonPrimitive(tool.name) && (it["arguments"] as? JsonObject)?.get("action") == JsonPrimitive(action) }) continue
            val context = receipts.values.firstNotNullOfOrNull { receipt ->
                if ((receipt["arguments"] as? JsonObject)?.get("action") != JsonPrimitive("status") || receipt["success"] != JsonPrimitive(true)) return@firstNotNullOfOrNull null
                val root = receipt["result"] as? JsonObject
                (root?.get("data") as? JsonObject) ?: root
            }
            return ProviderEvent.ToolCall(
                "status-$action",
                tool.name,
                buildJsonObject {
                    put("action", action)
                    if (action == "checks" || action == "commits") context?.get("commit_sha")?.let { put("commit_sha", it) }
                    if (action == "commits") put("per_page", 5)
                    if (action == "list") {
                        put("state", "open")
                        put("per_page", 5)
                    }
                }
            )
        }
        return null
    }
    fun matches(text: String): Boolean = text.trim().lowercase().trimEnd('.', '?', '!').trim() in setOf(
        "status",
        "repo status",
        "status of repo",
        "status of the repo",
        "status of repository",
        "status of the repository",
        "repository status",
        "github status",
        "git status",
        "show status",
        "show repository status",
        "what is the repository status"
    )

    fun isGreeting(text: String): Boolean = text.trim().lowercase().trimEnd('.', '?', '!').trim() in setOf("hi", "hello", "hey", "good morning", "good afternoon", "good evening")

    const val INSTRUCTION = "Status: inspect the current repository/branch, recent commits, open PRs and latest checks with at most four read-only calls. Do not read source, resume work, edit or poll. Separate latest workflow from required checks; unknown worktree/tests stay unknown."
}
