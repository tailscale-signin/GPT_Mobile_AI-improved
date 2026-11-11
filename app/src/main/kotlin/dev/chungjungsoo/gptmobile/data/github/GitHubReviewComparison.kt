package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Resolve the review's immutable comparison before spending its allowance on inventories. */
internal object GitHubReviewComparison {
    private val sha = Regex("[a-fA-F0-9]{40}")
    private val explicitBase = Regex("(?i)\\b(?:base|baseline|against)\\s*(?:sha|commit)?\\s*[:=]?\\s*`?([a-f0-9]{40})\\b")
    private val quotedMaterial = Regex("(?s)```.*?(?:```|\\z)|~~~.*?(?:~~~|\\z)|`[^`\\r\\n]*`|\"(?:\\\\.|[^\"\\\\])*\"|“[^”]*”|‘[^’]*’|(?<![\\p{L}\\p{N}])'[^'\\r\\n]*'")
    const val INSTRUCTION = "Branch review: establish exact base_sha and head_sha first. Use an observed matching PR base; otherwise resolve the default branch at a full SHA for a non-default selected branch. If the selected branch is the default branch and no baseline was supplied, ask which commit/tag to compare; never infer an empty diff by comparing the branch to itself. Call github_context action=changes with both pinned revisions, then read the relevant changed source at those revisions. Checks and branch inventories do not constitute a source review."
    data class Plan(val checkpoint: JsonObject, val nextCall: ProviderEvent.ToolCall?)

    private fun request(task: String): String = GitHubTaskContinuity.request(task).replace(quotedMaterial) { quoted ->
        // A SHA quoted by itself is a literal argument, while quoted instructions
        // and attached code/logs must not select the review's baseline.
        quoted.value.removeSurrounding("`").takeIf { sha.matches(it) } ?: " "
    }.lineSequence().filterNot { it.trimStart().startsWith(">") }.joinToString("\n")

    fun matches(task: String): Boolean {
        val request = request(task)
        return "[github-workflow-preset:review_branch]" in request || Regex("(?i)\\breview\\b[^\\n.:;]{0,100}\\bbranch\\b").containsMatchIn(request)
    }

    fun requiresComparison(requirement: String): Boolean = matches(requirement) || Regex("(?i)head/base|base/head|changed files|correctness findings|file/line").containsMatchIn(requirement)

