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

    // A selector is a separate request token, never the base component of
    // head/base prose or a path/ref such as feature/base. Require a delimiter
    // after it too, so base/head cannot authorize a lookup of /head.
    private val baselineSelector = "(?<![\\p{L}\\p{N}_./+@-])(?:baseline|base|against)(?=[ \\t]|[:=])[ \\t]*(?:(?:sha|commit|branch|tag|ref)(?=[ \\t]|[:=])[ \\t]*)?"
    private val baselinePrefix = "$baselineSelector[:=]?[ \\t]*"
    private val explicitBase = Regex("(?i)$baselinePrefix([^\\s]+)")
    private val labelledBase = Regex("(?i)$baselineSelector[:=][ \\t]*([^\\r\\n]*)")
    private val quotedBaselinePrefix = Regex("(?i)$baselinePrefix$")
    private val literalRef = Regex("[A-Za-z0-9_][A-Za-z0-9._/+@-]{0,254}")

    fun acceptsBaselineInput(text: String): Boolean = literalRef.matches(text.trim())
    private val quotedMaterial = Regex("(?s)```.*?(?:```|\\z)|~~~.*?(?:~~~|\\z)|`[^`\\r\\n]*`|\"(?:\\\\.|[^\"\\\\])*\"|“[^”]*”|‘[^’]*’|(?<![\\p{L}\\p{N}])'[^'\\r\\n]*'")
    private val resolutionFailureCodes = GitHubFailurePolicy.codes + setOf(
        "GITHUB_CIRCUIT_OPEN", "GITHUB_TRANSIENT_NETWORK", "GITHUB_REF_UNAVAILABLE", "GITHUB_OPERATION_FAILED",
        "GITHUB_TLS_FAILURE", "GITHUB_UNEXPECTED_RESPONSE", "GITHUB_RESPONSE_TOO_LARGE", "GITHUB_NOT_FOUND_OR_HIDDEN",
        "DNS_RESOLUTION_FAILED", "NETWORK_TIMEOUT", "NETWORK_DISCONNECTED", "CONNECTION_ABORTED", "TRANSPORT_EOF", "TLS_FAILURE",
        "RATE_LIMITED", "AUTHENTICATION_FAILED", "ACCESS_FORBIDDEN", "RECONCILIATION_REQUIRED", "TOOL_OUTCOME_UNKNOWN"
    )
    private val resolutionRecoveryActions = setOf("REFRESH_REPOSITORY_ACCESS", "CHANGE_REPOSITORY", "SELECT_REF", "RETRY_AFTER_CONFIGURATION_CHANGE")
    const val CORE_INSTRUCTION = "Branch review: pin exact base_sha/head_sha. Resolve a supplied baseline to SHA. Else use an observed matching PR base or default-branch SHA for a non-default selection. On default without baseline, ask; never infer a self-diff. Run github_context action=changes; read short changed code/matching test ranges at both SHAs. Resume source coverage's first unread exact range; retain requested lines and completed evidence. Bound guidance separately outside code/test allowance. Checks/inventories are not source review."
    const val DETAIL_INSTRUCTION = "Resolve every commit, tag or branch to an exact SHA. Never infer an empty diff by comparing a branch to itself. Start with short changed implementation and matching test ranges around affected symbols. Consult source coverage before subsequent reads. Keep guidance in a separate bounded request. Checks and branch inventories do not constitute a source review."
    const val INSTRUCTION = CORE_INSTRUCTION + " " + DETAIL_INSTRUCTION
    data class Plan(val checkpoint: JsonObject, val nextCall: ProviderEvent.ToolCall?)
    private data class BaselineSelection(val ref: String? = null, val invalid: Boolean = false)

    private fun request(task: String): String {
        val request = GitHubTaskContinuity.request(task)
        return request.replace(quotedMaterial) { quoted ->
            // Only a literal argument immediately following the request's baseline
            // selector survives quoting. Code/logs and quoted instructions do not.
            val literal = quoted.value.drop(1).dropLast(1)
            if (quotedBaselinePrefix.containsMatchIn(request.substring(0, quoted.range.first))) {
                // Keep an invalid selector visible to the parser so it cannot
                // silently authorize a different PR/default-branch comparison.
                literal.takeIf(literalRef::matches) ?: "[invalid-baseline]"
            } else {
                " "
            }
        }.lineSequence().filterNot { it.trimStart().startsWith(">") }.joinToString("\n")
    }

    private fun baseline(task: String): BaselineSelection {
        val request = request(task)
        val labelled = labelledBase.findAll(request).toList()
        // A labelled field owns its complete line. Extra tokens, missing values
        // and unsupported literals require correction rather than a guessed ref.
        val values = labelled.map { it.groupValues[1].trim() } + explicitBase.findAll(request)
            .filter { candidate -> labelled.none { candidate.range.first in it.range } }
            .map { it.groupValues[1].trimEnd('.') }
            .filter { it.isNotBlank() && it.lowercase(java.util.Locale.ROOT) !in setOf("its", "the", "a", "an", "this", "that", "selected", "current", "base", "baseline", "branch", "tag", "commit", "sha", "ref", "default") }
            .toList()
        if (values.isEmpty()) return BaselineSelection()
        if (values.any { !literalRef.matches(it) }) return BaselineSelection(invalid = true)
        val refs = values.distinct()
        return if (refs.size == 1) BaselineSelection(refs.single()) else BaselineSelection(invalid = true)
    }

    fun matches(task: String): Boolean {
        val request = request(task)
        val branch = "(?:(?:the|this|that|my|our|a|selected|current|feature|target|source|entire|latest|pr|pull request)\\s+){0,4}branch\\b"
        return "[github-workflow-preset:review_branch]" in request ||
            Regex("(?i)\\breview(?:\\s+and\\s+(?:fix|audit|inspect))?\\s+(?:(?:(?:all|the)\\s+){0,2}(?:changes|diff|code(?:/source)?|source(?:\\s+code)?)\\s+(?:on|in|of|for)\\s+)?$branch").containsMatchIn(request)
    }

    fun requiresComparison(requirement: String): Boolean = matches(requirement) || comparisonOnly(requirement) || Regex("(?i)head/base|base/head|changed files|correctness findings|file/line").containsMatchIn(requirement)

    /** Resolving the pair/file inventory is a prerequisite, not a source inspection. */
    fun comparisonOnly(requirement: String): Boolean = Regex(
        "(?i)(?:inspect|resolve|establish|identify|compare|report) (?:the )?(?:exact )?(?:head/base|base/head|head and base|base and head) (?:revisions|commits)(?: and (?:the )?changed files)?[.!]?"
    ).matches(requirement.trim())

    fun comparisonIncompleteReason(task: String, receipts: Map<String, JsonObject>): String? =
        plan(task, emptyList(), receipts)?.checkpoint?.takeIf { it["comparison_verified"] != JsonPrimitive(true) }?.text("detail")

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
        val originalCode = receipt.text("error_code").takeIf { it in resolutionFailureCodes }
        val code = originalCode ?: "GITHUB_OPERATION_FAILED"
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
            receipt.text("call_id").takeIf(String::isNotBlank)?.let { callId ->
                put("causal_id", callId)
                put("causal_provenance", "TOOL_CALL")
                originalCode?.let { put("causal_failure_code", it) }
            }
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
                it.text("repository") == checkpoint.text("repository") &&
                (checkpoint["binding"] == null || it["binding"] == checkpoint["binding"])
        }
        fun data(row: JsonObject) = GitHubSemanticSummary.data(row["result"] as? JsonObject ?: JsonObject(emptyMap()))
        val comparisons = eligible.filter { row ->
            row.text("tool").githubOperationFamily() == "github_context" && (row["arguments"] as? JsonObject)?.text("action") == "changes" && data(row).text("base_sha") == base && data(row).text("head_sha") == head
        }
        val changed = comparisons.flatMap { (data(it)["files"] as? JsonArray).orEmpty() }.filterIsInstance<JsonObject>().flatMap { file ->
            listOfNotNull(file.text("filename").takeIf(String::isNotBlank), file.text("previous_filename").takeIf { it.isNotBlank() && file.text("status") == "renamed" })
        }.toSet()
        if (changed.isEmpty() && comparisons.any { (data(it)["files_total"] as? JsonPrimitive)?.intOrNull == 0 && data(it)["has_more"] != JsonPrimitive(true) }) return null
        val coverage = checkpoint["comparison_coverage"] as? JsonObject
        val sourceRead = ((coverage?.get("changed_files_with_source_observation") as? JsonPrimitive)?.intOrNull ?: 0) > 0
        return if (sourceRead) null else "The exact comparison is retained, but no relevant changed-source read at the pinned base/head is recorded. Checks and inventories cannot verify a source review."
    }

    fun plan(task: String, tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>, authorizedTools: List<AgentToolDefinition> = tools, acquireSource: Boolean = true): Plan? {
        if (!matches(task)) return null
        val eligible = receipts.values.filter { it["trusted"] == JsonPrimitive(true) && it["verification_eligible"] != JsonPrimitive(false) && it["success"] == JsonPrimitive(true) }
        fun operation(row: JsonObject) = row.text("tool").githubOperationFamily()
        fun args(row: JsonObject) = row["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        fun data(row: JsonObject) = GitHubSemanticSummary.data(row["result"] as? JsonObject ?: JsonObject(emptyMap()))
        fun lookupMatches(row: JsonObject, ref: String): Boolean {
            val arguments = args(row)
            // Match the client's selector precedence; a ref field cannot vouch
            // for a different commit_sha or an unrelated saved response.
            val selected = arguments.text("commit_sha").ifBlank { arguments.text("ref").ifBlank { arguments.text("branch") } }
            return operation(row) == "github_context" && arguments.text("action") in setOf("commit", "resolve") && "view_cursor" !in arguments && selected == ref
        }
        var head = ""
        var base = ""
        var policy = "UNRESOLVED"
        var targetRepository = ""
        var targetBinding: String? = null
        var targetConnection: String? = null
        var headReceiptId: String? = null
        var baselineDecisionReceiptId: String? = null
        var comparisonCoverage: GitHubComparisonCoverage.Coverage? = null
        var sourceWork: GitHubReviewSourceQueue.Work? = null
        fun targetReceipt(row: JsonObject) = row["repository_role"] != JsonPrimitive("READ_SOURCE") &&
            (targetRepository.isBlank() || row.text("repository") == targetRepository) &&
            (targetBinding == null || row.text("binding") == targetBinding)
        fun call(operation: String, arguments: JsonObject, issuedToolName: String? = null): ProviderEvent.ToolCall? {
            // A retained page belongs to its issued facade and connection, even
            // when another family member accepts the same arguments.
            val candidates = if (issuedToolName == null) githubFamilyTools(tools, operation).filter { targetConnection == null || it.name.substringAfter("__", "") == targetConnection } else tools.filter { it.modelVisible && it.name == issuedToolName }
            val tool = candidates.firstOrNull { ToolArgumentValidator.errors(arguments, it.inputSchema).isEmpty() }
            if (tool == null) {
                val discovery = tools.singleOrNull { it.modelVisible && it.name == GitHubAuthorizedCatalog.TOOL } ?: return null
                val family = authorizedTools.filter { it.modelVisible && it.name.githubOperationFamily() == operation }
                val connection = (issuedToolName ?: candidates.firstOrNull()?.name)?.substringAfter("__", "") ?: targetConnection
                    ?: family.map { it.name.substringAfter("__", "") }.distinct().singleOrNull() ?: return null
                val exact = family.firstOrNull {
                    it.name.substringAfter("__", "") == connection &&
                        (issuedToolName == null || it.name == issuedToolName) &&
                        ToolArgumentValidator.errors(arguments, it.inputSchema).isEmpty()
                } ?: return null
                if (receipts.values.any { targetReceipt(it) && it["trusted"] == JsonPrimitive(true) && it.text("tool") == exact.name && it["arguments"] == arguments && it["verification_eligible"] != JsonPrimitive(false) }) return null
                val acquire = buildJsonObject {
                    put("names", JsonArray(listOf(JsonPrimitive(exact.name))))
                    val action = arguments.text("action")
                    if (dev.chungjungsoo.gptmobile.data.agent.GitHubActionSchema.project(exact, action) != null) put("action", action)
                }
                if (ToolArgumentValidator.errors(acquire, discovery.inputSchema).isNotEmpty() ||
                    receipts.values.any { it.text("tool") == discovery.name && it["arguments"] == acquire && it["verification_eligible"] != JsonPrimitive(false) }
                ) {
                    return null
                }
                return ProviderEvent.ToolCall("review-discovery-${receipts.size}", discovery.name, acquire)
            }
            if (receipts.values.any { targetReceipt(it) && it["trusted"] == JsonPrimitive(true) && it["tool"] == JsonPrimitive(tool.name) && it["arguments"] == arguments && it["verification_eligible"] != JsonPrimitive(false) }) return null
            return ProviderEvent.ToolCall("review-base-${receipts.size}-${GitHubEvidenceBook.digest(arguments.toString()).take(8)}", tool.name, arguments)
        }
        fun state(code: String, detail: String, next: ProviderEvent.ToolCall? = null, failure: JsonObject? = null, observation: String? = null) = Plan(
            buildJsonObject {
                put("state", code)
                put("detail", detail)
                put("comparison_policy", policy)
                observation?.let { put("github_prerequisite_observation", it) }
                if (targetRepository.isNotBlank()) put("repository", targetRepository)
                targetBinding?.let { put("binding", it) }
                if (head.isNotBlank()) put("head_sha", head)
                if (base.isNotBlank()) put("base_sha", base)
                headReceiptId?.let { put("head_observation_receipt_id", it) }
                baselineDecisionReceiptId?.let { put("baseline_decision_receipt_id", it) }
                comparisonCoverage?.let {
                    val ids = it.receipts.keys.toList()
                    put("comparison_receipt_ids", JsonArray((ids.take(1) + ids.takeLast(1)).distinct().map(::JsonPrimitive)))
                    put("comparison_receipt_count", ids.size)
                    put("comparison_receipt_preview_complete", ids.size <= 2)
                }
                put("comparison_verified", code == "READ_CHANGED_SOURCE")
                put("source_review_verified", false)
                put("user_input_required", code == "BASELINE_REQUIRED")
                sourceWork?.let { put("source_work_queue", it.checkpoint) }
                if (code in setOf("COMPARISON_PAGES_PENDING", "READ_CHANGED_SOURCE")) {
                    val changedFiles = (comparisonCoverage?.receipts?.values ?: eligible.filter { targetReceipt(it) && operation(it) == "github_context" && args(it).text("action") == "changes" && data(it).text("base_sha") == base && data(it).text("head_sha") == head })
                        .flatMap { (data(it)["files"] as? JsonArray).orEmpty() }.filterIsInstance<JsonObject>()
                    val changed = changedFiles.mapNotNull { it.text("filename").takeIf(String::isNotBlank) }.toSet()
                    val readPaths = eligible.filter { targetReceipt(it) && operation(it) == "github_read" && data(it).text("commit_sha") in setOf(base, head) }
                        .groupBy { data(it).text("commit_sha") }
                        .mapValues { (_, rows) -> rows.flatMapTo(mutableSetOf(), GitHubCriterionAcceptance::observedSourcePaths) }
                    val headPaths = readPaths[head].orEmpty()
                    val basePaths = readPaths[base].orEmpty()
                    val read = changedFiles.filter { file ->
                        val path = file.text("filename")
                        val status = file.text("status")
                        val basePath = if (status == "renamed") file.text("previous_filename") else path
                        status != "removed" && path in headPaths || status != "added" && basePath in basePaths
                    }.mapTo(mutableSetOf()) { it.text("filename") }
                    val pending = changed - read
                    put(
                        "comparison_coverage",
                        buildJsonObject {
                            put("pages_complete", code == "READ_CHANGED_SOURCE")
                            comparisonCoverage?.let { coverage ->
                                put("identity_sha256", coverage.identity)
                                put("generation", coverage.generation)
                            }
                            put("changed_files_observed", changed.size)
                            put("changed_files_with_source_observation", (changed intersect read).size)
                            put("changed_files_without_source_observation", pending.size)
                            put("pending_source_paths", JsonArray(pending.filter { it.length <= 384 }.take(16).map(::JsonPrimitive)))
                            put("pending_source_preview_complete", pending.size <= 16 && pending.all { it.length <= 384 })
                            put("source_review_verified", false)
                        }
                    )
                }
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
        fun blockedResolution(arguments: JsonObject): Plan? {
            // A read-only lookup must not replay a failed legacy selection call.
            val rejected = receipts.values.lastOrNull {
                targetReceipt(it) &&
                    lookupMatches(it, arguments.text("ref")) &&
                    it["trusted"] == JsonPrimitive(true) &&
                    it["verification_eligible"] != JsonPrimitive(false) &&
                    it["success"] == JsonPrimitive(false)
            } ?: return null
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
        val context = eligible.lastOrNull {
            operation(it) == "github_context" &&
                args(it).text("action") == "status" &&
                sha.matches(data(it).text("commit_sha")) &&
                it["repository_role"] != JsonPrimitive("READ_SOURCE") &&
                (it.text("workspace_branch").isBlank() || data(it).text("branch") == it.text("workspace_branch"))
        }
        if (context == null) {
            val arguments = buildJsonObject { put("action", "status") }
            val next = call("github_context", arguments)
            if (next != null) return state("HEAD_REQUIRED", "Resolve the selected branch and default branch before comparing.", next)
            val candidates = githubFamilyTools(tools, "github_context")
            val selectedTool = candidates.firstOrNull { ToolArgumentValidator.errors(arguments, it.inputSchema).isEmpty() }
            val prior = receipts.values.filter {
                targetReceipt(it) &&
                    it["trusted"] == JsonPrimitive(true) &&
                    it["verification_eligible"] != JsonPrimitive(false) &&
                    it.text("tool") == selectedTool?.name &&
                    args(it) == arguments
            }
            val failed = prior.takeIf { rows -> rows.map { it.text("repository") to it.text("workspace_branch") }.distinct().size == 1 }
                ?.lastOrNull()?.takeIf { it["success"] == JsonPrimitive(false) }
            val authorizedContext = authorizedTools.filter { it.modelVisible && it.name.githubOperationFamily() == "github_context" }
            val observation = when {
                candidates.isEmpty() && authorizedContext.isEmpty() -> "STATUS_TOOL_NOT_EXPOSED"
                candidates.isEmpty() && tools.none { it.modelVisible && it.name == GitHubAuthorizedCatalog.TOOL } -> "STATUS_DISCOVERY_UNAVAILABLE"
                candidates.isEmpty() && authorizedContext.map { it.name.substringAfter("__", "") }.distinct().size > 1 -> "STATUS_CONNECTION_AMBIGUOUS"
                candidates.isEmpty() -> "STATUS_ACQUISITION_UNAVAILABLE"
                selectedTool == null -> "STATUS_ARGUMENTS_UNAVAILABLE"
                failed != null -> "STATUS_READ_FAILED"
                else -> "STATUS_RECEIPT_UNUSABLE"
            }
            return state("HEAD_REQUIRED", "The selected branch head could not be verified from repository status.", failure = failed?.let(::resolutionFailure), observation = observation)
        }
        val selected = data(context)
        targetRepository = context.text("repository")
        targetBinding = context.text("binding").takeIf(String::isNotBlank)
        targetConnection = context.text("tool").substringAfter("__", "")
        headReceiptId = receipts.entries.lastOrNull { it.value == context }?.key
        head = selected.text("commit_sha")
        val branch = selected.text("branch").ifBlank { context.text("workspace_branch").ifBlank { context.text("branch") } }
        val default = selected.text("default_branch").ifBlank { (selected["repository"] as? JsonObject)?.text("default_branch").orEmpty() }
        val selection = baseline(task)
        if (selection.invalid) return state("BASELINE_REQUIRED", "The supplied baseline is missing, unsupported or ambiguous. Send one literal baseline on its own line: Baseline: <commit, tag or branch>. Saved observations remain retained.")
        val choiceId = "review-baseline-" + GitHubEvidenceBook.digest("$task|$targetRepository|$head").take(24)
        val choiceEntry = receipts.entries.lastOrNull { (_, row) ->
            targetReceipt(row) &&
                row.text("tool") == "github_decide" &&
                row.text("call_id") == choiceId &&
                row["shared_result"] == JsonPrimitive(true) &&
                row["dispatched"] == JsonPrimitive(false)
        }
        val choice = choiceEntry?.value
        if (selection.ref == null) baselineDecisionReceiptId = choiceEntry?.key
        val requestedBase = selection.ref ?: choice?.let {
            // A local decision supplies a selector only. The comparison and its evidence still require GitHub receipts.
            val result = data(it)
            if (it["success"] != JsonPrimitive(true) || result["cancelled"] != JsonPrimitive(false)) return state("BASELINE_REQUIRED", "Baseline selection stopped. The review observations are saved.")
            val answer = result.text("user_answer")
            val options = (args(it)["options"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            val parent = options.singleOrNull { option -> option.text("label") == answer && option.text("label").startsWith("Before this commit") }
                ?.text("detail")?.takeIf(sha::matches)
            parent ?: answer.takeIf(literalRef::matches) ?: return state("BASELINE_REQUIRED", "Select one commit, tag or branch to continue the saved review.")
        }
        if (requestedBase != null) {
            policy = if (sha.matches(requestedBase)) "EXPLICIT_BASE_SHA" else "EXPLICIT_BASE_REF"
            if (sha.matches(requestedBase)) {
                base = requestedBase.lowercase(java.util.Locale.ROOT)
            } else {
                val baseline = eligible.lastOrNull {
                    targetReceipt(it) &&
                        lookupMatches(it, requestedBase) &&
                        sha.matches(data(it).text("commit_sha"))
                }
                if (baseline == null) {
                    val arguments = buildJsonObject {
                        // Inspect the baseline without selecting it as the workspace.
                        put("action", "commit")
                        put("ref", requestedBase)
                    }
                    blockedResolution(arguments)?.let { return it }
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
                // A review of the selected default branch has no implied comparison base.
                // Ask immediately instead of consuming the user's tool budget paging an
                // unrelated PR inventory. A previously verified, exactly matching PR
                // above is still eligible to supply its observed base SHA.
                if (branch.isNotBlank() && default.isNotBlank() && branch == default) {
                    val observedHead = eligible.lastOrNull {
                        targetReceipt(it) &&
                            operation(it) == "github_context" &&
                            args(it).text("action") == "commit" &&
                            data(it).text("commit_sha").equals(head, ignoreCase = true)
                    }
                    val parent = (observedHead?.let(::data)?.get("parents") as? JsonArray)?.firstOrNull()
                        ?.let { it as? JsonObject }?.text("sha")?.takeIf { sha.matches(it) && !it.equals(head, ignoreCase = true) }
                    val decision = tools.singleOrNull { it.name == "github_decide" && it.modelVisible && dev.chungjungsoo.gptmobile.data.agent.AgentToolCapability.USER_DECISION in it.capabilities }
                    if (decision != null) {
                        if (observedHead == null) {
                            val lookup = call(
                                "github_context",
                                buildJsonObject {
                                    put("action", "commit")
                                    put("ref", head)
                                }
                            )
                            if (lookup != null) return state("HEAD_REQUIRED", "Checking the parent of the pinned revision.", lookup)
                        }
                        val question = "Which baseline should this review compare with ${head.take(12)}?"
                        val options = listOf(
                            buildJsonObject {
                                if (parent == null) {
                                    put("label", "Enter a commit")
                                    put("enter_value", true)
                                } else {
                                    put("label", "Before this commit (${parent.take(12)})")
                                    put("detail", parent)
                                }
                            },
                            buildJsonObject {
                                put("label", "Choose a tag or branch")
                                put("enter_value", true)
                            }
                        )
                        val arguments = buildJsonObject {
                            put("question", question)
                            put("context", "$targetRepository · $branch. The review will continue from its saved evidence.")
                            put("options", JsonArray(options))
                        }
                        return state("BASELINE_REQUIRED", question, ProviderEvent.ToolCall(choiceId, decision.name, arguments))
                    }
                    val suggestion = parent?.let { " For a one-commit review, you can select the observed parent with Baseline: $it." }.orEmpty()
                    return state("BASELINE_REQUIRED", "The selected branch is also the default branch ($branch). Send the review request again with Baseline: <commit, tag or branch>.$suggestion Saved observations remain retained.")
                }
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
                val pending = GitHubTestChecklist.pendingPages(GitHubTestChecklist.paginationReceipts(receipts) { targetReceipt(it) && operation(it) == "github_pr" && args(it).text("action") == "list" }).firstOrNull()
                if (pending != null) return state("PR_BASE_LOOKUP", "Finish the exact retained PR inventory page before excluding a PR base.", (pending["arguments"] as? JsonObject)?.let { call(pending.text("tool").githubOperationFamily(), it, issuedToolName = pending.text("tool")) })
                if (inventory.none { data(it)["has_more"] == JsonPrimitive(false) }) return state("PR_BASE_UNVERIFIED", "PR inventory completeness is unverified; reuse its exact continuation or establish the intended baseline.")
                if (branch.isBlank() || default.isBlank()) return state("BASELINE_REQUIRED", "The selected/default branch identity is incomplete. Choose the baseline for the captured head.")
                policy = "DEFAULT_BRANCH_HEAD"
                val baseline = eligible.lastOrNull { targetReceipt(it) && lookupMatches(it, default) && sha.matches(data(it).text("commit_sha")) }
                if (baseline == null) {
                    val arguments = buildJsonObject {
                        put("action", "commit")
                        put("ref", default)
                    }
                    blockedResolution(arguments)?.let { return it }
                    return state(
                        "BASE_SHA_REQUIRED",
                        "Resolve the default branch to an exact immutable comparison SHA.",
                        call("github_context", arguments)
                    )
                }
                base = data(baseline).text("commit_sha")
            }
        }
        comparisonCoverage = GitHubComparisonCoverage.observe(receipts) { targetReceipt(it) && operation(it) == "github_context" && args(it).text("action") == "changes" && data(it).text("base_sha") == base && data(it).text("head_sha") == head }
        comparisonCoverage?.let { coverage ->
            val pending = coverage.pending.firstOrNull()
            if (pending != null) return state("COMPARISON_PAGES_PENDING", "The comparison has unread changed-file pages; retain the exact pair while following its continuation.", (pending["arguments"] as? JsonObject)?.let { call(pending.text("tool").githubOperationFamily(), it, issuedToolName = pending.text("tool")) })
            if (!coverage.complete) return state("COMPARISON_PAGES_PENDING", "The comparison has unread or unverified changed-file pages; restore its exact continuation before asserting coverage.")
            sourceWork = GitHubReviewSourceQueue.plan(task, coverage.receipts, receipts, base, head, targetRepository, targetBinding)
            val sourceCall = sourceWork.recipes.takeIf { acquireSource }?.firstNotNullOfOrNull { recipe ->
                val arguments = recipe["arguments"] as? JsonObject ?: return@firstNotNullOfOrNull null
                call("github_read", arguments, issuedToolName = recipe.text("tool").takeIf { "view_cursor" in arguments })
            }
            return state("READ_CHANGED_SOURCE", "The exact comparison is retained. Inspect its changed files and relevant source at the pinned base/head before reporting correctness findings.", sourceCall)
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
