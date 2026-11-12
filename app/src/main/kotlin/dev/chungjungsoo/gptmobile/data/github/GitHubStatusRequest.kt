package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Exact short status intents only: an audit or a repair keeps the full engineering workflow. */
internal object GitHubStatusRequest {
    /** Four scoped reads; recovery/indexing and pagination cannot displace the checks read. */
    fun nextCall(tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>, now: Long = System.currentTimeMillis(), epoch: Long? = null): ProviderEvent.ToolCall? {
        fun fresh(receipt: JsonObject): Boolean = receipt["trusted"] != JsonPrimitive(false) &&
            receipt["verification_eligible"] != JsonPrimitive(false) &&
            (epoch == null || receipt["epoch"] == JsonPrimitive(epoch)) &&
            (receipt["observed_at_ms"] as? JsonPrimitive)?.longOrNull?.let { it > 0 && now - it in 0..GitHubEvidencePolicy.MAX_AGE_MS } == true
        val context = receipts.values.toList().asReversed().firstNotNullOfOrNull { receipt ->
            if ((receipt["tool"] as? JsonPrimitive)?.content?.substringBefore("__") != "github_context" || (receipt["arguments"] as? JsonObject)?.get("action") != JsonPrimitive("status") || receipt["success"] != JsonPrimitive(true) || !fresh(receipt)) return@firstNotNullOfOrNull null
            val root = receipt["result"] as? JsonObject
            (root?.get("data") as? JsonObject) ?: root
        }
        val steps = listOf("github_context" to "status", "github_context" to "checks", "github_pr" to "list", "github_context" to "commits")
        for ((operation, action) in steps) {
            val tool = tools.firstOrNull { it.name.substringBefore("__") == operation } ?: continue
            val attempts = receipts.values.filter {
                it["tool"] == JsonPrimitive(tool.name) &&
                    (it["arguments"] as? JsonObject)?.get("action") == JsonPrimitive(action) &&
                    it["verification_eligible"] != JsonPrimitive(false) &&
                    (it["success"] != JsonPrimitive(true) || fresh(it) && (action !in setOf("checks", "commits") || (it["arguments"] as? JsonObject)?.get("commit_sha") == context?.get("commit_sha")))
            }
            if (attempts.isNotEmpty()) {
                val retryable = attempts.last()["error_code"] in setOf("NETWORK_ERROR", "HTTP_502", "HTTP_503", "HTTP_504", "TOOL_TIMEOUT").map(::JsonPrimitive)
                val successful = attempts.filter { it["success"] == JsonPrimitive(true) }
                val truncatedSource = successful.isNotEmpty() && successful.all { (it["result"] as? JsonObject)?.get("observationTruncated") == JsonPrimitive(true) }
                if (successful.isNotEmpty() && !truncatedSource || attempts.size >= 2 || !retryable && !truncatedSource) continue
            }
            return ProviderEvent.ToolCall(
                "status-$action-${receipts.size}" + if (attempts.isEmpty()) "" else "-recovery",
                tool.name,
                buildJsonObject {
                    put("action", action)
                    if (attempts.isNotEmpty() && (tool.inputSchema["properties"] as? JsonObject)?.containsKey("response_detail") == true) put("response_detail", "compact")
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
    fun matches(text: String): Boolean {
        val request = GitHubTaskContinuity.request(text).lowercase(java.util.Locale.ROOT).trim().trimEnd('.', '?', '!').trim()
        return request in setOf(
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
        ) ||
            scopedInspection(request)
    }

    private fun scopedInspection(request: String): Boolean =
        Regex("^(?:please\\s+)?(?:inspect|examine|review|check|show|summari[sz]e)\\b").containsMatchIn(request) &&
            Regex("\\b(?:repo(?:sitory)?|github)\\b").containsMatchIn(request) &&
            Regex("\\b(?:status|current state|recent commits|latest checks|ci|open (?:prs|pull requests))\\b").containsMatchIn(request) &&
            !Regex("\\b(?:fix|repair|implement|create|merge|publish|deploy|run|test|audit|source|code|diff|architecture|security|every|all features|issues|delete|remove|close|reopen|cancel|build|install|uninstall|enable|disable|configure|update|change|edit|add|enhance)\\b").containsMatchIn(request)

    fun isGreeting(text: String): Boolean = GitHubTaskContinuity.request(text).lowercase(java.util.Locale.ROOT).trimEnd('.', '?', '!').trim() in setOf("hi", "hello", "hey", "good morning", "good afternoon", "good evening")

    const val INSTRUCTION = "Status: inspect the current repository/branch, recent commits, open PRs and latest checks with four scoped read-only calls plus at most two calls for failed-read recovery. Do not read source, resume work, edit or poll. Separate latest workflow from required checks; unknown worktree/tests stay unknown."
}