    /** Status observations cannot satisfy a changed-source review acceptance criterion. */
    fun incompleteReason(task: String, receipts: Map<String, JsonObject>): String? {
        val checkpoint = plan(task, emptyList(), receipts)?.checkpoint ?: return null
        if (checkpoint["comparison_verified"] != JsonPrimitive(true)) return checkpoint.text("detail")
        val head = checkpoint.text("head_sha")
        val base = checkpoint.text("base_sha")
        val eligible = receipts.values.filter { it["trusted"] == JsonPrimitive(true) && it["verification_eligible"] != JsonPrimitive(false) && it["success"] == JsonPrimitive(true) }
        fun data(row: JsonObject) = GitHubSemanticSummary.data(row["result"] as? JsonObject ?: JsonObject(emptyMap()))
        val comparisons = eligible.filter { row ->
            row.text("tool").substringBefore("__") == "github_context" && (row["arguments"] as? JsonObject)?.text("action") == "changes" && data(row).text("base_sha") == base && data(row).text("head_sha") == head
        }
        val changed = comparisons.flatMap { (data(it)["files"] as? JsonArray).orEmpty() }.filterIsInstance<JsonObject>().mapNotNull { it.text("filename").takeIf(String::isNotBlank) }.toSet()
        if (changed.isEmpty() && comparisons.any { (data(it)["files_total"] as? JsonPrimitive)?.intOrNull == 0 && data(it)["has_more"] != JsonPrimitive(true) }) return null
        val sourceRead = eligible.any { row ->
            val args = row["arguments"] as? JsonObject ?: return@any false
            val pinned = args.text("commit_sha").ifBlank { data(row).text("commit_sha").ifBlank { row.text("resource_sha") } }
            row.text("tool").substringBefore("__") == "github_read" &&
                pinned in setOf(base, head) &&
                (args["ranges"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().any { it.text("path") in changed }
        }
        return if (sourceRead) null else "The exact comparison is retained, but no relevant changed-source read at the pinned base/head is recorded. Checks and inventories cannot verify a source review."
    }

    fun plan(task: String, tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>): Plan? {
        if (!matches(task)) return null
        val eligible = receipts.values.filter { it["trusted"] == JsonPrimitive(true) && it["verification_eligible"] != JsonPrimitive(false) && it["success"] == JsonPrimitive(true) }
        fun operation(row: JsonObject) = row.text("tool").substringBefore("__")
        fun args(row: JsonObject) = row["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        fun data(row: JsonObject) = GitHubSemanticSummary.data(row["result"] as? JsonObject ?: JsonObject(emptyMap()))
        var head = ""
        var base = ""
        var policy = "UNRESOLVED"
        fun call(operation: String, arguments: JsonObject): ProviderEvent.ToolCall? {
            val tool = tools.firstOrNull { it.name.substringBefore("__") == operation } ?: return null
            if (ToolArgumentValidator.errors(arguments, tool.inputSchema).isNotEmpty()) return null
            if (receipts.values.any { it["tool"] == JsonPrimitive(tool.name) && it["arguments"] == arguments && it["verification_eligible"] != JsonPrimitive(false) }) return null
            return ProviderEvent.ToolCall("review-base-${receipts.size}-${GitHubEvidenceBook.digest(arguments.toString()).take(8)}", tool.name, arguments)
        }
        fun state(code: String, detail: String, next: ProviderEvent.ToolCall? = null) = Plan(
            buildJsonObject {
                put("state", code)
                put("detail", detail)
                put("comparison_policy", policy)
                if (head.isNotBlank()) put("head_sha", head)
                if (base.isNotBlank()) put("base_sha", base)
                put("comparison_verified", code == "READ_CHANGED_SOURCE")
                put("source_review_verified", false)
                put("user_input_required", code == "BASELINE_REQUIRED")
                next?.let {
                    put(
                        "next_call",
                        buildJsonObject {
                            put("tool", it.name)
                            put("arguments", it.arguments)
                        }
                    )
                }
            },
            next
        )
        val context = eligible.lastOrNull {
            operation(it) == "github_context" &&
                args(it).text("action") == "status" &&
                sha.matches(data(it).text("commit_sha")) &&
                it["repository_role"] != JsonPrimitive("READ_SOURCE") &&
                (it.text("workspace_branch").isBlank() || data(it).text("branch") == it.text("workspace_branch"))
        }
            ?: return state("HEAD_REQUIRED", "Resolve the selected branch and default branch before comparing.", call("github_context", buildJsonObject { put("action", "status") }))
        val selected = data(context)
        head = selected.text("commit_sha")
        val branch = selected.text("branch").ifBlank { context.text("workspace_branch").ifBlank { context.text("branch") } }
        val default = selected.text("default_branch").ifBlank { (selected["repository"] as? JsonObject)?.text("default_branch").orEmpty() }
        base = explicitBase.find(request(task))?.groupValues?.get(1).orEmpty()
        if (base.isNotBlank()) policy = "EXPLICIT_BASE_SHA"
        if (base.isBlank()) {
            val pulls = eligible.filter { operation(it) == "github_pr" && args(it).text("action") in setOf("list", "headers", "context") }
            val pull = pulls.asReversed().flatMap { row ->
                val source = data(row)
                ((source["items"] ?: source["pull_requests"]) as? JsonArray)?.filterIsInstance<JsonObject>() ?: listOfNotNull(source["pull_request"] as? JsonObject)
            }.firstOrNull { (it["head"] as? JsonObject)?.text("sha") == head && sha.matches((it["base"] as? JsonObject)?.text("sha").orEmpty()) }
            if (pull != null) {
                base = (pull["base"] as JsonObject).text("sha")
                policy = "OBSERVED_PR_BASE"
            } else {
                val inventory = pulls.filter { args(it).text("action") == "list" }
                if (inventory.isEmpty()) {
                    return state(
                        "PR_BASE_LOOKUP",
                        "Look for a PR whose head matches the pinned selected revision.",
                        call(
                            "github_pr",
                            buildJsonObject {
                                put("action", "list")
                                put("state", "open")
                                put("per_page", 100)
                            }
                        )
                    )
                }
                val pending = GitHubTestChecklist.pendingPages(receipts.filterValues { operation(it) == "github_pr" && args(it).text("action") == "list" }).firstOrNull()
                if (pending != null) return state("PR_BASE_LOOKUP", "Finish the exact retained PR inventory page before excluding a PR base.", (pending["arguments"] as? JsonObject)?.let { call(pending.text("tool").substringBefore("__"), it) })
                if (inventory.none { data(it)["has_more"] == JsonPrimitive(false) }) return state("PR_BASE_UNVERIFIED", "PR inventory completeness is unverified; reuse its exact continuation or establish the intended baseline.")
                if (branch.isBlank() || default.isBlank()) return state("BASELINE_REQUIRED", "The selected/default branch identity is incomplete. Choose the baseline for the captured head.")
                if (branch == default) return state("BASELINE_REQUIRED", "The selected branch is also the default branch ($branch). Which commit or tag should be the review baseline?")
                policy = "DEFAULT_BRANCH_HEAD"
                val baseline = eligible.lastOrNull { operation(it) == "github_context" && args(it).text("action") == "resolve" && args(it).text("ref") == default && sha.matches(data(it).text("commit_sha")) }
                if (baseline == null) {
                    return state(
                        "BASE_SHA_REQUIRED",
                        "Resolve the default branch to an exact immutable comparison SHA.",
                        call(
                            "github_context",
                            buildJsonObject {
                                put("action", "resolve")
                                put("ref", default)
                            }
                        )
                    )
                }
                base = data(baseline).text("commit_sha")
            }
        }
        val comparison = eligible.lastOrNull { operation(it) == "github_context" && args(it).text("action") == "changes" && data(it).text("base_sha") == base && data(it).text("head_sha") == head }
        if (comparison != null) {
            val pending = GitHubTestChecklist.pendingPages(receipts.filterValues { operation(it) == "github_context" && args(it).text("action") == "changes" && data(it).text("base_sha") == base && data(it).text("head_sha") == head }).firstOrNull()
            if (pending != null) return state("COMPARISON_PAGES_PENDING", "The comparison has unread changed-file pages; retain the exact pair while following its continuation.", (pending["arguments"] as? JsonObject)?.let { call(pending.text("tool").substringBefore("__"), it) })
            if (data(comparison)["has_more"] == JsonPrimitive(true)) return state("COMPARISON_PAGES_PENDING", "The comparison has unread changed-file pages; restore its exact continuation before asserting coverage.")
            return state("READ_CHANGED_SOURCE", "The exact comparison is retained. Inspect its changed files and relevant source at the pinned base/head before reporting correctness findings.")
        }
        return state(
            "COMPARE_REQUIRED",
            "Compare the pinned revisions before reading changed source.",
            call(
                "github_context",
                buildJsonObject {
                    put("action", "changes")
                    put("base_sha", base)
                    put("commit_sha", head)
                    put("include_patches", true)
                }
            )
        )
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
