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

    fun fixtureApprovalPreflight(mode: GitHubDecisionMode?, binding: String, repository: String, branch: String, runtime: JsonObject): JsonObject = buildJsonObject {
        val automatic = mode == GitHubDecisionMode.AUTO_APPROVE
        put("schema", "github.fixture-approval-preflight.v1")
        put("binding", binding)
        put("repository", repository)
        put("selected_branch", branch)
        put("decision_mode", mode?.name ?: "UNKNOWN")
        put("state", if (automatic) "READY" else "CONDITIONAL")
        put("mutation_dispatched", false)
        put("runtime_identity", runtime)
        put("scope", "AUTO_APPROVE applies only to the dedicated codex-self-test/* fixture branch; regular repository writes still use their configured approval policy.")
        put("reason", if (automatic) {
            "The effective GitHub decision mode was verified before fixture creation. Fixture-only native auto-approval is restricted to the owned codex-self-test/* branch."
        } else {
            "Fixture testing is conditional because effective GitHub decision mode is ${mode?.name ?: "unavailable"}. No branch or fixture mutation was started; enable the explicit fixture-only automatic approval mode or use the approval interaction harness."
        })
    }
    private fun trusted(receipts: Map<String, JsonObject>) = receipts.values.filter { it["trusted"] == JsonPrimitive(true) && it["verification_eligible"] != JsonPrimitive(false) }
    private fun matches(receipt: JsonObject, capability: Capability): Boolean = receipt.text("tool").substringBefore("__") == capability.tool &&
        (receipt["arguments"] as? JsonObject)?.containsKey("view_cursor") != true &&
        (capability.action.isBlank() || (receipt["arguments"] as? JsonObject)?.text("action") == capability.action)

    private fun sameCall(receipt: JsonObject, recipe: JsonObject, issuer: JsonObject? = null): Boolean {
        if (issuer != null && (issuer["binding"] != receipt["binding"] || issuer["repository"] != receipt["repository"])) return false
        val arguments = recipe["arguments"] as? JsonObject ?: return false
        return receipt.text("tool") == recipe.text("tool") && (receipt["arguments"] == arguments || GitHubActionIdentity.matches(recipe.text("tool"), arguments, receipt))
    }

    /** Follow only exact issued read recipes. Local fragments and remote pages are independent. */
    fun pendingPages(receipts: Map<String, JsonObject>): List<JsonObject> {
        val observations = trusted(receipts)
        val completed = observations.filter { it["success"] == JsonPrimitive(true) }
        val retired = GitHubSelfTestFixtures.retiredReadPredicate(receipts)
        val recovered = observations.mapIndexedNotNull { index, receipt ->
            val recovery = savedReadRecovery(receipt) ?: return@mapIndexedNotNull null
            if (observations.drop(index + 1).none { it["success"] == JsonPrimitive(true) && sameCall(it, recovery, receipt) }) return@mapIndexedNotNull null
            receipt.text("tool") to receipt["arguments"]
        }.toSet()
        return observations.flatMap { issuer ->
            issuedPages(issuer).filter { recipe ->
                val local = (recipe["arguments"] as? JsonObject)?.containsKey("view_cursor") == true
                (local || !retired(issuer)) && completed.none { sameCall(it, recipe, issuer) } && recipe.text("tool") to recipe["arguments"] !in recovered
            }
        }.distinct().let { recipes ->
            recipes.filter { weak ->
                val args = weak["arguments"] as? JsonObject ?: return@filter true
                recipes.none { strong ->
                    val bound = strong["arguments"] as? JsonObject
                    strong !== weak &&
                        strong["tool"] == weak["tool"] &&
                        strong.filterKeys { it != "arguments" } == weak.filterKeys { it != "arguments" } &&
                        bound != null &&
                        bound.size > args.size &&
                        args.all { (key, value) -> bound[key] == value } &&
                        (bound.keys - args.keys).all { it in setOf("expected_head_sha", "expected_blob_sha", "commit_sha") }
                }
            }
        }
    }

    /** An argument repair is admissible only for an exact local cursor returned by this facade. */
    private fun savedReadRecovery(receipt: JsonObject): JsonObject? {
        if (receipt["success"] == JsonPrimitive(true)) return null
        val root = data(receipt) ?: return null
        val recovery = (root["correction_call"] ?: root["recovery_call"] ?: root["source_recovery_call"]) as? JsonObject ?: return null
        val args = recovery["arguments"] as? JsonObject ?: return null
        val name = recovery.text("tool")
        when (receipt.text("error_code")) {
            "SAVED_VIEW_UNAVAILABLE", "SAVED_VIEW_CURSOR_ALTERED", "SAVED_VIEW_WRONG_OPERATION" -> Unit
            "INVALID_ARGUMENTS" -> {
                val rejected = receipt["arguments"] as? JsonObject ?: return null
                val cursor = rejected["view_cursor"] as? JsonPrimitive ?: return null
                if (receipt["dispatched"] != JsonPrimitive(false) ||
                    root.text("schema") != "github.arguments.v1" ||
                    root.text("operation") != receipt.text("tool").substringBefore("__") ||
                    root["correction_call"] != recovery ||
                    name != receipt.text("tool") ||
                    !cursor.isString ||
                    cursor.content.isBlank() ||
                    args.keys != setOf("view_cursor") ||
                    args["view_cursor"] != cursor
                ) {
                    return null
                }
            }
            else -> return null
        }
        if (!dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isReplayableRead(name, args) ||
            dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isBranchSelection(name, args) ||
            name.substringBefore("__") == "repository_graph" &&
            args.text("action") in setOf("index", "note")
        ) {
            return null
        }
        return recovery
    }

    private fun recoverySource(recipe: JsonObject): Pair<String, JsonObject>? {
        val args = recipe["arguments"] as? JsonObject ?: return null
        val cursor = (args["view_cursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return recipe.text("tool") to if (args.keys == setOf("view_cursor") && cursor != null) {
            buildJsonObject { put("saved_view", cursor.substringBefore(':')) }
        } else {
            args
        }
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
        val issued = observations.flatMap { issuer -> issuedPages(issuer).map { issuer to it } }
        return observations.count { receipt ->
            val args = receipt["arguments"] as? JsonObject ?: return@count false
            "view_cursor" !in args &&
                !(receipt.text("tool").substringBefore("__") == "github_ci" && args.text("action") == "definitions" && "index_cursor" in args) &&
                ((args["page"] as? JsonPrimitive)?.longOrNull?.let { it > 1 } == true || issued.any { (issuer, recipe) -> sameCall(receipt, recipe, issuer) })
        }
    }

    /** Spend the next bounded page on an observed dependency or selected revision before broad history. */
    private fun pagePriority(recipe: JsonObject, observations: List<JsonObject>): Int {
        val arguments = recipe["arguments"] as? JsonObject ?: return 4
        if ("view_cursor" in arguments) return 0
        val tool = recipe.text("tool").substringBefore("__")
        val action = arguments.text("action")
        val head = observations.asReversed().firstNotNullOfOrNull { receipt ->
            if (receipt["success"] == JsonPrimitive(true) && receipt.text("tool").substringBefore("__") == "github_context" && (receipt["arguments"] as? JsonObject)?.text("action") == "status") data(receipt)?.get("commit_sha") else null
        }
        if (tool == "github_ci" && action == "definitions") return 1
        if (tool == "github_issue" &&
            action == "list" &&
            observations.none { receipt ->
                receipt["success"] == JsonPrimitive(true) &&
                    receipt.text("tool").substringBefore("__") == tool &&
                    data(receipt)?.let { root ->
                        listOf("issues", "items").any { key -> (root[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().any { "pull_request" !in it } }
                    } == true
            }
        ) {
            return 1
        }
        if (head != null && arguments["commit_sha"] == head && (tool == "github_ci" && action == "runs" || tool == "github_context" && action == "commits")) return 2
        if (tool == "github_release" && action in setOf("list", "tags")) return 3
        return 4
    }

    /** Consume issued local fragments while their bounded cache entry is still live. */
    private fun continuation(task: String, tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>, localOnly: Boolean): ProviderEvent.ToolCall? {
        val observations = trusted(receipts)
        fun same(receipt: JsonObject, recipe: JsonObject) = sameCall(receipt, recipe)
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
        // A rejected call can contain extra selectors and therefore differ from
        // the original cursor-only recipe. Recover from the trusted rejection,
        // not equality with that malformed request. No arguments are inferred.
        val lastAttempts = mutableMapOf<Pair<String, JsonObject>, Int>()
        observations.forEachIndexed { index, receipt -> recoverySource(receipt)?.let { lastAttempts[it] = index } }
        val considered = mutableSetOf<Pair<String, JsonObject>>()
        for ((index, failed) in observations.withIndex()) {
            val recovery = savedReadRecovery(failed) ?: continue
            val source = recoverySource(recovery) ?: continue
            if (!considered.add(source)) continue
            val args = recovery["arguments"] as JsonObject
            if (localOnly && "view_cursor" !in args || !permitted(recovery) || same(failed, recovery)) continue
            // The durable receipts spend one correction per saved view or exact
            // recovery recipe. A later offset or process restart cannot reset it.
            if ((lastAttempts[source] ?: -1) > index) continue
            return call(recovery.text("tool"), args)
        }
        for (page in pendingPages(receipts).sortedBy { pagePriority(it, observations) }) {
            val args = page["arguments"] as? JsonObject ?: continue
            if (localOnly && "view_cursor" !in args || !permitted(page)) continue
            val priorIndex = observations.indexOfLast { same(it, page) }
            if (priorIndex >= 0) continue
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

    fun nextCall(task: String, tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>, remainingToolCalls: Int? = null, fixturePreflight: JsonObject? = null): ProviderEvent.ToolCall? {
        if (!integration(task) || tools.isEmpty()) return null
        val observations = trusted(receipts)
        val remaining = GitHubChecklistBudget.remaining(receipts, remainingToolCalls)
        if (remaining == 0) return null
        // Preserve cleanup allowance, but qualify required live reads while their
        // owned resource still exists. A verified deletion retires those reads.
        val acquisitionAllowed = remaining == null || remaining > GitHubChecklistBudget.VERIFICATION_RESERVE
        val cleanup = GitHubSelfTestFixtures.next(task, tools, receipts, allowCreate = false, cleanupOnly = true, abortIncomplete = !acquisitionAllowed)
        if (!acquisitionAllowed && cleanup != null) return cleanup
        // A deletion may already have happened. Reconcile its exact retained
        // intent before attempting reads whose resource may no longer exist.
        if (cleanup?.callId?.startsWith("fixture-preflight-") == true ||
            cleanup?.arguments?.get("action") == JsonPrimitive("recovery") &&
            cleanup.arguments["fixture_plan"] == JsonPrimitive(GitHubOwnedFixtures.PLAN)
        ) {
            return cleanup
        }
        if (acquisitionAllowed) GitHubTransportRecovery.nextRead(tools, observations)?.let { return it }
        val retiredRead = GitHubSelfTestFixtures.retiredReadPredicate(receipts)
        if (GitHubIntegrationAcceptance.requiresBuildBinding(task) && GitHubIntegrationAcceptance.binding(observations)["verified"] != JsonPrimitive(true)) {
            val runtime = GitHubRuntimeIdentity.current()
            val status = tools.firstOrNull { it.modelVisible && it.name.substringBefore("__") == "github_context" && (it.inputSchema["properties"] as? JsonObject)?.containsKey("verify_live") == true }
            val args = buildJsonObject {
                put("action", "status")
                put("verify_live", true)
            }
            if (status != null &&
                dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(args, status.inputSchema).isEmpty() &&
                observations.none { it.text("tool") == status.name && (it["arguments"] == args || GitHubActionIdentity.matches(status.name, args, it)) && it["runtime_identity"] == runtime }
            ) {
                return call(status.name, args)
            }
        }
        // A cached observation is useful evidence but cannot qualify transport.
        // Retry that exact authorized read once with explicit cache bypass. The
        // retained argument prevents loops after process restart or another hit.
        val liveRead = observations.asReversed().firstNotNullOfOrNull { receipt ->
            if (receipt["success"] != JsonPrimitive(true) || GitHubEvidenceOrigin.of(receipt) == "LIVE" || retiredRead(receipt)) return@firstNotNullOfOrNull null
            val args = receipt["arguments"] as? JsonObject ?: return@firstNotNullOfOrNull null
            if (!requiresLiveTransport(Capability(receipt.text("tool").substringBefore("__"), args.text("action"), ""))) return@firstNotNullOfOrNull null
            if ("view_cursor" in args || args["verify_live"] == JsonPrimitive(true) || !dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isReplayableRead(receipt.text("tool"), args)) return@firstNotNullOfOrNull null
            val refreshed = JsonObject(args + ("verify_live" to JsonPrimitive(true)))
            if (observations.any { it.text("tool") == receipt.text("tool") && (it["arguments"] == refreshed || GitHubActionIdentity.matches(receipt.text("tool"), refreshed, it)) }) return@firstNotNullOfOrNull null
            val tool = tools.firstOrNull { it.name == receipt.text("tool") && it.modelVisible } ?: return@firstNotNullOfOrNull null
            if ((tool.inputSchema["properties"] as? JsonObject)?.containsKey("verify_live") != true || dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(refreshed, tool.inputSchema).isNotEmpty()) return@firstNotNullOfOrNull null
            call(tool.name, refreshed)
        }
        if (liveRead != null) return liveRead
        if (cleanup != null) return cleanup
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
        val fixtureAllowed = (remaining == null || remaining >= GitHubSelfTestFixtures.minimumCalls(tools) + GitHubChecklistBudget.VERIFICATION_RESERVE) &&
            fixturePreflight?.get("state") == JsonPrimitive("READY")
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

    fun rows(receipts: Map<String, JsonObject>, tools: List<AgentToolDefinition> = emptyList(), task: String = "", fixturePreflight: JsonObject? = null): JsonArray {
        val historical = receipts.values.filter { it["trusted"] == JsonPrimitive(true) || it["success"] == JsonPrimitive(false) && it["dispatched"] == JsonPrimitive(false) }
        val allTrusted = trusted(receipts)
        val readyReads = GitHubSelfTestReads.available(tools, allTrusted).associateBy { it.name.substringBefore("__") to it.arguments.text("action") }
        val pendingApprovals = GitHubPendingApproval.snapshot(receipts).filterIsInstance<JsonObject>()
        val active = catalog(tools)
        val integrationCoverage = integration(task)
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
                val successes = eligible.mapNotNull { receipt ->
                    if (receipt["success"] != JsonPrimitive(true) || data(receipt)?.text("schema") == "github.repeat.v1") {
                        null
                    } else if (GitHubPendingApproval.taskId(receipt) != null) {
                        GitHubPendingApproval.publication(receipt, allTrusted)
                    } else if ((capability.tool != "github_stage_patch" || (receipt["arguments"] as? JsonObject)?.get("commit_immediately") == JsonPrimitive(true)) && GitHubOperationOutcome.snapshot(receipt)["proposal_prepared"] == JsonPrimitive(true)) {
                        null
                    } else {
                        receipt
                    }
                }.distinct()
                val waitingApproval = eligible.any { receipt ->
                    GitHubPendingApproval.taskId(receipt)?.let { id -> pendingApprovals.any { it.text("engineering_task_id") == id } }
                        ?: (GitHubOperationOutcome.snapshot(receipt)["approval_pending"] == JsonPrimitive(true))
                }
                val failures = observed.filter { it["success"] != JsonPrimitive(true) }
                val latest = eligible.lastOrNull()
                val immediatePublication = capability.tool == "github_stage_patch" && (latest?.get("arguments") as? JsonObject)?.get("commit_immediately") == JsonPrimitive(true)
                val liveRequired = requiresLiveTransport(capability) || immediatePublication
                val transportCandidates = if (immediatePublication) successes.filter { (it["arguments"] as? JsonObject)?.get("commit_immediately") == JsonPrimitive(true) } else successes
                val transportMissing = liveRequired && transportCandidates.isNotEmpty() && transportCandidates.none { GitHubEvidenceOrigin.of(it) == "LIVE" }
                val unqualifiedStatus = when {
                    successes.any { GitHubEvidenceOrigin.of(it) == "CACHED" } -> "CACHED"
                    successes.any { GitHubEvidenceOrigin.of(it) == "MOCKED" } -> "MOCKED"
                    else -> "NOT_TESTED"
                }
                val incomplete = latest?.get("success") == JsonPrimitive(true) && data(latest)?.text("status") in setOf("PENDING_LOG_AVAILABILITY", "PENDING_CHECKS")
                val excluded = capability.tool == "github_context" && capability.action == "repositories"
                val unsupportedFixture = !readOnly(capability) && !GitHubSelfTestFixtures.supports(capability.tool, capability.action)
                val ciActionWithoutRun = capability.tool == "github_ci" && capability.action in setOf("dispatch", "rerun", "cancel") && eligible.isEmpty() && observed.isEmpty()
                val notRequested = GitHubChecklistScope.readOnly(task) && !readOnly(capability) || GitHubChecklistScope.supportedFixturePlan(task) && unsupportedFixture || ciActionWithoutRun
                val readyCall = if (authorized && readOnly(capability) && eligible.isEmpty()) readyReads[capability.tool to capability.action] else null
                val conditionalDecision = capability.tool == "github_decide" && observed.isEmpty()
                val fixtureApprovalConditional = !readOnly(capability) &&
                    GitHubSelfTestFixtures.supports(capability.tool, capability.action) &&
                    fixturePreflight?.get("state") != JsonPrimitive("READY") &&
                    !waitingApproval && GitHubSelfTestFixtures.checkpoint(receipts).isEmpty() && successes.isEmpty()
                val blocker = when {
                    fixtureApprovalConditional -> "Effective GitHub approval mode was checked before fixture creation. Native approval is manual, so this owned fixture is conditional and no branch or mutation was started. Enable fixture-only auto approval for codex-self-test/* or run the approval interaction harness."
                    ciActionWithoutRun -> "No workflow run or job ID was supplied or deliberately created for this task. This CI mutation is conditional and was not requested."
                    notRequested -> if (unsupportedFixture && GitHubChecklistScope.supportedFixturePlan(task)) "No supported owned-resource cleanup recipe is admitted for this action. It is outside this preset, remains untested, and requires a separate authorized fixture lifecycle." else "Write operations are excluded by the requested read-only scope."
                    excluded -> "Account-wide repository discovery is outside this workspace's selected repository scope."
                    eligible.isEmpty() && observed.isNotEmpty() -> "Current catalog excludes the historical receipts; reauthorize this operation before using them as proof."
                    waitingApproval -> "The exact staged patch is waiting for its existing Approve & Commit card. Generic continuation cannot grant approval; preserve the task and fixture."
                    transportMissing -> "Saved, local or mocked responses cannot qualify this remote operation; an authorized live read or verified mutation is still required."
                    incomplete -> "The endpoint responded but traversal or log availability remains incomplete; follow the saved continuation or wait for the recorded state change."
                    failures.isNotEmpty() && (successes.isEmpty() || latest?.get("success") == JsonPrimitive(false)) -> "Resolve recorded errors: " + failures.map { it.text("error_code") }.distinct().joinToString(", ") + ". " + GitHubVerificationRecovery.advice(failures)
                    readyCall != null -> "Ready to execute using retained repository/resource evidence; no qualifying execution has been observed."
                    conditionalDecision -> "Conditional user-decision behavior has no genuine decision in this task. It remains untested; test it in the interaction harness."
                    capability.tool == "github_watch_checks" -> "Requires an observed pull request or workflow run and the active chat subscription."
                    !readOnly(capability) -> GitHubSelfTestFixtures.prerequisite(capability.tool, capability.action)
                    capability.tool == "github_issue" -> "No issue identifier observed from the issue inventory; a pull request number cannot satisfy this dependency."
                    capability.tool == "github_pr" -> "No pull request identifier observed from the pull request inventory."
                    capability.tool == "github_ci" -> "Requires an observed workflow/run and a completed job for logs or a failed completed run for failure analysis."
                    capability.tool == "github_release" -> "Requires a tag or release observed in the repository inventory."
                    else -> "Requires an authorized tool and its observed repository revision or source path."
                }
                val status = when {
                    notRequested -> "NOT_REQUESTED"
                    excluded -> "OUT_OF_SCOPE"
                    waitingApproval -> "WAITING_FOR_APPROVAL"
                    fixtureApprovalConditional -> "CONDITIONAL"
                    incomplete || tools.isNotEmpty() && !authorized -> "BLOCKED"
                    latest?.get("success") == JsonPrimitive(false) -> if (latest["dispatched"] == JsonPrimitive(false)) "PREFLIGHT_REJECTED" else "FAILED"
                    transportMissing -> unqualifiedStatus
                    successes.isNotEmpty() && failures.isNotEmpty() -> "RECOVERED"
                    successes.isNotEmpty() -> "PASSED"
                    failures.isNotEmpty() && failures.all { it["dispatched"] == JsonPrimitive(false) } -> "PREFLIGHT_REJECTED"
                    failures.isNotEmpty() -> "FAILED"
                    readyCall != null || conditionalDecision || capability.automatic && tools.any { it.name.substringBefore("__") == capability.tool } -> "UNTESTED"
                    else -> "BLOCKED"
                }
                val blockerCode = when {
                    status in setOf("PASSED", "RECOVERED") -> null
                    notRequested -> GitHubCriterionBlocker.OUT_OF_SCOPE
                    excluded -> GitHubCriterionBlocker.REPOSITORY_SCOPE_EXCLUDED
                    tools.isNotEmpty() && !authorized -> GitHubCriterionBlocker.AUTHORIZATION_REQUIRED
                    eligible.isEmpty() && observed.isNotEmpty() -> GitHubCriterionBlocker.HISTORICAL_CONTRACT_CHANGED
                    waitingApproval -> GitHubCriterionBlocker.APPROVAL_REQUIRED
                    fixtureApprovalConditional -> GitHubCriterionBlocker.FIXTURE_APPROVAL_MODE_REQUIRED
                    transportMissing -> GitHubCriterionBlocker.LIVE_EVIDENCE_REQUIRED
                    incomplete -> GitHubCriterionBlocker.CONTINUATION_REQUIRED
                    status == "PREFLIGHT_REJECTED" -> GitHubCriterionBlocker.PRECHECK_REJECTED
                    status == "FAILED" -> GitHubCriterionBlocker.OPERATION_FAILED
                    status == "UNTESTED" -> GitHubCriterionBlocker.EXECUTION_NOT_EXERCISED
                    capability.tool == "github_decide" -> GitHubCriterionBlocker.USER_DECISION_REQUIRED
                    capability.tool == "github_watch_checks" -> GitHubCriterionBlocker.CHECK_SUBSCRIPTION_REQUIRED
                    !readOnly(capability) -> GitHubCriterionBlocker.OWNED_FIXTURE_REQUIRED
                    capability.tool == "github_issue" -> GitHubCriterionBlocker.ISSUE_ID_REQUIRED
                    capability.tool == "github_pr" -> GitHubCriterionBlocker.PULL_REQUEST_ID_REQUIRED
                    capability.tool == "github_ci" -> GitHubCriterionBlocker.WORKFLOW_RUN_OR_JOB_REQUIRED
                    capability.tool == "github_release" -> GitHubCriterionBlocker.RELEASE_OR_TAG_REQUIRED
                    else -> GitHubCriterionBlocker.REPOSITORY_INPUT_REQUIRED
                }
                buildJsonObject {
                    put("id", "${capability.tool}:${capability.action}")
                    put("scope", capability.scope)
                    put("required", integrationCoverage && !excluded && !notRequested && !conditionalDecision)
                    put(
                        "applicability",
                        if (ciActionWithoutRun) {
                            "CONDITIONAL_RESOURCE_NOT_PRESENT"
                        } else if (fixtureApprovalConditional) {
                            "CONDITIONAL_APPROVAL_MODE"
                        } else if (conditionalDecision) {
                            "CONDITIONAL_USER_DECISION"
                        } else if (waitingApproval) {
                            "WAITING_FOR_APPROVAL"
                        } else if (readyCall != null) {
                            "READY_UNTESTED"
                        } else if (!readOnly(capability) && successes.isEmpty() && !waitingApproval) {
                            "OWNED_FIXTURE_REQUIRED"
                        } else {
                            "APPLICABLE"
                        }
                    )
                    readyCall?.let {
                        put(
                            "next_call",
                            buildJsonObject {
                                put("tool", it.name)
                                put("arguments", it.arguments)
                            }
                        )
                    }
                    put("acceptance_role", if (integrationCoverage) "INTEGRATION_COVERAGE" else "DIAGNOSTIC_ONLY")
                    put("status", status)
                    put("acceptance_state", if (waitingApproval) "WAITING_FOR_APPROVAL" else GitHubAcceptanceLedger.state(status).name)
                    blockerCode?.let { put("blocker_code", it.name) }
                    put("evidence_origins", JsonArray(successes.ifEmpty { eligible }.map { GitHubEvidenceOrigin.of(it) }.distinct().map(::JsonPrimitive)))
                    put("historical_evidence_origins", JsonArray(eligible.map { GitHubEvidenceOrigin.of(it) }.distinct().map(::JsonPrimitive)))
                    if (eligible.any { GitHubPendingApproval.publication(it, allTrusted) != null }) {
                        put("publication_evidence", "REMOTE_STATE_VERIFIED")
                        put("publication_dispatch_attribution", "UNAVAILABLE_UNLESS_SEPARATELY_RECORDED")
                    }
                    put("live_verified", successes.any { GitHubEvidenceOrigin.of(it) == "LIVE" })
                    put("response_validated", eligible.any { it["success"] == JsonPrimitive(true) })
                    put("waiting_for_approval", waitingApproval)
                    put("requires_live_transport", liveRequired)
                    if (capability.tool == "github_stage_patch") put("publication_mode", if (immediatePublication) "IMMEDIATE_PUBLICATION" else "STAGED_ONLY")
                    put("fixture_state", if (readOnly(capability)) "NOT_APPLICABLE" else "REQUIRES_SEPARATE_VERIFICATION")
                    put(
                        "readiness",
                        buildJsonObject {
                            put("discovered", discovered)
                            val denied = latest?.text("error_code")?.let { code -> GitHubVerificationRecovery.authorizationBlocked(code) } == true
                            val revalidation = latest?.text("error_code") in dev.chungjungsoo.gptmobile.data.agent.ToolFailureMetadata.authorityInvalidationCodes
                            val authorization = when {
                                revalidation -> "REVALIDATION_REQUIRED"
                                denied -> "DENIED"
                                tools.isEmpty() -> "UNKNOWN"
                                authorized -> "AUTHORIZED"
                                else -> "NOT_AUTHORIZED"
                            }
                            put("schema", "github.capability-readiness.v1")
                            put("authorization_state", authorization)
                            put("authorization", authorization)
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
        if (!integration(task)) return false
        val requirements = GitHubIntegrationAcceptance.requirements(task, receipts, tools)
        return requirements.isNotEmpty() && requirements.all { (it as JsonObject)["acceptance_state"] == JsonPrimitive("PASS") }
    }

    /** Action coverage is one acceptance criterion, independent from build/device criteria. */
    fun capabilitiesComplete(task: String, receipts: Map<String, JsonObject>, tools: List<AgentToolDefinition>, fixturePreflight: JsonObject? = null): Boolean {
        if (tools.isEmpty()) return false
        if (GitHubAuthorizedCatalog.required(tools, receipts) && GitHubAuthorizedCatalog.snapshot(receipts, tools)["complete"] != JsonPrimitive(true)) return false
        val mutations = catalog(tools).filter { !readOnly(it) && it.tool != "github_decide" && !GitHubChecklistScope.readOnly(task) && (!GitHubChecklistScope.supportedFixturePlan(task) || GitHubSelfTestFixtures.supports(it.tool, it.action)) }
        if (mutations.any { !GitHubSelfTestFixtures.supports(it.tool, it.action) }) return false
        if (mutations.isNotEmpty() && !GitHubSelfTestFixtures.complete(receipts)) return false
        val coverage = rows(receipts, tools, task, fixturePreflight).filter { (it as JsonObject)["required"] != JsonPrimitive(false) }
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
        val waiting = rows.count { it.text("status") == "WAITING_FOR_APPROVAL" }
        val conditional = rows.count { it.text("status") == "CONDITIONAL" }
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
        return "Checks: $completed/${rows.size} complete" + (if (waiting > 0) "; $waiting waiting for approval" else "") + (if (conditional > 0) "; $conditional conditional before mutation" else "") + (if (failed > 0) "; $failed failed" else "") + (if (blocked > 0) "; $blocked blocked" else "") + (if (excluded > 0) "; $excluded excluded from scope" else "") + ". $nextAction"
    }

    /** Repeating identical observations is history, not forward execution progress. */
    fun progressDigest(task: String, receipts: Map<String, JsonObject>, tools: List<AgentToolDefinition>, fixturePreflight: JsonObject? = null): String = GitHubEvidenceBook.digest(
        buildJsonObject {
            put("request_digest", GitHubEvidenceBook.digest(task))
            fixturePreflight?.let { put("fixture_approval_preflight", it) }
            if (integration(task)) put("requirements", JsonArray(GitHubIntegrationAcceptance.requirements(task, receipts, tools, fixturePreflight = fixturePreflight).map { raw -> JsonObject((raw as JsonObject).filterKeys { it in setOf("id", "status", "acceptance_state") }) }))
            put(
                "capabilities",
                JsonArray(
                    rows(receipts, tools, task, fixturePreflight).map { raw ->
                        JsonObject((raw as JsonObject).filterKeys { it in setOf("id", "required", "status", "acceptance_state", "blocker_code", "live_verified", "waiting_for_approval") })
                    }
                )
            )
            put("pending_pages", JsonArray(pendingPages(receipts).sortedBy { it.toString() }))
            put(
                "pending_approvals",
                JsonArray(
                    GitHubPendingApproval.snapshot(receipts).map { raw ->
                        JsonObject((raw as JsonObject).filterKeys { it in setOf("engineering_task_id", "state", "repository", "branch", "expected_head_sha", "stage_id") })
                    }
                )
            )
            put(
                "fixtures",
                JsonArray(
                    GitHubSelfTestFixtures.checkpoint(receipts).map { raw ->
                        JsonObject((raw as JsonObject).filterKeys { it in setOf("branch", "fixture_plan", "ownership_verified", "content_verified", "final_state_verified", "cleanup_status", "lifecycle_state", "lifecycle_reason") })
                    }
                )
            )
        }.toString()
    )

    fun checkpoint(task: String, receipts: Map<String, JsonObject>, tools: List<AgentToolDefinition> = emptyList(), remainingToolCalls: Int? = null, fixturePreflight: JsonObject? = null): JsonObject = buildJsonObject {
        put("schema", "github.test-checklist.v3")
        put("request_digest", GitHubEvidenceBook.digest(task))
        val capabilities = rows(receipts, tools, task, fixturePreflight)
        put("capabilities", capabilities)
        val requirements = if (integration(task)) {
            GitHubIntegrationAcceptance.requirements(task, receipts, tools, fixturePreflight = fixturePreflight)
        } else {
            JsonArray(
                GitHubRequestCompletion.requirements(task).map { requirement ->
                    buildJsonObject {
                        put("id", GitHubRequestCompletion.id(requirement))
                        put("requirement", requirement)
                        put("required", true)
                        put("acceptance_state", "NOT_TESTED")
                        put("acceptance", "Verify the requested output against matching receipts; unrelated API capabilities are diagnostic inventory only.")
                    }
                }
            )
        }
        put("requirements", requirements)
        GitHubReviewComparison.plan(task, tools, receipts)?.let { put("review_comparison", it.checkpoint) }
        put("remaining_requirements", JsonArray(requirements.filter { (it as JsonObject)["acceptance_state"] != JsonPrimitive("PASS") }))
        put("authorized_catalog", GitHubAuthorizedCatalog.snapshot(receipts, tools))
        put("runtime_binding", GitHubIntegrationAcceptance.binding(receipts.values))
        fixturePreflight?.let { put("fixture_approval_preflight", it) }
        put("evidence_classes", GitHubIntegrationAcceptance.evidenceClasses(receipts))
        put("stage_budget", GitHubChecklistBudget.checkpoint(receipts, remainingToolCalls, GitHubSelfTestFixtures.minimumCalls(tools)))
        put(
            "resume_plan",
            buildJsonObject {
                put("source", "Trusted receipts revalidated against the current authorization catalog")
                put("pending_capabilities", JsonArray(capabilities.filter { (it as JsonObject)["required"] == JsonPrimitive(true) && it.text("status") !in setOf("PASSED", "RECOVERED", "OUT_OF_SCOPE", "NOT_REQUESTED") }))
                put("cleanup_first", false)
                put("cleanup_policy", "Finish required live existence reads before teardown; preserve cleanup reserve and reconcile exact owned-ref absence without repeating deletion.")
                put("mutation_retry_policy", "Reconcile failed or uncertain writes; never replay a completed mutation or repeat a write to recover its response.")
            }
        )
        put("acceptance_ledger", GitHubAcceptanceLedger.snapshot(requirements = requirements, capabilities = capabilities, requestDigest = GitHubEvidenceBook.digest(task)))
        put("coverage_source", if (tools.isEmpty()) "BASELINE_CATALOG" else "AUTHORIZED_TOOL_ACTION_SCHEMAS")
        put("pagination_policy", "Reuse complete persisted originals and their exact remote recipes; consume display fragments only when no complete original was retained; up to 32 definition pages per revision independently of two remote inventory samples, or eight while locating an issue dependency, unless complete inventory is explicitly requested; restore retained pages locally before one bounded read-only source recovery")
        val acceptance = if (integration(task)) {
            listOf("Each requested capability has a result or explicit untested/blocker reason", "Every claim cites matching branch/PR/SHA receipts", "Complete durable sources or required local fragments, and remote pages, consumed or explicitly pending", "Application test results bound to newly executed runs at tested SHA", "Coverage, measured score or limitation, failures and solutions retained", "Authorized isolated write fixtures verified and cleaned up")
        } else {
            listOf("Only the user's requested outputs are required acceptance criteria", "Every claim cites matching branch/PR/SHA receipts", "Read-only reviews require exact comparison revisions and relevant source reads, not unrelated mutation fixtures", "Unverified outputs and pending remote pages remain explicit")
        }
        put("acceptance", JsonArray(acceptance.map(::JsonPrimitive)))
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
        val counts = listOf("PASSED", "CACHED", "MOCKED", "FAILED", "PREFLIGHT_REJECTED", "BLOCKED", "CONDITIONAL", "WAITING_FOR_APPROVAL", "UNTESTED", "NOT_TESTED", "RECOVERED", "OUT_OF_SCOPE", "NOT_REQUESTED").associateWith { status -> rows.count { (it as JsonObject).text("status") == status } }
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
            receipt["success"] == JsonPrimitive(true) &&
                data(receipt)?.text("schema") != "github.repeat.v1" &&
                (receipt["arguments"] as? JsonObject)?.text("action") in setOf("workflows", "branches", "list", "commits", "runs", "tags")
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
                val inventories = byPage.toSortedMap().values.map(GitHubInventoryObservation::of)
                val canonical = inventories.all { it != null }
                val arrays = pages.mapNotNull { page -> (page["workflows"] ?: page["items"] ?: page["pull_requests"] ?: page["workflow_runs"] ?: page["tags"] ?: page["recent_commits"] ?: page["commits"] ?: page["issues"] ?: page["releases"] ?: page["branches"]) as? JsonArray }
                val records = arrays.flatMap { it.mapNotNull { item -> item as? JsonObject } }
                val identities = if (canonical) inventories.flatMap { (it?.get("record_keys") as? JsonArray).orEmpty() }.distinct() else records.mapNotNull { item -> item["id"] ?: item["number"] ?: item["name"] ?: item["sha"] }.distinct()
                val fetched = if (canonical) inventories.sumOf { (it?.get("fetched") as? JsonPrimitive)?.longOrNull ?: 0 } else records.size.toLong()
                val displayed = inventories.mapNotNull { (it?.get("displayed") as? JsonPrimitive)?.longOrNull }.takeIf { it.size == inventories.size }?.sum()
                val coveragePages = if (canonical) inventories.filterNotNull() else pages
                val totals = coveragePages.mapNotNull { (it["total_count"] as? JsonPrimitive)?.longOrNull }.distinct()
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
                    (1L until end).all { page ->
                        val receipt = requireNotNull(byPage[page])
                        (GitHubInventoryObservation.of(receipt) ?: data(receipt))?.get("has_more") == JsonPrimitive(true)
                    }
                val unresolvedFailure = trusted.withIndex().any { (index, failed) ->
                    if (failed["success"] == JsonPrimitive(true) || !belongs(failed)) return@any false
                    val later = trusted.drop(index + 1)
                    val recovery = savedReadRecovery(failed)
                    val reopened = recovery != null && later.any { it["success"] == JsonPrimitive(true) && it["tool"] == recovery["tool"] && it["arguments"] == recovery["arguments"] }
                    !reopened && later.none { it["success"] == JsonPrimitive(true) && it["tool"] == failed["tool"] && it["arguments"] == failed["arguments"] }
                }
                val complete = contiguous &&
                    coveragePages.lastOrNull()?.get("has_more") == JsonPrimitive(false) &&
                    coveragePages.none { it["complete"] == JsonPrimitive(false) } &&
                    canonical &&
                    inventories.all { it?.get("record_keys_complete") == JsonPrimitive(true) } &&
                    identities.size.toLong() == fetched &&
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
                    put("fetched", if (canonical) JsonPrimitive(fetched) else kotlinx.serialization.json.JsonNull)
                    put("fetched_lower_bound", fetched)
                    put("fetched_count_scope", if (canonical) "CANONICAL_RETURNED_PAGES" else "RETAINED_PROJECTION_ONLY")
                    put("displayed", displayed?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                    put("consumed", kotlinx.serialization.json.JsonNull)
                    put("consumption_status", "NOT_RECORDED")
                    put("retained_review_records", records.size)
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
