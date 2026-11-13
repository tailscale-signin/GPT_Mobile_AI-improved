package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Exact short status intents only: an audit or a repair keeps the full engineering workflow. */
internal object GitHubStatusRequest {
    /** Base reads happen first; remaining accepted calls can follow pinned pages or health obligations. */
    fun nextCall(tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>, now: Long = System.currentTimeMillis(), epoch: Long? = null, task: String = "status", acceptedRepository: String? = null): ProviderEvent.ToolCall? {
        val selectedReceipts = GitHubStatusEvidence.selected(receipts, acceptedRepository)
        fun fresh(receipt: JsonObject) = GitHubStatusEvidence.fresh(receipt, now, epoch)
        val context = selectedReceipts.values.toList().asReversed().firstNotNullOfOrNull { receipt ->
            if ((receipt["tool"] as? JsonPrimitive)?.content?.substringBefore("__") != "github_context" || (receipt["arguments"] as? JsonObject)?.get("action") != JsonPrimitive("status") || receipt["success"] != JsonPrimitive(true) || !fresh(receipt)) return@firstNotNullOfOrNull null
            GitHubStatusEvidence.data(receipt)
        }
        val head = (context?.get("commit_sha") as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) }
        val branch = (context?.get("branch") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        fun call(operation: String, arguments: JsonObject, afterReceiptId: String? = null): ProviderEvent.ToolCall? {
            val tool = tools.firstOrNull { it.name.substringBefore("__") == operation } ?: return null
            val action = (arguments["action"] as? JsonPrimitive)?.content.orEmpty()
            val candidates = if (afterReceiptId == null) selectedReceipts.values else selectedReceipts.entries.dropWhile { it.key != afterReceiptId }.map { it.value }
            val attempts = candidates.filter {
                it["tool"] == JsonPrimitive(tool.name) &&
                    (
                        fresh(it) ||
                            it["success"] == JsonPrimitive(false) &&
                            it["verification_eligible"] != JsonPrimitive(false) &&
                            (epoch == null || it["epoch"] == JsonPrimitive(epoch)) &&
                            (it["observed_at_ms"] as? JsonPrimitive)?.longOrNull?.let { at -> at > 0 && now - at in 0..GitHubEvidencePolicy.MAX_AGE_MS } == true
                        ) &&
                    arguments.all { (key, value) ->
                        val actual = (it["arguments"] as? JsonObject)?.get(key)
                        when (key) {
                            "response_detail" -> true
                            "per_page" -> action != "list" || !health(task) || actual == value || GitHubStatusEvidence.data(it)?.get("has_more") == JsonPrimitive(false)
                            "page" -> (actual ?: JsonPrimitive(1)) == value
                            "state" -> (actual ?: JsonPrimitive("open")) == value
                            else -> actual == value
                        }
                    }
            }
            if (attempts.isNotEmpty()) {
                val retryable = attempts.last()["error_code"] in setOf("NETWORK_ERROR", "HTTP_502", "HTTP_503", "HTTP_504", "TOOL_TIMEOUT").map(::JsonPrimitive)
                val successful = attempts.filter { it["success"] == JsonPrimitive(true) }
                val truncatedSource = successful.isNotEmpty() && successful.all { (it["result"] as? JsonObject)?.get("observationTruncated") == JsonPrimitive(true) }
                if (successful.isNotEmpty() && !truncatedSource || attempts.size >= 2 || !retryable && !truncatedSource) return null
            }
            return ProviderEvent.ToolCall(
                "status-$action-${receipts.size}" + if (attempts.isEmpty()) "" else "-recovery",
                tool.name,
                JsonObject(arguments + if ((tool.inputSchema["properties"] as? JsonObject)?.containsKey("response_detail") == true) mapOf("response_detail" to JsonPrimitive("compact")) else emptyMap())
            )
        }
        call("github_context", buildJsonObject { put("action", "status") })?.let { return it }
        if (head != null) {
            call(
                "github_context",
                buildJsonObject {
                    put("action", "checks")
                    put("commit_sha", head)
                    put("page", 1)
                }
            )?.let { return it }
        }
        call(
            "github_pr",
            buildJsonObject {
                put("action", "list")
                put("state", "open")
                put("per_page", if (health(task)) 100 else 5)
                put("page", 1)
            }
        )?.let { return it }
        if (head != null) {
            call(
                "github_context",
                buildJsonObject {
                    put("action", "commits")
                    put("commit_sha", head)
                    put("per_page", 5)
                }
            )?.let { return it }
        }
        if (head != null) {
            val inventory = GitHubStatusEvidence.checks(selectedReceipts, head, now, epoch)
            inventory.nextPage?.let { page ->
                call(
                    "github_context",
                    buildJsonObject {
                        put("action", "checks")
                        put("commit_sha", head)
                        put("page", page)
                    },
                    afterReceiptId = inventory.receiptIds.firstOrNull()
                )?.let { return it }
            }
        }
        if (!health(task) || head == null || branch == null) return null
        call(
            "github_context",
            buildJsonObject {
                put("action", "branch")
                put("branch", branch)
            }
        )?.let { return it }
        call(
            "github_ci",
            buildJsonObject {
                put("action", "runs")
                put("branch", branch)
                put("status", "failure")
                put("per_page", 5)
            }
        )?.let { return it }
        val pullPages = GitHubStatusEvidence.openPullPages(selectedReceipts, now, epoch)
        val pulls = pullPages.values
        // Use only observed PR identities and revision leases; never invent a PR or follow a tool-supplied write recipe.
        pulls.forEach { receipt ->
            val data = GitHubStatusEvidence.data(receipt) ?: return@forEach
            ((data["items"] ?: data["pull_requests"]) as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach pull@{ pull ->
                val number = (pull["number"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: return@pull
                val pullHead = ((pull["head"] as? JsonObject)?.get("sha") as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) } ?: return@pull
                val pullBase = ((pull["base"] as? JsonObject)?.get("sha") as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) } ?: return@pull
                call(
                    "github_pr",
                    buildJsonObject {
                        put("action", "context")
                        put("pull_number", number)
                        put("expected_head_sha", pullHead)
                        put("expected_base_sha", pullBase)
                        put("include_health", true)
                        put("include_files", false)
                        put("include_checks", true)
                    }
                )?.let { return it }
            }
        }
        val lastPullPage = pulls.mapNotNull { receipt -> GitHubStatusEvidence.data(receipt)?.let { receipt to it } }.maxByOrNull { (_, data) -> (data["page"] as? JsonPrimitive)?.intOrNull ?: 1 }
        if (lastPullPage?.second?.get("has_more") == JsonPrimitive(true)) {
            val page = (lastPullPage.second["page"] as? JsonPrimitive)?.intOrNull ?: 1
            call(
                "github_pr",
                buildJsonObject {
                    put("action", "list")
                    put("state", "open")
                    put("per_page", 100)
                    put("page", page + 1)
                },
                afterReceiptId = pullPages.keys.firstOrNull()
            )?.let { return it }
        }
        return null
    }

    fun health(task: String): Boolean = matches(task) && Regex("(?i)\\b(?:health|required checks|recent failures|outstanding review decisions)\\b").containsMatchIn(GitHubTaskContinuity.request(task))

    const val POLICY_REQUIREMENT = "Inspect required-check policy for the selected branch without treating unavailable rules as no requirements"
    const val FAILURES_REQUIREMENT = "Report recent failed workflow runs for the selected branch with their observed scope"
    const val REVIEWS_REQUIREMENT = "Report outstanding review decisions for open pull requests at their observed head and base revisions"

    fun requirements(task: String): List<String> = listOf(
        "Identify the selected repository, branch and observed head revision",
        "Report recent commits and open pull requests with their observed scope",
        "Report exact-head check results and distinguish them from the latest workflow observation",
        "Identify unavailable checks and unknown required-check policy without claiming unverified health"
    ) + if (health(task)) listOf(POLICY_REQUIREMENT, FAILURES_REQUIREMENT, REVIEWS_REQUIREMENT) else emptyList()
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

    const val INSTRUCTION = "Status: inspect the current repository/branch, recent commits, open PRs and latest checks with bounded read-only calls. Keep checks and their continuation pages pinned to the observed commit. A health request also requires branch policy, recent failures and current PR review decisions. Respect the accepted call allowance; unavailable or incomplete observations remain partial. Do not read source, resume work, edit or poll. Separate latest workflow from required checks; unknown worktree/tests stay unknown."
}
