package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Receipt-derived checkpoints survive restarts without replaying completed writes. */
internal object GitHubTestChecklist {
    private data class Capability(val tool: String, val action: String, val scope: String, val automatic: Boolean = false)
    private val capabilities = listOf(
        Capability("github_context", "status", "Repository status", true),
        Capability("repository_graph", "status", "Engineering gateway graph status", true),
        Capability("github_context", "checks", "Checks for selected SHA", true),
        Capability("github_context", "branches", "Branch inventory", true),
        Capability("github_context", "commits", "Recent commits", true),
        Capability("github_pr", "list", "Pull request inventory", true),
        Capability("github_ci", "workflows", "Workflow inventory", true),
        Capability("github_ci", "runs", "Recent workflow runs", true),
        Capability("github_search", "", "Path search"),
        Capability("github_read", "", "Pinned source read"),
        Capability("github_commit", "branch", "Isolated branch creation"),
        Capability("github_stage_patch", "", "Patch staging"),
        Capability("github_commit", "commit", "Commit publication"),
        Capability("github_pr", "create", "Draft PR creation"),
        Capability("github_ci", "dispatch", "Workflow dispatch"),
        Capability("github_ci", "rerun", "Workflow rerun"),
        Capability("github_ci", "cancel", "Workflow cancellation"),
        Capability("github_pr", "merge", "PR merge"),
        Capability("github_commit", "delete_branch", "Fixture cleanup")
    )

    fun integration(task: String): Boolean = !isGatewayIntegrationTask(task) && GitHubTaskCoverage.matches(task) && (Regex("(?i)\\b(?:tools?|integration|functionality)\\b").containsMatchIn(task) || task.trim().matches(Regex("(?i)(?:please\\s+)?test\\s+all[.!]?")))
    private fun trusted(receipts: Map<String, JsonObject>) = receipts.values.filter { it["trusted"] == JsonPrimitive(true) && it["verification_eligible"] != JsonPrimitive(false) }
    private fun matches(receipt: JsonObject, capability: Capability): Boolean = receipt.text("tool").substringBefore("__") == capability.tool &&
        (receipt["arguments"] as? JsonObject)?.containsKey("view_cursor") != true &&
        (capability.action.isBlank() || (receipt["arguments"] as? JsonObject)?.text("action") == capability.action)

    /** Follow only exact issued read recipes. Local fragments and remote pages are independent. */
    fun pendingPages(receipts: Map<String, JsonObject>): List<JsonObject> {
        val observations = trusted(receipts)
        val completed = observations.filter { it["success"] == JsonPrimitive(true) }.map { it.text("tool") to it["arguments"] }.toSet()
        val recovered = observations.mapIndexedNotNull { index, receipt ->
            if (receipt["success"] == JsonPrimitive(true) || receipt.text("error_code") !in setOf("SAVED_VIEW_UNAVAILABLE", "SAVED_VIEW_CURSOR_ALTERED", "SAVED_VIEW_WRONG_OPERATION")) return@mapIndexedNotNull null
            val root = data(receipt) ?: return@mapIndexedNotNull null
            val recovery = (root["correction_call"] ?: root["recovery_call"] ?: root["source_recovery_call"]) as? JsonObject ?: return@mapIndexedNotNull null
            val recoveryArgs = recovery["arguments"] as? JsonObject ?: return@mapIndexedNotNull null
            if (!dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isReplayableRead(recovery.text("tool"), recoveryArgs)) return@mapIndexedNotNull null
            if (observations.drop(index + 1).none { it["success"] == JsonPrimitive(true) && it.text("tool") == recovery.text("tool") && it["arguments"] == recovery["arguments"] }) return@mapIndexedNotNull null
            receipt.text("tool") to receipt["arguments"]
        }.toSet()
        return observations.flatMap(::issuedPages).distinct().filter { it.text("tool") to it["arguments"] !in completed + recovered }
    }

    private fun issuedPages(receipt: JsonObject): List<JsonObject> {
        if (receipt["success"] != JsonPrimitive(true)) return emptyList()
        val view = (receipt["view"] as? JsonObject) ?: (receipt["result"] as? JsonObject) ?: return emptyList()
        return buildList {
            // Complete original data satisfies display traversal, never remote pagination.
            if (receipt["retained_result_complete"] == JsonPrimitive(true) && receipt["result_sha256"] is JsonPrimitive && receipt["retained_remote_continuations"] is JsonArray) {
                (receipt["retained_remote_continuations"] as JsonArray).forEach { entry -> ((entry as? JsonObject)?.get("call") as? JsonObject)?.let(::add) }
            } else {
                (view["continuation_call"] as? JsonObject)?.let(::add)
                if (view["local_page_complete"] != JsonPrimitive(false)) {
                    (view["remote_continuations"] as? JsonArray).orEmpty().forEach { entry -> ((entry as? JsonObject)?.get("call") as? JsonObject)?.let(::add) }
                }
            }
        }
    }

    private fun remoteSamples(observations: List<JsonObject>): Int {
        val issued = observations.flatMap(::issuedPages).map { it.text("tool") to it["arguments"] }.toSet()
        return observations.count { receipt ->
            val args = receipt["arguments"] as? JsonObject ?: return@count false
            "view_cursor" !in args &&
                !(receipt.text("tool").substringBefore("__") == "github_ci" && args.text("action") == "definitions" && "index_cursor" in args) &&
                ((args["page"] as? JsonPrimitive)?.longOrNull?.let { it > 1 } == true || receipt.text("tool") to args in issued)
        }
    }

