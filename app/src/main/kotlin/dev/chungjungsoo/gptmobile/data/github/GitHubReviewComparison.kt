package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Resolve the review's immutable comparison before spending its allowance on inventories. */
internal object GitHubReviewComparison {
    private val sha = Regex("[a-fA-F0-9]{40}")
    private val baselinePrefix = "\\b(?:baseline|base|against)\\b[ \\t]*(?:(?:sha|commit|branch|tag|ref)\\b[ \\t]*)?[:=]?[ \\t]*"
    private val explicitBase = Regex("(?i)$baselinePrefix([A-Za-z0-9][A-Za-z0-9._/-]{0,254})(?=\\s|$|[,;!?])")
    private val quotedBaselinePrefix = Regex("(?i)$baselinePrefix$")
    private val literalRef = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,254}")
    private val quotedMaterial = Regex("(?s)```.*?(?:```|\\z)|~~~.*?(?:~~~|\\z)|`[^`\\r\\n]*`|\"(?:\\\\.|[^\"\\\\])*\"|“[^”]*”|‘[^’]*’|(?<![\\p{L}\\p{N}])'[^'\\r\\n]*'")
    private val resolutionFailureCodes = GitHubFailurePolicy.codes + setOf(
        "GITHUB_CIRCUIT_OPEN", "GITHUB_TRANSIENT_NETWORK", "GITHUB_REF_UNAVAILABLE", "GITHUB_OPERATION_FAILED",
        "GITHUB_TLS_FAILURE", "GITHUB_UNEXPECTED_RESPONSE", "GITHUB_RESPONSE_TOO_LARGE", "GITHUB_NOT_FOUND_OR_HIDDEN",
        "DNS_RESOLUTION_FAILED", "NETWORK_TIMEOUT", "NETWORK_DISCONNECTED", "CONNECTION_ABORTED", "TRANSPORT_EOF", "TLS_FAILURE",
        "RATE_LIMITED", "AUTHENTICATION_FAILED", "ACCESS_FORBIDDEN", "RECONCILIATION_REQUIRED", "TOOL_OUTCOME_UNKNOWN"
    )
    private val resolutionRecoveryActions = setOf("REFRESH_REPOSITORY_ACCESS", "CHANGE_REPOSITORY", "SELECT_REF", "RETRY_AFTER_CONFIGURATION_CHANGE")
    const val INSTRUCTION = "Branch review: establish exact base_sha and head_sha first. Resolve a user-supplied baseline commit, tag or branch to an exact SHA. Otherwise use an observed matching PR base, or resolve the default branch at a full SHA for a non-default selected branch. If the selected branch is the default branch and no baseline was supplied, ask for an updated review request with Baseline: <commit, tag or branch>; never infer an empty diff by comparing the branch to itself. Call github_context action=changes with both pinned revisions, then read the relevant changed source at those revisions. Checks and branch inventories do not constitute a source review."
    data class Plan(val checkpoint: JsonObject, val nextCall: ProviderEvent.ToolCall?)

    private fun request(task: String): String {
        val request = GitHubTaskContinuity.request(task)
        return request.replace(quotedMaterial) { quoted ->
            // Only a literal argument immediately following the request's baseline
            // selector survives quoting. Code/logs and quoted instructions do not.
            val literal = quoted.value.drop(1).dropLast(1)
            literal.takeIf {
                literalRef.matches(it) && quotedBaselinePrefix.containsMatchIn(request.substring(0, quoted.range.first))
            } ?: " "
        }.lineSequence().filterNot { it.trimStart().startsWith(">") }.joinToString("\n")
    }

    fun matches(task: String): Boolean {
        val request = request(task)
        val branch = "(?:(?:the|this|that|my|our|a|selected|current|feature|target|source|entire|latest|pr|pull request)\\s+){0,4}branch\\b"
        return "[github-workflow-preset:review_branch]" in request ||
            Regex("(?i)\\breview(?:\\s+and\\s+(?:fix|audit|inspect))?\\s+(?:(?:(?:all|the)\\s+){0,2}(?:changes|diff|code(?:/source)?|source(?:\\s+code)?)\\s+(?:on|in|of|for)\\s+)?$branch").containsMatchIn(request)
    }

    fun requiresComparison(requirement: String): Boolean = matches(requirement) || Regex("(?i)head/base|base/head|changed files|correctness findings|file/line").containsMatchIn(requirement)

    /** Report the retained prerequisite failure without authorizing its replay. */
    fun blockedFailure(plan: Plan): ProviderEvent.Failed {
        val checkpoint = plan.checkpoint
        val needsInput = checkpoint["user_input_required"] == JsonPrimitive(true)
        val original = checkpoint["baseline_resolution_failure"] as? JsonObject
        val code = original?.text("error_code")?.takeIf { it in resolutionFailureCodes }
            ?: if (needsInput) "REVIEW_INPUT_REQUIRED" else "GITHUB_REVIEW_COMPARISON_REQUIRED"
        return ProviderEvent.Failed(
            checkpoint.text("detail"),
            errorCode = code,
            transportComplete = original == null || original["transport_complete"] == JsonPrimitive(true),
            taskOutcome = "BLOCKED",
            failureDetails = JsonObject(
                checkpoint + original.orEmpty() + buildJsonObject {
                    put("failed_stage", "review_comparison")
                    put("inference_dispatched", false)
                    // The review has not started, but its prerequisite read may
                    // already have reached GitHub. Retain that distinction.
                    if (original == null) {
                        put("dispatched", false)
                        put("dispatch_state", "NOT_DISPATCHED")
                        put("remote_acceptance", "NO")
                    }
                    put("retryable", false)
                    put("resume_allowed", false)
                    put("request_replay_allowed", false)
                    put("next_action", original?.text("next_action")?.takeIf { it in resolutionRecoveryActions } ?: if (needsInput) "SELECT_REVIEW_BASELINE" else "RESTORE_REVIEW_COMPARISON_EVIDENCE")
                }
            )
        )
    }

    private fun resolutionFailure(receipt: JsonObject): JsonObject {
        val code = receipt.text("error_code").takeIf { it in resolutionFailureCodes } ?: "GITHUB_OPERATION_FAILED"
        val data = GitHubSemanticSummary.data(receipt["result"] as? JsonObject ?: JsonObject(emptyMap()))
        val execution = receipt["execution"] as? JsonObject
        val hint = when (code) {
            "GITHUB_TRANSIENT_NETWORK", "DNS_RESOLUTION_FAILED", "NETWORK_TIMEOUT", "NETWORK_DISCONNECTED", "CONNECTION_ABORTED", "TRANSPORT_EOF" ->
                "Restore the selected connection and inspect the saved lookup and any recorded cooldown before an authorized read."
            "RATE_LIMITED" -> GitHubFailurePolicy.recoveryHint("GITHUB_PRIMARY_RATE_LIMITED")
            else -> GitHubFailurePolicy.recoveryHint(code)
        }
        return buildJsonObject {
            put("error_code", code)
            put("recovery_hint", hint)
            (execution?.get("transport_complete") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull?.let { put("transport_complete", it) }
            (receipt["dispatched"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull?.let {
                put("dispatched", it)
                put("dispatch_state", if (it) "DISPATCHED" else "NOT_DISPATCHED")
                if (!it) put("remote_acceptance", "NO")
            }
            for (key in listOf("http_status", "retry_after_ms", "retry_at_ms", "retry_not_before_ms")) {
                ((execution?.get(key) ?: data[key]) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                    ?.takeIf { if (key == "http_status") it in 100L..599L else it >= 0 }
                    ?.let { put(key, it) }
            }
            (execution?.get("phase") as? JsonPrimitive)?.contentOrNull?.takeIf { it in setOf("PREFLIGHT_REJECTED", "READ_FAILED", "OUTCOME_UNKNOWN", "PARTIAL_ACCEPTED") }?.let { put("resolution_dispatch_phase", it) }
            ((execution?.get("requires_reconciliation") ?: data["requires_reconciliation"]) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull?.let { put("requires_reconciliation", it) }
            val actions = ((data["recovery_actions"] ?: data["allowed_recovery_actions"]) as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { action -> action in resolutionRecoveryActions } }.distinct()
            if (actions.isNotEmpty()) put("recovery_actions", JsonArray(actions.map(::JsonPrimitive)))
            data.text("next_action").ifBlank { data.text("recovery_action") }.takeIf { it in resolutionRecoveryActions }?.let { put("next_action", it) }
        }
    }

    /** Status observations cannot satisfy a changed-source review acceptance criterion. */
    fun incompleteReason(task: String, receipts: Map<String, JsonObject>): String? {
        val checkpoint = plan(task, emptyList(), receipts)?.checkpoint ?: return null
        if (checkpoint["comparison_verified"] != JsonPrimitive(true)) return checkpoint.text("detail")
        val head = checkpoint.text("head_sha")
        val base = checkpoint.text("base_sha")
        val eligible = receipts.values.filter {
            it["trusted"] == JsonPrimitive(true) &&
                it["verification_eligible"] != JsonPrimitive(false) &&
                it["success"] == JsonPrimitive(true) &&
                it["repository_role"] != JsonPrimitive("READ_SOURCE") &&
                it.text("repository") == checkpoint.text("repository")
        }
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
        var targetRepository = ""
        fun targetReceipt(row: JsonObject) = row["repository_role"] != JsonPrimitive("READ_SOURCE") &&
            (targetRepository.isBlank() || row.text("repository") == targetRepository)
        fun call(operation: String, arguments: JsonObject): ProviderEvent.ToolCall? {
            val tool = tools.firstOrNull { it.name.substringBefore("__") == operation } ?: return null
            if (ToolArgumentValidator.errors(arguments, tool.inputSchema).isNotEmpty()) return null
            if (receipts.values.any { targetReceipt(it) && it["trusted"] == JsonPrimitive(true) && it["tool"] == JsonPrimitive(tool.name) && it["arguments"] == arguments && it["verification_eligible"] != JsonPrimitive(false) }) return null
            return ProviderEvent.ToolCall("review-base-${receipts.size}-${GitHubEvidenceBook.digest(arguments.toString()).take(8)}", tool.name, arguments)
        }
        fun state(code: String, detail: String, next: ProviderEvent.ToolCall? = null, failure: JsonObject? = null) = Plan(
            buildJsonObject {
                put("state", code)
                put("detail", detail)
                put("comparison_policy", policy)
                if (targetRepository.isNotBlank()) put("repository", targetRepository)
                if (head.isNotBlank()) put("head_sha", head)
                if (base.isNotBlank()) put("base_sha", base)
                put("comparison_verified", code == "READ_CHANGED_SOURCE")
                put("source_review_verified", false)
                put("user_input_required", code == "BASELINE_REQUIRED")
                failure?.let { put("baseline_resolution_failure", it) }
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
        targetRepository = context.text("repository")
        head = selected.text("commit_sha")
        val branch = selected.text("branch").ifBlank { context.text("workspace_branch").ifBlank { context.text("branch") } }
        val default = selected.text("default_branch").ifBlank { (selected["repository"] as? JsonObject)?.text("default_branch").orEmpty() }
        val requestedBase = explicitBase.findAll(request(task)).map { it.groupValues[1].trimEnd('.') }
            .firstOrNull { it.lowercase(java.util.Locale.ROOT) !in setOf("its", "the", "a", "an", "this", "that", "selected", "current", "base", "baseline", "branch", "tag", "commit", "sha", "ref", "default") }
        if (requestedBase != null) {
            policy = if (sha.matches(requestedBase)) "EXPLICIT_BASE_SHA" else "EXPLICIT_BASE_REF"
            if (sha.matches(requestedBase)) {
                base = requestedBase.lowercase(java.util.Locale.ROOT)
            } else {
                val baseline = eligible.lastOrNull {
                    targetReceipt(it) &&
                        operation(it) == "github_context" &&
                        args(it).text("action") == "resolve" &&
                        args(it).text("ref") == requestedBase &&
                        sha.matches(data(it).text("commit_sha"))
                }
                if (baseline == null) {
                    val arguments = buildJsonObject {
                        put("action", "resolve")
                        put("ref", requestedBase)
                    }
                    val rejected = receipts.values.lastOrNull {
                        targetReceipt(it) &&
                            operation(it) == "github_context" &&
                            args(it) == arguments &&
                            it["trusted"] == JsonPrimitive(true) &&
                            it["verification_eligible"] != JsonPrimitive(false) &&
                            it["success"] == JsonPrimitive(false)
                    }
                    if (rejected != null) {
                        val failure = resolutionFailure(rejected)
                        val invalidInput = failure.text("error_code") in setOf("INVALID_ARGUMENTS", "GITHUB_VALIDATION_FAILED")
                        return state(
                            if (invalidInput) "BASELINE_REQUIRED" else "BASE_REF_BLOCKED",
                            "The baseline lookup failed (${failure.text("error_code")}). ${failure.text("recovery_hint")} " +
                                (if (invalidInput) "Correct the lookup or send an updated review request with Baseline: <commit, tag or branch>. " else "The requested baseline remains unresolved. ") +
                                "Saved observations remain retained.",
                            failure = failure
                        )
                    }
                    return state("BASE_REF_REQUIRED", "Resolve the explicitly supplied baseline to an immutable commit before reviewing.", call("github_context", arguments))
                }
                base = data(baseline).text("commit_sha")
            }
        }
        if (base.isBlank()) {
            val pulls = eligible.filter { targetReceipt(it) && operation(it) == "github_pr" && args(it).text("action") in setOf("list", "headers", "context") }
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
                val pending = GitHubTestChecklist.pendingPages(receipts.filterValues { targetReceipt(it) && operation(it) == "github_pr" && args(it).text("action") == "list" }).firstOrNull()
                if (pending != null) return state("PR_BASE_LOOKUP", "Finish the exact retained PR inventory page before excluding a PR base.", (pending["arguments"] as? JsonObject)?.let { call(pending.text("tool").substringBefore("__"), it) })
                if (inventory.none { data(it)["has_more"] == JsonPrimitive(false) }) return state("PR_BASE_UNVERIFIED", "PR inventory completeness is unverified; reuse its exact continuation or establish the intended baseline.")
                if (branch.isBlank() || default.isBlank()) return state("BASELINE_REQUIRED", "The selected/default branch identity is incomplete. Choose the baseline for the captured head.")
                if (branch == default) return state("BASELINE_REQUIRED", "The selected branch is also the default branch ($branch). Which commit or tag should be the review baseline? Send the review request again with Baseline: <commit, tag or branch>. Saved observations remain retained.")
                policy = "DEFAULT_BRANCH_HEAD"
                val baseline = eligible.lastOrNull { targetReceipt(it) && operation(it) == "github_context" && args(it).text("action") == "resolve" && args(it).text("ref") == default && sha.matches(data(it).text("commit_sha")) }
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
        val comparison = eligible.lastOrNull { targetReceipt(it) && operation(it) == "github_context" && args(it).text("action") == "changes" && data(it).text("base_sha") == base && data(it).text("head_sha") == head }
        if (comparison != null) {
            val pending = GitHubTestChecklist.pendingPages(receipts.filterValues { targetReceipt(it) && operation(it) == "github_context" && args(it).text("action") == "changes" && data(it).text("base_sha") == base && data(it).text("head_sha") == head }).firstOrNull()
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