    /** Consume issued local fragments while their bounded cache entry is still live. */
    private fun continuation(task: String, tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>, localOnly: Boolean): ProviderEvent.ToolCall? {
        val observations = trusted(receipts)
        fun same(receipt: JsonObject, recipe: JsonObject) = receipt.text("tool") == recipe.text("tool") && receipt["arguments"] == recipe["arguments"]
        fun permitted(recipe: JsonObject): Boolean {
            val args = recipe["arguments"] as? JsonObject ?: return false
            val name = recipe.text("tool")
            val graphWrite = name.substringBefore("__") == "repository_graph" && args.text("action") in setOf("index", "note")
            return !graphWrite &&
                tools.any { it.modelVisible && it.name == name && dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(args, it.inputSchema).isEmpty() } &&
                dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isReplayableRead(name, args) &&
                !dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isBranchSelection(name, args)
        }
        val exhaustive = GitHubChecklistScope.exhaustive(task)
        val sampled = remoteSamples(observations)
        for (page in pendingPages(receipts)) {
            val args = page["arguments"] as? JsonObject ?: continue
            if (localOnly && "view_cursor" !in args || !permitted(page)) continue
            val priorIndex = observations.indexOfLast { same(it, page) }
            if (priorIndex >= 0) {
                val failed = observations[priorIndex]
                if (failed.text("error_code") !in setOf("SAVED_VIEW_UNAVAILABLE", "SAVED_VIEW_CURSOR_ALTERED", "SAVED_VIEW_WRONG_OPERATION")) continue
                val root = data(failed) ?: continue
                val recovery = (root["correction_call"] ?: root["recovery_call"] ?: root["source_recovery_call"]) as? JsonObject ?: continue
                if (!permitted(recovery) || recovery == page) continue
                // One correction/reopen per retained source, even if a later cursor is lost.
                val firstFailure = observations.indexOfFirst { observation ->
                    observation["success"] != JsonPrimitive(true) &&
                        data(observation)?.let { result ->
                            listOf("correction_call", "recovery_call", "source_recovery_call").any { result[it] == recovery }
                        } == true
                }
                if (observations.drop(firstFailure + 1).any { same(it, recovery) }) continue
                return call(recovery.text("tool"), recovery["arguments"] as JsonObject)
            }
            // Required local fragments and issue dependencies are never stopped by
            // an unrelated inventory's sampling allowance.
            val issueDependency = page.text("tool").substringBefore("__") == "github_issue" &&
                args.text("action") == "list" &&
                observations.none { receipt ->
                    receipt.text("tool").substringBefore("__") == "github_issue" &&
                        receipt["success"] == JsonPrimitive(true) &&
                        data(receipt)?.let { root ->
                            listOf("issues", "items").any { field ->
                                (root[field] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().any { "pull_request" !in it }
                            }
                        } == true
                }
            val definitionPage = page.text("tool").substringBefore("__") == "github_ci" && args.text("action") == "definitions" && "index_cursor" in args
            val definitionPagesRead = observations.count {
                it.text("tool") == page.text("tool") &&
                    (it["arguments"] as? JsonObject)?.let { prior ->
                        prior.text("action") == "definitions" && "index_cursor" in prior && prior["commit_sha"] == args["commit_sha"]
                    } == true
            }
            // Source definitions are a small, independently bounded inventory. A
            // sampled run-history page must not strand an issued definition page.
            if (definitionPage && (exhaustive || definitionPagesRead < 31)) return call(page.text("tool"), args)
            val required = "view_cursor" in args || issueDependency
            // A missing issue dependency must not trigger unbounded remote inventory traversal.
            // Preserve local view fragments, but cap sampled remote pages for ordinary tests.
            if (!exhaustive && "view_cursor" !in args && sampled >= 8) continue
            if (!exhaustive && !required && sampled >= 2) continue
            // Every exact issued, authorized read is eligible, including tags,
            // issue history/comments and log-offset pages. All share the same cap.
            return call(page.text("tool"), args)
        }
        return null
    }

    fun nextCall(task: String, tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>, remainingToolCalls: Int? = null): ProviderEvent.ToolCall? {
        if (!integration(task) || tools.isEmpty()) return null
        val observations = trusted(receipts)
        val remaining = GitHubChecklistBudget.remaining(receipts, remainingToolCalls)
        if (remaining == 0) return null
        // Finish a verified owned fixture before optional acquisition can spend
        // its cleanup allowance. Failed/unknown writes remain blocked.
        GitHubSelfTestFixtures.next(task, tools, receipts, allowCreate = false)?.let { return it }
        val acquisitionAllowed = remaining == null || remaining > GitHubChecklistBudget.VERIFICATION_RESERVE
        // A cached observation is useful evidence but cannot qualify transport.
        // Retry that exact authorized read once with explicit cache bypass. The
        // retained argument prevents loops after process restart or another hit.
        val liveRead = observations.asReversed().firstNotNullOfOrNull { receipt ->
            if (receipt["success"] != JsonPrimitive(true) || GitHubEvidenceOrigin.of(receipt) == "LIVE") return@firstNotNullOfOrNull null
            val args = receipt["arguments"] as? JsonObject ?: return@firstNotNullOfOrNull null
            if (!requiresLiveTransport(Capability(receipt.text("tool").substringBefore("__"), args.text("action"), ""))) return@firstNotNullOfOrNull null
            if ("view_cursor" in args || args["verify_live"] == JsonPrimitive(true) || !dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isReplayableRead(receipt.text("tool"), args)) return@firstNotNullOfOrNull null
            val refreshed = JsonObject(args + ("verify_live" to JsonPrimitive(true)))
            if (observations.any { it.text("tool") == receipt.text("tool") && it["arguments"] == refreshed }) return@firstNotNullOfOrNull null
            val tool = tools.firstOrNull { it.name == receipt.text("tool") && it.modelVisible } ?: return@firstNotNullOfOrNull null
            if ((tool.inputSchema["properties"] as? JsonObject)?.containsKey("verify_live") != true || dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(refreshed, tool.inputSchema).isNotEmpty()) return@firstNotNullOfOrNull null
            call(tool.name, refreshed)
        }
        if (liveRead != null) return liveRead
        if (acquisitionAllowed) continuation(task, tools, receipts, localOnly = true)?.let { return it }
        val watch = tools.firstOrNull { it.modelVisible && it.name.substringBefore("__") == "github_watch_checks" }
        val selectedHead = observations.asReversed().firstNotNullOfOrNull { receipt ->
            if (!matches(receipt, capabilities.first()) || receipt["success"] != JsonPrimitive(true)) null else data(receipt)?.get("commit_sha")
        }
        if (watch != null && selectedHead != null) {
            for (run in GitHubObservedWorkflowRuns.current(observations, selectedHead as? JsonPrimitive)) {
                if (run.text("status") !in setOf("queued", "in_progress", "waiting", "pending", "requested")) continue
                val id = run["id"] ?: continue
                val args = buildJsonObject {
                    put("run_id", id)
                    put("commit_sha", selectedHead)
                }
                if (dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(args, watch.inputSchema).isEmpty() && observations.none { it.text("tool") == watch.name && (it["arguments"] as? JsonObject)?.get("run_id") == id }) return call(watch.name, args)
            }
        }
        for (capability in capabilities.filter { it.automatic }) {
            if (!acquisitionAllowed && capability.action != "checks") continue
            if (observations.any { matches(it, capability) }) continue
            val tool = tools.firstOrNull { it.modelVisible && it.name.substringBefore("__") == capability.tool } ?: continue
            val head = observations.asReversed().firstNotNullOfOrNull { receipt ->
                if (!matches(receipt, capabilities.first()) || receipt["success"] != JsonPrimitive(true)) return@firstNotNullOfOrNull null
                val root = receipt["result"] as? JsonObject
                ((root?.get("data") as? JsonObject) ?: root)?.get("commit_sha")
            }
            val arguments = buildJsonObject {
                put("action", capability.action)
                if (capability.action in setOf("branches", "workflows", "list", "commits", "runs")) put("per_page", if (capability.action == "workflows" || GitHubChecklistScope.exhaustive(task)) 100 else 30)
                if ((capability.action in setOf("checks", "commits") || capability.action == "runs" && !GitHubChecklistScope.exhaustive(task)) && head != null) put("commit_sha", head)
                if (capability.action == "list") put("state", "all")
            }
            if (dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(arguments, tool.inputSchema).isNotEmpty()) continue
            return call(tool.name, arguments)
        }
        if (!acquisitionAllowed) return null
        GitHubSelfTestReads.next(tools, observations)?.let { return it }
        // A fixture is bounded and must leave capacity for its verification and
        // cleanup; optional inventory sampling runs only after this decision.
        val fixtureAllowed = remaining == null || remaining >= GitHubChecklistBudget.FIXTURE_CALLS + GitHubChecklistBudget.VERIFICATION_RESERVE
        GitHubSelfTestFixtures.next(task, tools, receipts, allowCreate = fixtureAllowed)?.let { return it }
        return continuation(task, tools, receipts, localOnly = false)
    }

    private fun call(tool: String, args: JsonObject) = ProviderEvent.ToolCall("checklist-" + GitHubEvidenceBook.digest("$tool:$args").take(24), tool, args)

    private fun catalog(tools: List<AgentToolDefinition>): List<Capability> {
        if (tools.isEmpty()) return capabilities
        return tools.filter { it.modelVisible && (it.name.substringBefore("__").startsWith("github_") || it.name.substringBefore("__") == "repository_graph") }.flatMap { tool ->
            val name = tool.name.substringBefore("__")
            val properties = tool.inputSchema["properties"] as? JsonObject
            val actions = ((properties?.get("action") as? JsonObject)?.get("enum") as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty().ifEmpty { listOf(((properties?.get("action") as? JsonObject)?.get("const") as? JsonPrimitive)?.content.orEmpty()) }
            actions.map { action -> capabilities.firstOrNull { it.tool == name && it.action == action } ?: Capability(name, action, "$name / ${action.ifBlank { "default" }}") }
        }.distinctBy { it.tool to it.action }
    }

    private fun requiresLiveTransport(capability: Capability): Boolean = when (capability.tool) {
        "github_context" -> capability.action !in setOf("status", "resolve", "index", "efficiency", "capabilities", "recovery")
        "github_search", "github_read", "github_stage_patch", "repository_graph" -> false
        else -> capability.tool.startsWith("github_")
    }

    private fun readOnly(capability: Capability): Boolean = dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isReplayableRead(capability.tool, buildJsonObject { if (capability.action.isNotBlank()) put("action", capability.action) })

    fun rows(receipts: Map<String, JsonObject>, tools: List<AgentToolDefinition> = emptyList(), task: String = ""): JsonArray {
        val historical = receipts.values.filter { it["trusted"] == JsonPrimitive(true) || it["success"] == JsonPrimitive(false) && it["dispatched"] == JsonPrimitive(false) }
        val active = catalog(tools)
        val union = (
            active + historical.filter { (it.text("tool").startsWith("github_") || it.text("tool").substringBefore("__") == "repository_graph") && (it["arguments"] as? JsonObject)?.containsKey("view_cursor") != true }.map {
                val name = it.text("tool").substringBefore("__")
                val action = (it["arguments"] as? JsonObject)?.text("action").orEmpty()
                Capability(name, action, "$name / ${action.ifBlank { "default" }}")
            }
            ).distinctBy { it.tool to it.action }
        return JsonArray(
            union.map { capability ->
                val observed = historical.filter { matches(it, capability) }
                val discovered = tools.any { it.name.substringBefore("__") == capability.tool }
                fun permitted(receipt: JsonObject): Boolean {
                    val arguments = receipt["arguments"] as? JsonObject ?: return false
                    return tools.any { it.modelVisible && it.name == receipt.text("tool") && dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(arguments, it.inputSchema).isEmpty() }
                }
                val authorized = tools.isNotEmpty() && (active.any { it.tool == capability.tool && it.action == capability.action } || observed.any(::permitted))
                val eligible = observed.filter { it["verification_eligible"] != JsonPrimitive(false) && (tools.isEmpty() || permitted(it)) }
                val successes = eligible.filter { it["success"] == JsonPrimitive(true) }
                val failures = observed.filter { it["success"] != JsonPrimitive(true) }
                val transportMissing = requiresLiveTransport(capability) && successes.isNotEmpty() && successes.none { GitHubEvidenceOrigin.of(it) == "LIVE" }
                val unqualifiedStatus = when {
                    successes.any { GitHubEvidenceOrigin.of(it) == "CACHED" } -> "CACHED"
                    successes.any { GitHubEvidenceOrigin.of(it) == "MOCKED" } -> "MOCKED"
                    else -> "NOT_TESTED"
                }
                val latest = eligible.lastOrNull()
                val incomplete = latest?.get("success") == JsonPrimitive(true) && data(latest)?.text("status") == "PENDING_LOG_AVAILABILITY"
                val excluded = capability.tool == "github_context" && capability.action == "repositories"
                val notRequested = GitHubChecklistScope.readOnly(task) && !readOnly(capability)
                val blocker = when {
                    notRequested -> "Write operations are excluded by the requested read-only scope."
                    excluded -> "Account-wide repository discovery is outside this workspace's selected repository scope."
                    eligible.isEmpty() && observed.isNotEmpty() -> "Current catalog excludes the historical receipts; reauthorize this operation before using them as proof."
                    transportMissing -> "Saved, local or mocked responses cannot qualify this remote operation; an authorized live read or verified mutation is still required."
                    incomplete -> "The endpoint responded but traversal or log availability remains incomplete; follow the saved continuation or wait for the recorded state change."
                    failures.isNotEmpty() && (successes.isEmpty() || latest?.get("success") == JsonPrimitive(false)) -> "Resolve recorded errors: " + failures.map { it.text("error_code") }.distinct().joinToString(", ") + ". " + GitHubVerificationRecovery.advice(failures)
                    capability.tool == "github_decide" -> "Requires a genuine user decision; an integration test must not manufacture a blocking question."
                    capability.tool == "github_watch_checks" -> "Requires an observed pull request or workflow run and the active chat subscription."
                    !readOnly(capability) -> "Requires an owned isolated fixture and operation-specific authorization; fixture cleanup must be verified before completion."
                    capability.tool == "github_issue" -> "No issue identifier observed from the issue inventory; a pull request number cannot satisfy this dependency."
                    capability.tool == "github_pr" -> "No pull request identifier observed from the pull request inventory."
                    capability.tool == "github_ci" -> "Requires an observed workflow/run and a completed job for logs or a failed completed run for failure analysis."
                    capability.tool == "github_release" -> "Requires a tag or release observed in the repository inventory."
                    else -> "Requires an authorized tool and its observed repository revision or source path."
                }
                val status = when {
                    notRequested -> "NOT_REQUESTED"
                    excluded -> "OUT_OF_SCOPE"
                    incomplete || tools.isNotEmpty() && !authorized -> "BLOCKED"
                    latest?.get("success") == JsonPrimitive(false) -> if (latest["dispatched"] == JsonPrimitive(false)) "PREFLIGHT_REJECTED" else "FAILED"
                    transportMissing -> unqualifiedStatus
                    successes.isNotEmpty() && failures.isNotEmpty() -> "RECOVERED"
                    successes.isNotEmpty() -> "PASSED"
                    failures.isNotEmpty() && failures.all { it["dispatched"] == JsonPrimitive(false) } -> "PREFLIGHT_REJECTED"
                    failures.isNotEmpty() -> "FAILED"
                    capability.automatic && tools.any { it.name.substringBefore("__") == capability.tool } -> "UNTESTED"
                    else -> "BLOCKED"
                }
                buildJsonObject {
                    put("id", "${capability.tool}:${capability.action}")
                    put("scope", capability.scope)
                    put("status", status)
                    put("acceptance_state", GitHubAcceptanceLedger.state(status).name)
                    put("evidence_origins", JsonArray(eligible.map { GitHubEvidenceOrigin.of(it) }.distinct().map(::JsonPrimitive)))
                    put("live_verified", successes.any { GitHubEvidenceOrigin.of(it) == "LIVE" })
                    put("response_validated", successes.isNotEmpty())
                    put("requires_live_transport", requiresLiveTransport(capability))
                    put("fixture_state", if (readOnly(capability)) "NOT_APPLICABLE" else "REQUIRES_SEPARATE_VERIFICATION")
                    put(
                        "readiness",
                        buildJsonObject {
                            put("discovered", discovered)
                            val denied = latest?.text("error_code")?.let { code -> GitHubVerificationRecovery.authorizationBlocked(code) } == true
                            put(
                                "authorization",
                                if (denied) {
                                    "DENIED"
                                } else if (tools.isEmpty()) {
                                    "UNKNOWN"
                                } else if (authorized) {
                                    "AUTHORIZED"
                                } else {
                                    "NOT_AUTHORIZED"
                                }
                            )
                            val dispatched = eligible.filter { GitHubEvidenceOrigin.of(it) == "LIVE" }
                            val latestDispatch = dispatched.lastOrNull()
                            val unavailable = latest?.text("error_code")?.let { code -> code.contains("PERMISSION") || code.contains("AUTHORIZATION") || code.contains("UNREACHABLE") || code.contains("UNAVAILABLE") } == true
                            put(
                                "availability",
                                if (unavailable) {
                                    "UNAVAILABLE"
                                } else if (latestDispatch?.get("success") == JsonPrimitive(true)) {
                                    "AVAILABLE"
                                } else {
                                    "UNKNOWN"
                                }
                            )
                            put("tested", dispatched.isNotEmpty())
                            put("successfully_tested", status in setOf("PASSED", "RECOVERED") && latestDispatch?.get("success") == JsonPrimitive(true))
                        }
                    )
                    put("inventory_status", if (successes.any { data(it)?.get("complete") == JsonPrimitive(false) }) "PARTIAL" else "See independent inventory coverage")
                    put("evidence_scope", "Validated response for the exact action; origins identify LIVE, CACHED, LOCAL, MOCKED or NOT_TESTED execution. Fixture state, cleanup, application behavior and device health require separate evidence")
                    put("receipt_ids", JsonArray(observed.mapNotNull { it["receipt_id"] }))
                    put("successful_receipt_ids", JsonArray(successes.mapNotNull { it["receipt_id"] }))
                    put("attempted", observed.size)
                    put("dispatched", observed.count { it["dispatched"] == JsonPrimitive(true) })
                    put("rejected_before_dispatch", failures.count { it["dispatched"] == JsonPrimitive(false) })
                    put("completed", successes.size)
                    put("historical_failure_ids", JsonArray(failures.mapNotNull { it["receipt_id"] }))
                    put("excluded_receipt_ids", JsonArray(observed.filter { it["verification_eligible"] == JsonPrimitive(false) }.mapNotNull { it["receipt_id"] }))
                    put("next_action", if (status in setOf("PASSED", "RECOVERED")) "Reuse matching receipts; inspect pending inventory pages independently." else blocker)
                    if (status !in setOf("PASSED", "RECOVERED", "OUT_OF_SCOPE", "NOT_REQUESTED")) put("blocker", blocker)
                }
            }
        )
    }

    /** Error-code guidance for diagnostic callers; receipt-aware advice remains authoritative. */
    internal fun recoveryAdvice(codes: Set<String>): String = when {
        "AUTH_REQUIRED" in codes -> GitHubFailurePolicy.recoveryHint("AUTH_REQUIRED")
        "REPOSITORY_GRAPH_UNAVAILABLE" in codes -> GitHubFailurePolicy.recoveryHint("REPOSITORY_GRAPH_UNAVAILABLE")
        codes.any { it in GitHubFailurePolicy.codes } -> codes.filter { it in GitHubFailurePolicy.codes }.sorted().joinToString(" ") { GitHubFailurePolicy.recoveryHint(it) }
        codes.any { it.contains("PERMISSION") || it.contains("AUTHORIZATION") || it.contains("SCOPE_MISMATCH") } -> "Review the selected repository and connection permissions; unchanged authorization failures are not retried."
        codes.any { it.startsWith("SAVED_VIEW_") || it == "RETAINED_RESULT_CAPACITY" } -> "Use the exact retained read-only recovery recipe once; retain the unavailable result if reopening fails. Never replay a write to recover its output."
        "INVALID_ARGUMENTS" in codes -> "Use identifiers and required fields from the matching inventory and current tool schema; retry only corrected read arguments."
        "GITHUB_NOT_FOUND_OR_HIDDEN" in codes -> "Check the observed resource, permissions and retention; a missing response does not prove deletion. Log sampling permits one different executed job."
        codes.any { it.contains("RATE_LIMIT") } -> "Wait until the recorded retry deadline before an authorized read."
        else -> "Inspect the retained error and its exact resource before correcting the request. Reconciliation is required only when a mutation's remote outcome is uncertain."
    }

    /** API completion never implies device, benchmark or application-wide acceptance. */
    fun apiComplete(task: String, receipts: Map<String, JsonObject>, tools: List<AgentToolDefinition>): Boolean {
        if (!integration(task) || GitHubRequestCompletion.requirements(task).size != 1 || tools.isEmpty() || Regex("(?i)device|android|app(?:lication)? health|stress|benchmark|reboot|process death|install apk|\\b(?:fix|implement|repair|improve|performance|latency|features?)\\b").containsMatchIn(task)) return false
        val mutations = catalog(tools).filter { !readOnly(it) && !GitHubChecklistScope.readOnly(task) }
        if (mutations.any { it.tool != "github_commit" || it.action !in setOf("branch", "delete_branch") }) return false
        if (mutations.isNotEmpty() && !GitHubSelfTestFixtures.complete(receipts)) return false
        val coverage = rows(receipts, tools, task)
        if (coverage.none { (it as JsonObject).text("status") in setOf("PASSED", "RECOVERED") } || coverage.any { (it as JsonObject).text("status") !in setOf("PASSED", "RECOVERED", "OUT_OF_SCOPE", "NOT_REQUESTED") }) return false
        if (GitHubChecklistScope.exhaustive(task) && (pendingPages(receipts).isNotEmpty() || inventoryCoverage(receipts).any { (it as JsonObject).text("coverage") != "retrieval complete at observation" })) return false
        if (workflowExecutions(receipts).any { (it as JsonObject)["execution_verified"] != JsonPrimitive(true) }) return false
        return true
    }

    /** Compact public progress uses evaluated outcomes, never receipt or call counts. */
    fun progressSummary(checkpoint: JsonObject, next: ProviderEvent.ToolCall?): String {
        val catalog = (checkpoint["capabilities"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val rows = catalog.filter { it.text("status") !in setOf("OUT_OF_SCOPE", "NOT_REQUESTED") }
        val excluded = catalog.size - rows.size
        val completed = rows.count { it.text("status") in setOf("PASSED", "RECOVERED") }
        val failed = rows.count { it.text("status") in setOf("FAILED", "PREFLIGHT_REJECTED") }
        val blocked = rows.count { it.text("status") == "BLOCKED" }
        val nextAction = if (next == null) {
            "Review remaining blockers in the results."
        } else {
            val tool = next.name.substringBefore("__")
            val action = next.arguments.text("action")
            val scope = rows.firstOrNull { it.text("id") == "$tool:$action" }?.text("scope")
            val label = scope?.takeUnless { it.startsWith("github_") }
                ?: when (tool) {
                    "discover_available_tools" -> "Discover available operations"
                    "github_watch_checks" -> "Watch pending checks"
                    "github_search" -> "Search repository paths"
                    "github_read" -> "Read the selected source"
                    else -> action.replace('_', ' ').ifBlank { "Read the next result page" }
                }
            "Next: $label."
        }
        return "Checks: $completed/${rows.size} complete" + (if (failed > 0) "; $failed failed" else "") + (if (blocked > 0) "; $blocked blocked" else "") + (if (excluded > 0) "; $excluded excluded from scope" else "") + ". $nextAction"
    }

    /** Repeating identical observations is history, not forward execution progress. */
    fun progressDigest(task: String, receipts: Map<String, JsonObject>, tools: List<AgentToolDefinition>): String = GitHubEvidenceBook.digest(
        buildJsonObject {
            put("request_digest", GitHubEvidenceBook.digest(task))
            put("capabilities", JsonArray(rows(receipts, tools, task).map { raw -> JsonObject((raw as JsonObject).filterKeys { !it.endsWith("_ids") && it !in setOf("attempted", "dispatched", "rejected_before_dispatch", "completed") }) }))
            put("pending_pages", JsonArray(pendingPages(receipts)))
            put(
                "distinct_observations",
                JsonArray(
                    receipts.values.filter { it["trusted"] == JsonPrimitive(true) }.map { receipt ->
                        JsonObject(receipt.filterKeys { it in setOf("tool", "arguments", "success", "error_code", "result_sha256", "verification_eligible") })
                    }.distinct().sortedBy { it.toString() }
                )
            )
            put("fixtures", JsonArray(GitHubSelfTestFixtures.checkpoint(receipts).map { raw -> JsonObject((raw as JsonObject).filterKeys { !it.endsWith("_receipt") }) }))
        }.toString()
    )

    fun checkpoint(task: String, receipts: Map<String, JsonObject>, tools: List<AgentToolDefinition> = emptyList(), remainingToolCalls: Int? = null): JsonObject = buildJsonObject {
        put("schema", "github.test-checklist.v3")
        put("request_digest", GitHubEvidenceBook.digest(task))
        val capabilities = rows(receipts, tools, task)
        put("capabilities", capabilities)
        put("stage_budget", GitHubChecklistBudget.checkpoint(receipts, remainingToolCalls))
        put(
            "resume_plan",
            buildJsonObject {
                put("source", "Trusted receipts revalidated against the current authorization catalog")
                put("pending_capabilities", JsonArray(capabilities.filter { (it as JsonObject).text("status") !in setOf("PASSED", "RECOVERED", "OUT_OF_SCOPE", "NOT_REQUESTED") }))
                put("cleanup_first", true)
                put("mutation_retry_policy", "Reconcile failed or uncertain writes; never replay a completed mutation or repeat a write to recover its response.")
            }
        )
        put("acceptance_ledger", GitHubAcceptanceLedger.snapshot(capabilities = capabilities, requestDigest = GitHubEvidenceBook.digest(task)))
        put("coverage_source", if (tools.isEmpty()) "BASELINE_CATALOG" else "AUTHORIZED_TOOL_ACTION_SCHEMAS")
        put("pagination_policy", "Reuse complete persisted originals and their exact remote recipes; consume display fragments only when no complete original was retained; up to 32 definition pages per revision independently of two remote inventory samples, or eight while locating an issue dependency, unless complete inventory is explicitly requested; restore retained pages locally before one bounded read-only source recovery")
        put("acceptance", JsonArray(listOf("Each requested capability has a result or explicit untested/blocker reason", "Every claim cites matching branch/PR/SHA receipts", "Complete durable sources or required local fragments, and remote pages, consumed or explicitly pending", "Application test results bound to newly executed runs at tested SHA", "Coverage, measured score or limitation, failures and solutions retained", "Authorized isolated write fixtures verified and cleaned up").map(::JsonPrimitive)))
        put("pending_pages", JsonArray(pendingPages(receipts)))
        put("pagination_resume", paginationResume(task, receipts, tools, remainingToolCalls))
        put("fixtures", GitHubSelfTestFixtures.checkpoint(receipts))
        put("inventory_coverage", inventoryCoverage(receipts))
        put("evidence_count", trusted(receipts).size)
        put("new_workflow_executions", workflowExecutions(receipts))
        put("application_tests", "UNTESTED unless a newly executed test run/job result is bound to the exact requested SHA; generic workflow success is not application coverage")
        put("device_tests", "UNTESTED without a device execution receipt")
        put("overall_health", "NOT_MEASURED")
    }

    fun render(receipts: Map<String, JsonObject>, tools: List<AgentToolDefinition> = emptyList(), task: String = ""): String = buildString {
        val rows = rows(receipts, tools, task)
        val counts = listOf("PASSED", "CACHED", "MOCKED", "FAILED", "PREFLIGHT_REJECTED", "BLOCKED", "UNTESTED", "NOT_TESTED", "RECOVERED", "OUT_OF_SCOPE", "NOT_REQUESTED").associateWith { status -> rows.count { (it as JsonObject).text("status") == status } }
        append("\n**Capability coverage:** ${rows.size} capabilities in the cumulative catalog (including historical operations). ")
        append(counts.entries.joinToString("; ") { "${it.key.lowercase().replace('_', ' ')}: ${it.value}" })
        append(". Endpoint success and complete search coverage are separate. **Overall integration health: Not measured.**\n\n")
        append("| Capability | Result | Blocker / next action |\n| --- | --- | --- |\n")
        val detailed = Regex("(?i)\\b(?:detailed|full|complete|all)\\s+(?:report|results|receipts|coverage)\\b").containsMatchIn(task)
        val visible = if (detailed || rows.size <= 12) rows else rows.filter { (it as JsonObject)["blocker"] != null }.distinctBy { (it as JsonObject)["blocker"] }.take(6)
        visible.forEach {
            val row = it as JsonObject
            append("| ${row.text("scope")} | ${row.text("status")} | ${row.text("next_action").replace("|", "\\|")} |\n")
        }
        if (visible.size < rows.size) append("\nShowing ${visible.size} representative blockers. All ${rows.size} capability rows and receipt IDs remain in the conversation export; request a detailed report to display them.\n")
        inventoryCoverage(receipts).forEach { raw ->
            val item = raw as JsonObject
            val filters = item["filters"] as? JsonObject
            val scope = filters.orEmpty().entries.joinToString(", ") { (key, value) -> "$key=${(value as? JsonPrimitive)?.contentOrNull.orEmpty().replace(Regex("[\\p{Cntrl}<>`*\\[\\]|]"), " ")}" }
            append("\n**${item.text("tool").substringBefore("__")} / ${item.text("operation")} inventory:** ${item.text("retrieved")} of ${item.text("total")} retrieved; ${item.text("coverage")}." + scope.takeIf(String::isNotBlank)?.let { " Filters: $it." }.orEmpty() + "\n")
        }
        val pages = pendingPages(receipts)
        if (pages.isNotEmpty()) {
            append("\n**Pending pagination:** ${pages.size} issued continuation(s) remain unread; inventory completeness is not established. Complete retained sources satisfy display-fragment traversal; unread remote pages remain pending.\n")
            val resume = paginationResume(task, receipts, tools)
            append("Pagination mode: ${resume.text("mode")}. ${resume.text("next_action")}\n")
        }
        append("\n**Solutions / next actions:** finish pending reads using the saved continuation arguments; resolve recorded failures; exercise missing writes on an authorized isolated branch or test repository; execute application tests at the exact SHA and retain run/job links. Watch pending checks to a bounded deadline, then keep a pending checkpoint. Cleanup requires verifying fixture ownership. Merge and cancellation are never implied for existing work.\n")
    }

    /** Exact resumable reads remain separate from intentional smoke-test sampling. */
    fun paginationResume(task: String, receipts: Map<String, JsonObject>, tools: List<AgentToolDefinition>, remainingToolCalls: Int? = null): JsonObject {
        val pending = pendingPages(receipts)
        val observations = trusted(receipts)
        val exhaustive = GitHubChecklistScope.exhaustive(task)
        val remaining = GitHubChecklistBudget.remaining(receipts, remainingToolCalls)
        val next = if (remaining == null || remaining > GitHubChecklistBudget.VERIFICATION_RESERVE) continuation(task, tools, receipts, localOnly = true) ?: continuation(task, tools, receipts, localOnly = false) else null
        val rejected = pending.count { page -> observations.lastOrNull { it["tool"] == page["tool"] && it["arguments"] == page["arguments"] }?.get("success") == JsonPrimitive(false) }
        return buildJsonObject {
            put("mode", if (exhaustive) "COMPLETE_INVENTORY_REQUESTED" else "BOUNDED_SMOKE_SAMPLE")
            put("pending_count", pending.size)
            put("failed_page_count", rejected)
            put("remote_samples_spent", remoteSamples(observations))
            put("complete", pending.isEmpty())
            next?.let {
                put(
                    "next_call",
                    buildJsonObject {
                        put("tool", it.name)
                        put("arguments", it.arguments)
                    }
                )
            }
            put(
                "next_action",
                when {
                    pending.isEmpty() -> "No issued continuation remains; inspect independent inventory coverage for gaps."
                    remaining != null && remaining <= GitHubChecklistBudget.VERIFICATION_RESERVE -> "Acquisition budget is reserved for verification/cleanup. Resume from saved read recipes with an explicitly authorized new budget; no ceiling was increased."
                    next != null -> "Continue the exact next_call read under the existing authorization and budget. No writes, decisions, workflow dispatch or merges are implied."
                    rejected > 0 -> "Resolve the failed page's exact permission, validation or availability blocker before resuming. Unchanged failures are not retried."
                    !exhaustive -> "Ordinary integration tests sample remote inventory within bounded limits. Request testing all tools and completing pending pagination to traverse retained read recipes; other permissions and budgets still apply."
                    else -> "No currently authorized read recipe is executable. Restore the exact tool/schema/connection, then resume the saved checkpoint; do not invent a cursor or infer completeness."
                }
            )
        }
    }

    fun inventoryCoverage(receipts: Map<String, JsonObject>): JsonArray {
        val pending = pendingPages(receipts)
        val trusted = trusted(receipts)
        fun filters(receipt: JsonObject): JsonObject {
            val args = receipt["arguments"] as? JsonObject
            val selected = args.orEmpty().filterKeys { it in setOf("state", "ref", "branch", "commit_sha", "head_sha", "workflow_id", "head", "base", "per_page", "actor", "event", "created", "status") }
            val pageSize = args?.get("per_page") ?: data(receipt)?.get("per_page")
            return JsonObject(if (pageSize == null) selected else selected + ("per_page" to pageSize))
        }
        val groups = trusted.filter { receipt ->
            receipt["success"] == JsonPrimitive(true) && (receipt["arguments"] as? JsonObject)?.text("action") in setOf("workflows", "branches", "list", "commits", "runs", "tags")
        }.groupBy {
            val args = it["arguments"] as? JsonObject
            Triple(it.text("tool"), args?.text("action"), filters(it))
        }
        return JsonArray(
            groups.mapNotNull { (key, observations) ->
                val byPage = observations.groupBy { receipt ->
                    ((receipt["arguments"] as? JsonObject)?.get("page") as? JsonPrimitive)?.longOrNull
                        ?: (data(receipt)?.get("page") as? JsonPrimitive)?.longOrNull ?: 1L
                }.mapValues { (_, versions) -> versions.last() }
                val pages = byPage.toSortedMap().values.mapNotNull(::data)
                val arrays = pages.mapNotNull { page -> (page["workflows"] ?: page["items"] ?: page["pull_requests"] ?: page["workflow_runs"] ?: page["tags"]) as? JsonArray }
                val records = arrays.flatMap { it.mapNotNull { item -> item as? JsonObject } }
                val identities = records.mapNotNull { item -> item["id"] ?: item["number"] ?: item["name"] ?: item["sha"] }.distinct()
                val totals = pages.mapNotNull { (it["total_count"] as? JsonPrimitive)?.longOrNull }.distinct()
                val total = totals.singleOrNull()
                val viewIds = observations.mapNotNull { receipt ->
                    val view = (receipt["view"] as? JsonObject) ?: (receipt["result"] as? JsonObject)
                    (((view?.get("recovery_call") as? JsonObject)?.get("arguments") as? JsonObject)?.text("view_cursor"))?.substringBefore(':')?.takeIf { it.isNotBlank() }
                }.toSet()
                fun belongs(recipe: JsonObject): Boolean {
                    if (recipe.text("tool") != key.first) return false
                    val args = recipe["arguments"] as? JsonObject ?: return false
                    if ("view_cursor" in args) return args.text("view_cursor").substringBefore(':') in viewIds
                    val selected = filters(recipe)
                    // Failed reads may not reveal the default page size; keep their
                    // blocker for every otherwise matching inventory until recovered.
                    val expected = if ("per_page" in selected) key.third else JsonObject(key.third.filterKeys { it != "per_page" })
                    return args.text("action") == key.second && selected == expected
                }
                val end = byPage.keys.maxOrNull() ?: 0L
                val contiguous = end in 1..1000 &&
                    (1L..end).all { it in byPage } &&
                    (1L until end).all { data(requireNotNull(byPage[it]))?.get("has_more") == JsonPrimitive(true) }
                val unresolvedFailure = trusted.withIndex().any { (index, failed) ->
                    if (failed["success"] == JsonPrimitive(true) || !belongs(failed)) return@any false
                    val later = trusted.drop(index + 1)
                    val result = data(failed)
                    val recovery = (result?.get("correction_call") ?: result?.get("recovery_call") ?: result?.get("source_recovery_call")) as? JsonObject
                    val recoveryArgs = recovery?.get("arguments") as? JsonObject
                    val reopened = failed.text("error_code") in setOf("SAVED_VIEW_UNAVAILABLE", "SAVED_VIEW_CURSOR_ALTERED", "SAVED_VIEW_WRONG_OPERATION") &&
                        recovery != null &&
                        recoveryArgs != null &&
                        dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isReplayableRead(recovery.text("tool"), recoveryArgs) &&
                        later.any { it["success"] == JsonPrimitive(true) && it["tool"] == recovery["tool"] && it["arguments"] == recoveryArgs }
                    !reopened && later.none { it["success"] == JsonPrimitive(true) && it["tool"] == failed["tool"] && it["arguments"] == failed["arguments"] }
                }
                val complete = contiguous &&
                    pages.lastOrNull()?.get("has_more") == JsonPrimitive(false) &&
                    pages.none { it["complete"] == JsonPrimitive(false) } &&
                    arrays.size == pages.size &&
                    arrays.sumOf { it.size } == records.size &&
                    identities.size == records.size &&
                    totals.size <= 1 &&
                    (total == null || total == identities.size.toLong()) &&
                    pending.none(::belongs) &&
                    !unresolvedFailure
                buildJsonObject {
                    put("tool", key.first)
                    put("operation", key.second.orEmpty())
                    put("page_count", byPage.size)
                    put("pending_continuations", pending.count(::belongs))
                    put("filters", key.third)
                    put("retrieved", identities.size)
                    put("total", total?.toString() ?: "unknown total")
                    put("coverage", if (complete) "retrieval complete at observation" else "incomplete or pending pagination")
                }
            }
        )
    }

    /** Only explicit mutation receipts can establish that this task initiated a run. */
    fun workflowExecutions(receipts: Map<String, JsonObject>): JsonArray = JsonArray(
        trusted(receipts).filter {
            it["success"] == JsonPrimitive(true) &&
                it.text("tool").substringBefore("__") == "github_ci" &&
                (it["arguments"] as? JsonObject)?.text("action") in setOf("dispatch", "rerun", "rerun_all", "rerun_failed_jobs")
        }.map { mutation ->
            val args = mutation["arguments"] as? JsonObject
            val result = data(mutation)
            val accepted = result?.get("receipt") as? JsonObject
            val id = accepted?.get("workflow_run_id") ?: accepted?.get("id") ?: result?.get("run_id") ?: args?.get("run_id")
            val sha = args?.get("expected_head_sha") ?: args?.get("commit_sha")
            val mutationTime = (mutation["observed_at_ms"] as? JsonPrimitive)?.longOrNull
            val observation = trusted(receipts).asReversed().firstNotNullOfOrNull { read ->
                val readTime = (read["observed_at_ms"] as? JsonPrimitive)?.longOrNull
                // A rerun reuses a run ID; without an attempt fence its old terminal state is not new execution evidence.
                if (mutationTime == null || readTime == null || readTime <= mutationTime) return@firstNotNullOfOrNull null
                if (read["success"] != JsonPrimitive(true) || read.text("tool").substringBefore("__") != "github_ci" || (read["arguments"] as? JsonObject)?.text("action") !in setOf("run", "status")) return@firstNotNullOfOrNull null
                data(read)?.takeIf {
                    val expectedAttempt = (result?.get("expected_run_attempt") as? JsonPrimitive)?.longOrNull
                    val attempt = (it["run_attempt"] as? JsonPrimitive)?.longOrNull
                    id != null &&
                        sha != null &&
                        it["id"] == id &&
                        it["head_sha"] == sha &&
                        (args?.text("action") == "dispatch" || expectedAttempt != null && attempt == expectedAttempt)
                }
            }
            val jobPages = trusted(receipts).filter { read ->
                val value = data(read)
                val expectedAttempt = result?.get("expected_run_attempt") ?: observation?.get("run_attempt")
                read["success"] == JsonPrimitive(true) &&
                    (read["observed_at_ms"] as? JsonPrimitive)?.longOrNull?.let { it > (mutationTime ?: Long.MAX_VALUE) } == true &&
                    value?.get("run_id") == id &&
                    id != null &&
                    value?.get("head_sha") == sha &&
                    sha != null &&
                    expectedAttempt != null &&
                    value?.get("run_attempt") == expectedAttempt &&
                    read.text("tool").substringBefore("__") == "github_ci" &&
                    (read["arguments"] as? JsonObject)?.text("action") == "jobs"
            }.mapNotNull(::data)
            val jobs = GitHubWorkflowJobEvidence.completeJobs(jobPages)
            val jobsVerified = jobs != null && jobs.all { it.text("status") == "completed" && it.text("conclusion") in setOf("success", "skipped", "neutral") }
            buildJsonObject {
                put("attempt_jobs_verified", jobsVerified)
                put("execution_verified", observation?.text("status") == "completed" && observation.text("conclusion") == "success" && jobsVerified)
                mutation["receipt_id"]?.let { put("dispatch_receipt", it) }
                id?.let { put("run_id", it) }
                sha?.let { put("commit_sha", it) }
                result?.get("expected_run_attempt")?.let { put("expected_run_attempt", it) }
                put("status", observation?.text("status") ?: "PENDING_ASSOCIATION_OR_RESULT")
                put("conclusion", observation?.text("conclusion") ?: "NOT_MEASURED")
                put("scope", "Explicitly initiated workflow only; identify executed test jobs before claiming application-test coverage")
            }
        }
    )

    private fun data(receipt: JsonObject): JsonObject? {
        val root = receipt["result"] as? JsonObject ?: return null
        return (root["data"] as? JsonObject) ?: root
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
