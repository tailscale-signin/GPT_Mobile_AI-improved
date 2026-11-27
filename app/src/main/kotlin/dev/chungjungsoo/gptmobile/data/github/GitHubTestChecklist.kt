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

/** Receipt-derived checkpoints survive restarts without replaying completed writes. */
internal object GitHubTestChecklist {
    private data class Capability(val tool: String, val action: String, val scope: String, val automatic: Boolean = false)
    private val capabilities = listOf(
        Capability("github_context", "status", "Repository status", true),
        Capability("repository_graph", "status", "Engineering gateway graph status", true),
        Capability("repository_workspace", "catalog", "Host-approved offline workspace checks", true),
        Capability("github_context", "checks", "Checks for selected SHA", true),
        Capability("github_context", "branches", "Branch inventory", true),
        Capability("github_context", "commits", "Recent commits", true),
        Capability("github_pr", "list", "Pull request inventory", true),
        Capability("github_issue", "list", "Issue inventory", true),
        Capability("github_release", "list", "Release inventory", true),
        Capability("github_release", "tags", "Tag inventory", true),
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
        put(
            "state",
            if (automatic) {
                "READY"
            } else if (mode == null) {
                "UNKNOWN"
            } else {
                "CONDITIONAL"
            }
        )
        put("mutation_dispatched", false)
        put("ordinary_tool_auto_approval_is_fixture_authority", false)
        put("approval_source", "TASK_BOUND_FIXTURE_POLICY")
        put("completed_read_receipts_reusable", true)
        put("read_only_alternative", "Start an Inspect existing fixtures only workflow to qualify read-only coverage without creating or modifying fixtures.")
        put("runtime_identity", runtime)
        put("scope", "AUTO_APPROVE applies only to the dedicated codex-self-test/* fixture branch; regular repository writes still use their configured approval policy.")
        put(
            "reason",
            if (automatic) {
                "The effective GitHub decision mode was verified before fixture creation. Fixture-only native auto-approval is restricted to the owned codex-self-test/* branch."
            } else {
                if (mode == null) {
                    "The effective GitHub approval mode could not be verified. Recheck the bound profile and task before preparing a fixture."
                } else {
                    "This task retains its accepted fixture policy. General tool auto-approval does not authorize fixture changes. Use its exact approval interaction, or select fixture-only automatic approval before starting a new fixture test. Completed reads remain retained."
                }
            }
        )
    }
    private fun trusted(receipts: Map<String, JsonObject>) = receipts.values.filter { it["trusted"] == JsonPrimitive(true) && it["verification_eligible"] != JsonPrimitive(false) }
    private fun matches(receipt: JsonObject, capability: Capability): Boolean = receipt.text("tool").githubOperationFamily() == capability.tool &&
        (receipt["arguments"] as? JsonObject)?.containsKey("view_cursor") != true &&
        (capability.action.isBlank() || (receipt["arguments"] as? JsonObject)?.text("action") == capability.action)

    internal fun sameCall(receipt: JsonObject, recipe: JsonObject, issuer: JsonObject? = null): Boolean {
        if (issuer != null && (issuer["binding"] != receipt["binding"] || issuer["repository"] != receipt["repository"])) return false
        val arguments = recipe["arguments"] as? JsonObject ?: return false
        if (receipt.text("tool") != recipe.text("tool")) return false
        val actual = receipt["arguments"] as? JsonObject ?: return false
        if (recipe.text("tool").githubOperationFamily() == "github_context" &&
            arguments["action"] == JsonPrimitive("changes") &&
            actual["action"] == JsonPrimitive("changes") &&
            "view_cursor" !in arguments &&
            "view_cursor" !in actual
        ) {
            val observedSize = data(receipt)?.let(GitHubComparisonPage::observedSize) ?: return false
            val issuingData = issuer?.let(::data)
            val issuingSize = issuingData?.let(GitHubComparisonPage::observedSize)
            if (issuingData != null && issuingSize == null) return false
            val issuedSize = if (issuingData != null && "effective_per_page" !in issuingData) {
                GitHubComparisonPage.DEFAULT_SIZE
            } else {
                (arguments["per_page"] as? JsonPrimitive)?.intOrNull ?: issuingSize ?: GitHubComparisonPage.DEFAULT_SIZE
            }
            val expected = comparisonPageArguments(recipe.text("tool"), arguments, issuer?.text("repository"), issuedSize)
            val observed = comparisonPageArguments(receipt.text("tool"), actual, issuer?.text("repository"), observedSize)
            return observed == expected ||
                observed["verify_live"] == JsonPrimitive(true) &&
                expected["verify_live"] != JsonPrimitive(true) &&
                observed == JsonObject(expected + ("verify_live" to JsonPrimitive(true)))
        }
        if (receipt["arguments"] == arguments || GitHubActionIdentity.matches(recipe.text("tool"), arguments, receipt)) return true
        if (actual["verify_live"] != JsonPrimitive(true) || arguments["verify_live"] == JsonPrimitive(true)) return false
        val live = JsonObject(arguments + ("verify_live" to JsonPrimitive(true)))
        return actual == live || GitHubActionIdentity.matches(recipe.text("tool"), live, receipt)
    }

    /** Receipts use their observed size; legacy native comparisons explicitly retain their fixed 100 rows. */
    internal fun comparisonPageArguments(tool: String, arguments: JsonObject, repository: String? = null, effectivePageSize: Int? = null): JsonObject {
        if (tool.githubOperationFamily() != "github_context" || arguments["action"] != JsonPrimitive("changes") || "view_cursor" in arguments) return arguments
        val target = repository?.lowercase(java.util.Locale.ROOT)?.split('/')?.takeIf { it.size == 2 && it.all(String::isNotBlank) }
        val sameTarget = target != null &&
            ("owner" !in arguments && "repo" !in arguments || arguments.text("owner").lowercase(java.util.Locale.ROOT) == target[0] && arguments.text("repo").lowercase(java.util.Locale.ROOT) == target[1])
        val scope = if (sameTarget) mapOf("owner" to JsonPrimitive(requireNotNull(target)[0]), "repo" to JsonPrimitive(target[1])) else emptyMap()
        return JsonObject(
            arguments + scope + mapOf(
                "page" to (arguments["page"] ?: JsonPrimitive(1)),
                "per_page" to (effectivePageSize?.let(::JsonPrimitive) ?: arguments["per_page"] ?: JsonPrimitive(GitHubComparisonPage.DEFAULT_SIZE)),
                "include_patches" to (arguments["include_patches"] ?: JsonPrimitive(false)),
                "verify_live" to (arguments["verify_live"] ?: JsonPrimitive(false))
            )
        )
    }

    /** Retain the exact issued page chain even when cursor-only calls omit the root action. */
    fun paginationReceipts(receipts: Map<String, JsonObject>, root: (JsonObject) -> Boolean): Map<String, JsonObject> {
        val observations = receipts.filterValues { it["trusted"] == JsonPrimitive(true) && it["verification_eligible"] != JsonPrimitive(false) }
        val selected = observations.filterValues(root).toMutableMap()
        var frontier = selected.values.toList()
        while (frontier.isNotEmpty()) {
            val issued = frontier.flatMap { issuer -> issuedPages(issuer).map { issuer to it } }
            val next = observations.filter { (id, receipt) ->
                id !in selected && issued.any { (issuer, recipe) -> sameCall(receipt, recipe, issuer) }
            }
            selected.putAll(next)
            frontier = next.values.toList()
        }
        return selected
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
        data class Page(val issuer: JsonObject, val recipe: JsonObject)
        val pages = observations.flatMap { issuer ->
            issuedPages(issuer).filter { recipe ->
                val local = (recipe["arguments"] as? JsonObject)?.containsKey("view_cursor") == true
                (local || !retired(issuer)) && completed.none { sameCall(it, recipe, issuer) } && recipe.text("tool") to recipe["arguments"] !in recovered
            }.map { Page(issuer, it) }
        }.distinctBy { listOf(it.issuer["binding"], it.issuer["repository"], it.issuer["source_head_sha"], it.recipe) }
        return pages.filter { weak ->
            val args = weak.recipe["arguments"] as? JsonObject ?: return@filter true
            pages.none { strong ->
                val bound = strong.recipe["arguments"] as? JsonObject ?: return@none false
                if (strong === weak ||
                    strong.recipe.filterKeys { it != "arguments" } != weak.recipe.filterKeys { it != "arguments" } ||
                    strong.issuer["binding"] != weak.issuer["binding"] ||
                    strong.issuer["repository"] != weak.issuer["repository"] ||
                    strong.issuer["source_head_sha"] != weak.issuer["source_head_sha"]
                ) {
                    return@none false
                }
                val upgrades = bound.keys - args.keys
                val strongerLive = bound["verify_live"] == JsonPrimitive(true) && args["verify_live"] != JsonPrimitive(true)
                val same = args.all { (key, value) -> if (key == "verify_live" && strongerLive) true else bound[key] == value }
                same &&
                    (upgrades.isNotEmpty() || strongerLive) &&
                    upgrades.all { key ->
                        when (key) {
                            "verify_live" -> bound[key] == JsonPrimitive(true)
                            "expected_head_sha", "commit_sha" -> bound[key] == weak.issuer["source_head_sha"]
                            "expected_blob_sha" -> bound[key] == weak.issuer["source_blob_sha"]
                            else -> false
                        }
                    }
            }
        }.map { it.recipe }
    }

    /** UI resume proof is re-derived from current, authorized receipts; it never retries the dead cursor. */
    fun sourceReadRecovery(tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>): JsonObject? {
        if (GitHubPendingApproval.snapshot(receipts).isNotEmpty() || receipts.values.any { GitHubExecutionLedger.receiptState(it) == GitHubExecutionLedger.ReceiptState.UNKNOWN_OUTCOME }) return null
        val next = continuation("", tools, receipts, localOnly = false) ?: return null
        if ("view_cursor" in next.arguments) return null
        val observations = trusted(receipts)
        val failed = observations.lastOrNull { receipt ->
            receipt.text("error_code") == "SAVED_VIEW_UNAVAILABLE" && savedReadRecovery(receipt)?.let { it.text("tool") == next.name && it["arguments"] == next.arguments } == true
        } ?: return null
        if (failed.text("call_id").isBlank() || failed.text("binding").isBlank()) return null
        val source = observations.lastOrNull { issuer ->
            issuer["success"] == JsonPrimitive(true) &&
                issuer["binding"] == failed["binding"] &&
                issuer["repository"] == failed["repository"] &&
                sameCall(
                    issuer,
                    buildJsonObject {
                        put("tool", next.name)
                        put("arguments", next.arguments)
                    },
                    failed
                ) &&
                issuedPages(issuer).any { sameCall(failed, it, issuer) }
        } ?: return null
        if (source.text("receipt_id").isBlank()) return null
        return buildJsonObject {
            put("schema", "github.saved-read-recovery.v1")
            put("available", true)
            put("read_only", true)
            put("source_bound", true)
            put("failed_call_id", failed.text("call_id"))
            put("tool_name", next.name)
            put("arguments", next.arguments)
            put("source_receipt_id", source.text("receipt_id"))
            put("binding", source.getValue("binding"))
            source["source_head_sha"]?.let { put("source_head_sha", it) }
        }
    }

    /** An argument repair is admissible only for an exact local cursor returned by this facade. */
    private fun savedReadRecovery(receipt: JsonObject): JsonObject? {
        if (receipt["success"] == JsonPrimitive(true)) return null
        if (receipt.text("error_code") == "GITHUB_PAGINATION_INCOMPLETE") return pullPageCorrection(receipt)
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
                    root.text("operation") != receipt.text("tool").githubOperationFamily() ||
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
            name.githubOperationFamily() == "repository_graph" &&
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
        if (receipt["success"] != JsonPrimitive(true)) return listOfNotNull(pullPageCorrection(receipt))
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

    /** Incomplete coverage can issue one native read for an observed gap without granting another query. */
    private fun pullPageCorrection(receipt: JsonObject): JsonObject? {
        val name = receipt.text("tool")
        if (receipt["trusted"] != JsonPrimitive(true) ||
            receipt["success"] != JsonPrimitive(false) ||
            receipt["verification_eligible"] == JsonPrimitive(false) ||
            receipt["repository_role"] == JsonPrimitive("READ_SOURCE") ||
            name.githubOperationFamily() != "github_pr" ||
            receipt.text("error_code") != "GITHUB_PAGINATION_INCOMPLETE"
        ) {
            return null
        }
        val original = receipt["arguments"] as? JsonObject ?: return null
        val body = data(receipt) ?: return null
        val progress = body["pagination_progress"] as? JsonObject ?: return null
        val gap = (progress["first_unobserved_page"] as? JsonPrimitive)?.takeIf { !it.isString && it.intOrNull in 1..1000 } ?: return null
        val repair = body["correction_call"] as? JsonObject ?: return null
        if (repair.text("tool") != name ||
            progress.text("schema") != "github.pagination-progress.v1" ||
            progress["complete"] != JsonPrimitive(false) ||
            progress["upstream_changed_during_traversal"] == JsonPrimitive(true) ||
            original.containsKey("view_cursor") ||
            gap == original["page"]
        ) {
            return null
        }
        val expected = when (original.text("action")) {
            "list" -> JsonObject(original + ("page" to gap))
            "context" -> GitHubPullFileInventory.recoveryArguments(original, body, gap)
            else -> null
        } ?: return null
        return repair.takeIf { it["arguments"] == expected }
    }

    private fun remoteSamples(observations: List<JsonObject>): Int {
        val issued = observations.flatMap { issuer -> issuedPages(issuer).map { issuer to it } }
        return observations.count { receipt ->
            val args = receipt["arguments"] as? JsonObject ?: return@count false
            "view_cursor" !in args &&
                !(receipt.text("tool").githubOperationFamily() == "github_ci" && args.text("action") == "definitions" && "index_cursor" in args) &&
                ((args["page"] as? JsonPrimitive)?.longOrNull?.let { it > 1 } == true || issued.any { (issuer, recipe) -> sameCall(receipt, recipe, issuer) })
        }
    }

    /** Spend the next bounded page on an observed dependency or selected revision before broad history. */
    private fun pagePriority(recipe: JsonObject, observations: List<JsonObject>): Int {
        val arguments = recipe["arguments"] as? JsonObject ?: return 4
        if ("view_cursor" in arguments) return 0
        val tool = recipe.text("tool").githubOperationFamily()
        val action = arguments.text("action")
        val head = observations.asReversed().firstNotNullOfOrNull { receipt ->
            if (receipt["success"] == JsonPrimitive(true) && receipt.text("tool").githubOperationFamily() == "github_context" && (receipt["arguments"] as? JsonObject)?.text("action") == "status") data(receipt)?.get("commit_sha") else null
        }
        if (tool == "github_ci" && action == "definitions") return 1
        if (tool == "github_issue" &&
            action == "list" &&
            observations.none { receipt ->
                receipt["success"] == JsonPrimitive(true) &&
                    receipt.text("tool").githubOperationFamily() == tool &&
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

    /** Prioritize local fragments and correctable page gaps while their observations are still live. */
    private fun continuation(task: String, tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>, localOnly: Boolean, workflowDependencyOnly: Boolean = false): ProviderEvent.ToolCall? {
        val observations = trusted(receipts)
        fun same(receipt: JsonObject, recipe: JsonObject) = sameCall(receipt, recipe)
        fun permitted(recipe: JsonObject): Boolean {
            val args = recipe["arguments"] as? JsonObject ?: return false
            val name = recipe.text("tool")
            val graphWrite = name.githubOperationFamily() == "repository_graph" && args.text("action") in setOf("index", "note")
            return !graphWrite &&
                tools.any { it.modelVisible && it.name == name && GitHubSelfTestReads.valid(it, args) } &&
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
            if (workflowDependencyOnly) continue
            val recovery = savedReadRecovery(failed) ?: continue
            val pullGap = failed.text("error_code") == "GITHUB_PAGINATION_INCOMPLETE"
            if (pullGap && !exhaustive && sampled >= 2) continue
            val source = recoverySource(recovery) ?: continue
            if (!considered.add(source)) continue
            val args = recovery["arguments"] as JsonObject
            if (localOnly && !pullGap && "view_cursor" !in args || !permitted(recovery) || same(failed, recovery)) continue
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
            val workflowDependency = observations.any { issuer ->
                page in issuedPages(issuer) && GitHubSelfTestWorkflowJobs.dependencyPage(page, tools, observations, issuer)
            }
            if (workflowDependencyOnly && !workflowDependency) continue
            // This independently bounded selected-SHA acquisition finds an
            // executed job, without expanding unrelated inventory or the budget.
            if (workflowDependency) return call(page.text("tool"), args)
            // Required local fragments and issue dependencies are never stopped by
            // an unrelated inventory's sampling allowance.
            val issueDependency = page.text("tool").githubOperationFamily() == "github_issue" &&
                args.text("action") == "list" &&
                observations.none { receipt ->
                    receipt.text("tool").githubOperationFamily() == "github_issue" &&
                        receipt["success"] == JsonPrimitive(true) &&
                        data(receipt)?.let { root ->
                            listOf("issues", "items").any { field ->
                                (root[field] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().any { "pull_request" !in it }
                            }
                        } == true
                }
            val definitionPage = page.text("tool").githubOperationFamily() == "github_ci" && args.text("action") == "definitions" && "index_cursor" in args
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
            // Missing resource dependencies must not trigger unbounded remote inventory traversal.
            // Preserve local view fragments, but cap sampled remote pages for ordinary tests.
            if (!exhaustive && "view_cursor" !in args && sampled >= 8) continue
            if (!exhaustive && !required && sampled >= 2) continue
            // Every exact issued, authorized read is eligible, including tags,
            // issue history/comments and log-offset pages. All share the same cap.
            return call(page.text("tool"), args)
        }
        return null
    }

    /** Unknown graph writes cannot prevent unrelated reads or authorize fixture cleanup. */
    fun nextIndependentRead(task: String, tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>, remainingToolCalls: Int? = null): ProviderEvent.ToolCall? {
        if (!integration(task)) return null
        val nativeTools = tools.filter { it.name.githubOperationFamily() != "repository_graph" }
        return nextCall(task, nativeTools, receipts, remainingToolCalls, independentReadsOnly = true)
    }

    fun schedule(task: String, authorized: List<AgentToolDefinition>, advertised: List<AgentToolDefinition>, receipts: Map<String, JsonObject>, remainingToolCalls: Int? = null, fixturePreflight: JsonObject? = null): GitHubChecklistSchedule.Plan {
        val tools = authorized.ifEmpty { advertised }
        val planned = nextCall(task, advertised, receipts, remainingToolCalls, fixturePreflight)
        val authorizedNext = if (tools == advertised) planned else nextCall(task, tools, receipts, remainingToolCalls, fixturePreflight)
        val candidates = listOfNotNull(authorizedNext) +
            GitHubSelfTestReads.available(tools, trusted(receipts))
        return GitHubChecklistSchedule.select(
            tools,
            advertised,
            receipts,
            candidates,
            planned,
            GitHubAuthorizedCatalog.next(advertised, receipts, tools),
            GitHubChecklistBudget.acquisitionAllowed(receipts, remainingToolCalls)
        )
    }

    fun nextCall(task: String, tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>, remainingToolCalls: Int? = null, fixturePreflight: JsonObject? = null, independentReadsOnly: Boolean = false): ProviderEvent.ToolCall? {
        if (!integration(task) || tools.isEmpty()) return null
        val observations = trusted(receipts)
        val remaining = GitHubChecklistBudget.remaining(receipts, remainingToolCalls)
        if (remaining == 0) return null
        if (independentReadsOnly && remaining != null && remaining <= GitHubChecklistBudget.cleanupReserve(receipts)) return null
        // Filter while selecting, so a deferred mutation or subscription cannot
        // hide later inventory reads, dependency recipes or saved continuations.
        fun eligible(call: ProviderEvent.ToolCall) = tools.any { it.modelVisible && it.name == call.name && GitHubSelfTestReads.valid(it, call.arguments) } &&
            (!independentReadsOnly || dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isReplayableRead(call.name, call.arguments))
        // Preserve cleanup allowance, but qualify required live reads while their
        // owned resource still exists. A verified deletion retires those reads.
        val acquisitionAllowed = GitHubChecklistBudget.acquisitionAllowed(receipts, remainingToolCalls)
        val cleanup = GitHubSelfTestFixtures.next(task, tools, receipts, allowCreate = false, cleanupOnly = true, abortIncomplete = !acquisitionAllowed)
            ?.takeIf(::eligible)
        if (!acquisitionAllowed && cleanup != null) return cleanup
        // A deletion may already have happened. Reconcile its exact retained
        // intent before attempting reads whose resource may no longer exist.
        if (cleanup?.callId?.startsWith("fixture-preflight-") == true ||
            cleanup?.arguments?.get("action") == JsonPrimitive("recovery") &&
            cleanup.arguments["fixture_plan"] == JsonPrimitive(GitHubOwnedFixtures.PLAN)
        ) {
            return cleanup
        }
        if (acquisitionAllowed) GitHubTransportRecovery.nextRead(tools, observations)?.takeIf(::eligible)?.let { return it }
        val retiredRead = GitHubSelfTestFixtures.retiredReadPredicate(receipts)
        if (GitHubIntegrationAcceptance.requiresBuildBinding(task) && GitHubIntegrationAcceptance.binding(observations)["verified"] != JsonPrimitive(true)) {
            val runtime = GitHubRuntimeIdentity.current()
            for (tool in githubFamilyTools(tools, "github_context")) {
                val live = (tool.inputSchema["properties"] as? JsonObject)?.containsKey("verify_live") == true
                val args = buildJsonObject {
                    put("action", "status")
                    if (live) put("verify_live", true)
                }
                val candidate = call(tool.name, args)
                val attempted = observations.any { receipt ->
                    val prior = receipt["arguments"] as? JsonObject
                    receipt.text("tool") == tool.name &&
                        prior?.text("action") == "status" &&
                        (!live || prior["verify_live"] == JsonPrimitive(true)) &&
                        receipt["runtime_identity"] == runtime
                }
                if (!attempted && eligible(candidate)) return candidate
            }
        }
        // A cached observation is useful evidence but cannot qualify transport.
        // Retry that exact authorized read once with explicit cache bypass. The
        // retained argument prevents loops after process restart or another hit.
        // An exact live read qualifies an already acquired observation, so it
        // may spend the verification reserve, but never a fixture's cleanup reserve.
        val liveVerificationAllowed = remaining == null || remaining > GitHubChecklistBudget.cleanupReserve(receipts)
        val liveRead = observations.asReversed().takeIf { liveVerificationAllowed }?.firstNotNullOfOrNull { receipt ->
            if (receipt["success"] != JsonPrimitive(true) || GitHubEvidenceOrigin.of(receipt) == "LIVE" || retiredRead(receipt)) return@firstNotNullOfOrNull null
            val args = receipt["arguments"] as? JsonObject ?: return@firstNotNullOfOrNull null
            if (!requiresLiveTransport(Capability(receipt.text("tool").githubOperationFamily(), args.text("action"), ""))) return@firstNotNullOfOrNull null
            if (definitionTransportVerified(receipt, args, observations)) return@firstNotNullOfOrNull null
            if ("view_cursor" in args || args["verify_live"] == JsonPrimitive(true) || !dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isReplayableRead(receipt.text("tool"), args)) return@firstNotNullOfOrNull null
            val refreshed = JsonObject(args + ("verify_live" to JsonPrimitive(true)))
            if (observations.any { it.text("tool") == receipt.text("tool") && (it["arguments"] == refreshed || GitHubActionIdentity.matches(receipt.text("tool"), refreshed, it)) }) return@firstNotNullOfOrNull null
            val tool = tools.firstOrNull { it.name == receipt.text("tool") && it.modelVisible } ?: return@firstNotNullOfOrNull null
            if ((tool.inputSchema["properties"] as? JsonObject)?.containsKey("verify_live") != true || !GitHubSelfTestReads.valid(tool, refreshed)) return@firstNotNullOfOrNull null
            call(tool.name, refreshed)
        }
        if (liveRead != null) return liveRead
        if (cleanup != null) return cleanup
        if (acquisitionAllowed) continuation(task, tools, receipts, localOnly = true)?.takeIf(::eligible)?.let { return it }
        val watch = tools.firstOrNull { it.modelVisible && it.name.githubOperationFamily() == "github_watch_checks" }
        val selectedHead = observations.asReversed().firstNotNullOfOrNull { receipt ->
            if (!matches(receipt, capabilities.first()) || receipt["success"] != JsonPrimitive(true)) null else data(receipt)?.get("commit_sha")
        }
        if (acquisitionAllowed && watch != null && selectedHead != null) {
            for (run in GitHubObservedWorkflowRuns.current(observations, selectedHead as? JsonPrimitive)) {
                if (run.text("status") !in setOf("queued", "in_progress", "waiting", "pending", "requested")) continue
                val id = run["id"] ?: continue
                val args = buildJsonObject {
                    put("run_id", id)
                    put("commit_sha", selectedHead)
                    put("wait_timeout_seconds", 1)
                }
                if (GitHubSelfTestReads.valid(watch, args) && observations.none { it.text("tool") == watch.name && (it["arguments"] as? JsonObject)?.get("run_id") == id }) {
                    call(watch.name, args).takeIf(::eligible)?.let { return it }
                }
            }
        }
        for (capability in capabilities.filter { it.automatic }) {
            if (!acquisitionAllowed && capability.action != "checks") continue
            if (observations.any { matches(it, capability) }) continue
            val head = observations.asReversed().firstNotNullOfOrNull { receipt ->
                if (!matches(receipt, capabilities.first()) || receipt["success"] != JsonPrimitive(true)) return@firstNotNullOfOrNull null
                val root = receipt["result"] as? JsonObject
                ((root?.get("data") as? JsonObject) ?: root)?.get("commit_sha")
            }
            for (tool in githubFamilyTools(tools, capability.tool)) {
                val arguments = buildJsonObject {
                    put("action", capability.action)
                    if (requiresLiveTransport(capability) && (tool.inputSchema["properties"] as? JsonObject)?.containsKey("verify_live") == true) put("verify_live", true)
                    if (capability.action in setOf("branches", "workflows", "list", "commits", "runs")) put("per_page", if (capability.action == "workflows" || GitHubChecklistScope.exhaustive(task)) 100 else 30)
                    if ((capability.action in setOf("checks", "commits") || capability.action == "runs" && !GitHubChecklistScope.exhaustive(task)) && head != null) put("commit_sha", head)
                    if (capability.action == "list" && capability.tool in setOf("github_pr", "github_issue")) put("state", "all")
                }
                if (!GitHubSelfTestReads.valid(tool, arguments)) continue
                call(tool.name, arguments).takeIf(::eligible)?.let { return it }
            }
        }
        if (!acquisitionAllowed) return null
        val read = GitHubSelfTestReads.available(tools, observations).firstOrNull(::eligible)
        // Acquire resource IDs before optional source/metadata samples. Once
        // dependencies are observed, finish an admissible fixture while its
        // cleanup allowance still fits instead of spending it on more samples.
        if (read != null && GitHubSelfTestReads.isDependency(read)) return read
        if (read != null && GitHubChecklistScope.exhaustive(task) && remaining != null) {
            val cannotFinish = inventoryCoverage(receipts, remaining).any { (it as? JsonObject)?.get("fits_remaining_call_budget") == JsonPrimitive(false) }
            if (cannotFinish) return read
        }
        continuation(task, tools, receipts, localOnly = false, workflowDependencyOnly = true)?.takeIf(::eligible)?.let { return it }
        // A fixture is bounded and must leave capacity for its verification and
        // cleanup; optional inventory sampling runs only after this decision.
        val fixtureAllowed = !independentReadsOnly &&
            (remaining == null || remaining >= GitHubSelfTestFixtures.minimumCalls(tools) + GitHubChecklistBudget.VERIFICATION_RESERVE) &&
            fixturePreflight?.get("state") == JsonPrimitive("READY")
        GitHubSelfTestFixtures.next(task, tools, receipts, allowCreate = fixtureAllowed)?.takeIf(::eligible)?.let { return it }
        if (read != null) return read
        return continuation(task, tools, receipts, localOnly = false)?.takeIf(::eligible)
    }

    private fun call(tool: String, args: JsonObject) = ProviderEvent.ToolCall("checklist-" + GitHubEvidenceBook.digest("$tool:$args").take(24), tool, args)

    /** Immutable definition pages share one transport capability. Qualifying it
     * once does not require replaying every cached page of the same pinned tree.
     * Mutable inventories and fixture existence reads retain their exact checks.
     */
    private fun definitionTransportVerified(receipt: JsonObject, args: JsonObject, observations: List<JsonObject>): Boolean {
        if (receipt.text("tool").githubOperationFamily() != "github_ci" ||
            args.text("action") != "definitions" ||
            !args.text("commit_sha").matches(Regex("[a-fA-F0-9]{40}")) ||
            receipt.text("binding").isBlank() ||
            receipt.text("repository").isBlank()
        ) {
            return false
        }
        return observations.any { live ->
            live["tool"] == receipt["tool"] &&
                live["binding"] == receipt["binding"] &&
                live["repository"] == receipt["repository"] &&
                live[GitHubPolicyReadRecovery.AUTHORITY] == receipt[GitHubPolicyReadRecovery.AUTHORITY] &&
                live["success"] == JsonPrimitive(true) &&
                GitHubEvidenceOrigin.of(live) == "LIVE" &&
                (live["arguments"] as? JsonObject)?.let { it.text("action") == "definitions" && it["commit_sha"] == args["commit_sha"] } == true
        }
    }

    private fun catalog(tools: List<AgentToolDefinition>): List<Capability> {
        if (tools.isEmpty()) return capabilities
        return tools.filter { it.modelVisible && (it.name.githubOperationFamily().startsWith("github_") || it.name.isGatewayRepositoryTool()) }.flatMap { tool ->
            val name = tool.name.githubOperationFamily()
            val properties = tool.inputSchema["properties"] as? JsonObject
            val actions = ((properties?.get("action") as? JsonObject)?.get("enum") as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty().ifEmpty { listOf(((properties?.get("action") as? JsonObject)?.get("const") as? JsonPrimitive)?.content.orEmpty()) }
            actions.map { action -> capabilities.firstOrNull { it.tool == name && it.action == action } ?: Capability(name, action, "$name / ${action.ifBlank { "default" }}") }
        }.distinctBy { it.tool to it.action }
    }

    private fun requiresLiveTransport(capability: Capability): Boolean = requiresLiveTransport(capability.tool, capability.action)

    internal fun requiresLiveTransport(tool: String, action: String): Boolean = when (tool.githubOperationFamily()) {
        "github_context" -> action !in setOf("status", "resolve", "index", "efficiency", "capabilities", "recovery")
        "github_repository" -> action != "capabilities"
        "repository_workspace" -> true
        "github_search", "github_read", "github_stage_patch", "repository_graph" -> false
        else -> tool.startsWith("github_")
    }

    private fun readOnly(capability: Capability): Boolean {
        val arguments = buildJsonObject {
            if (capability.action.isNotBlank()) put("action", capability.action)
            // The capability can be exercised with a bounded, subscription-free observation.
            if (capability.tool == "github_watch_checks") put("wait_timeout_seconds", 1)
        }
        return dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isReplayableRead(capability.tool, arguments) ||
            dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isLocalGraphIndexing(capability.tool, arguments)
    }

    private data class CoverageCatalog(val active: List<Capability>, val historical: List<JsonObject>, val cumulative: List<Capability>)

    private fun coverageCatalog(receipts: Map<String, JsonObject>, tools: List<AgentToolDefinition>): CoverageCatalog {
        val historical = receipts.values.filter { it["trusted"] == JsonPrimitive(true) || it["success"] == JsonPrimitive(false) && it["dispatched"] == JsonPrimitive(false) }
        val active = catalog(tools)
        val union = (
            active + historical.filter { (it.text("tool").startsWith("github_") || it.text("tool").isGatewayRepositoryTool()) && (it["arguments"] as? JsonObject)?.containsKey("view_cursor") != true }.map {
                val name = it.text("tool").githubOperationFamily()
                val action = (it["arguments"] as? JsonObject)?.text("action").orEmpty()
                Capability(name, action, "$name / ${action.ifBlank { "default" }}")
            }
            ).distinctBy { it.tool to it.action }
        return CoverageCatalog(active, historical, union)
    }

    fun rows(receipts: Map<String, JsonObject>, tools: List<AgentToolDefinition> = emptyList(), task: String = "", fixturePreflight: JsonObject? = null): JsonArray =
        rows(receipts, tools, task, fixturePreflight, coverageCatalog(receipts, tools), observedOnly = false)

    private fun rows(receipts: Map<String, JsonObject>, tools: List<AgentToolDefinition>, task: String, fixturePreflight: JsonObject?, coverage: CoverageCatalog, observedOnly: Boolean): JsonArray {
        val historical = coverage.historical
        val active = coverage.active
        // Ordinary task checkpoints retain actual observations, including rejected
        // and ineligible receipts, without evaluating unrelated catalog blockers.
        val selected = if (observedOnly) coverage.cumulative.filter { capability -> historical.any { matches(it, capability) } } else coverage.cumulative
        if (selected.isEmpty()) return JsonArray(emptyList())
        val allTrusted = trusted(receipts)
        val knownPull = GitHubSelfTestReads.pullNumber(allTrusted)
        val graphObservation = GitHubSelfTestPrerequisites.graphObservation(allTrusted)
        val readyReads = GitHubSelfTestReads.available(tools, allTrusted).associateBy { it.name.githubOperationFamily() to it.arguments.text("action") }
        val pendingApprovals = GitHubPendingApproval.snapshot(receipts).filterIsInstance<JsonObject>()
        val integrationCoverage = integration(task)
        return JsonArray(
            selected.map { capability ->
                val observed = historical.filter { matches(it, capability) }
                val discovered = tools.any { it.name.githubOperationFamily() == capability.tool }
                fun permitted(receipt: JsonObject): Boolean {
                    val arguments = receipt["arguments"] as? JsonObject ?: return false
                    return tools.any { it.modelVisible && it.name == receipt.text("tool") && dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(arguments, it.inputSchema).isEmpty() }
                }
                val authorized = tools.isNotEmpty() && (active.any { it.tool == capability.tool && it.action == capability.action } || observed.any(::permitted))
                val eligible = observed.filter { it["verification_eligible"] != JsonPrimitive(false) && (tools.isEmpty() || permitted(it)) }
                val successes = eligible.mapNotNull { receipt ->
                    if (receipt["success"] != JsonPrimitive(true) || data(receipt)?.text("schema") == "github.repeat.v1") {
                        null
                    } else if (!readOnly(capability) && GitHubPendingApproval.taskId(receipt) != null) {
                        GitHubPendingApproval.publication(receipt, allTrusted)
                    } else if (!readOnly(capability) && (capability.tool != "github_stage_patch" || (receipt["arguments"] as? JsonObject)?.get("commit_immediately") == JsonPrimitive(true)) && GitHubOperationOutcome.snapshot(receipt)["proposal_prepared"] == JsonPrimitive(true)) {
                        null
                    } else {
                        receipt
                    }
                }.distinct()
                val waitingApproval = !readOnly(capability) &&
                    eligible.any { receipt ->
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
                val graphIncomplete = capability.tool == "repository_graph" && latest?.let { GitHubSelfTestGraph.incomplete(capability.action, it, allTrusted) } == true
                val pending = latest?.takeIf { it["success"] == JsonPrimitive(true) }?.let(::data)
                val observedPendingWatch = capability.tool == "github_watch_checks" && GitHubBoundedCheckWait.hasObservation(pending)
                val incomplete = graphIncomplete || (pending?.text("status") in setOf("PENDING_LOG_AVAILABILITY", "PENDING_CHECKS") && !observedPendingWatch)
                val excluded = capability.tool == "github_context" && capability.action == "repositories"
                val unsupportedFixture = !readOnly(capability) && !GitHubSelfTestFixtures.supports(capability.tool, capability.action)
                val ciActionWithoutRun = capability.tool == "github_ci" && capability.action in setOf("dispatch", "rerun", "cancel") && eligible.isEmpty() && observed.isEmpty()
                val repositoryPrerequisites = if (capability.tool == "github_repository") GitHubRepositoryReadiness.read(capability.action, tools, allTrusted) else null
                val graphPrerequisites = if (capability.tool == "repository_graph") GitHubSelfTestGraph.requiredInputs(capability.action, allTrusted) else emptyList()
                val repositoryStatusWithoutOperation = repositoryPrerequisites?.conditional == true && eligible.isEmpty() && observed.isEmpty()
                val notRequested = GitHubChecklistScope.readOnly(task) && !readOnly(capability) || GitHubChecklistScope.supportedFixturePlan(task) && unsupportedFixture || ciActionWithoutRun
                val readyCall = if (authorized && readOnly(capability) && (eligible.isEmpty() || graphIncomplete)) readyReads[capability.tool to capability.action] else null
                val conditionalDecision = capability.tool == "github_decide" && observed.isEmpty()
                val awaitingFixtureStart = !readOnly(capability) &&
                    GitHubSelfTestFixtures.supports(capability.tool, capability.action) &&
                    !waitingApproval &&
                    GitHubSelfTestFixtures.checkpoint(receipts).isEmpty() &&
                    successes.isEmpty() &&
                    failures.isEmpty()
                val fixtureApprovalUnknown = awaitingFixtureStart && fixturePreflight?.text("state") !in setOf("READY", "CONDITIONAL")
                val fixtureApprovalConditional = awaitingFixtureStart && fixturePreflight?.text("state") == "CONDITIONAL"
                val blocker = when {
                    fixtureApprovalUnknown -> "Fixture approval mode is unavailable. Recheck the effective approval policy for this task before creating a fixture."
                    fixtureApprovalConditional -> "The effective approval mode requires an explicit fixture approval decision. In GitHub workspace > Settings > Chat decisions, choose Fixture-only auto-approval for codex-self-test/*, or use the existing Approve & Commit interaction. This task retains its original approval preflight; changing settings does not approve an already staged patch."
                    awaitingFixtureStart -> "Fixture-only approval is available. The owned fixture still needs execution and verified cleanup within the remaining budget."
                    ciActionWithoutRun -> "No workflow run or job ID was supplied or deliberately created for this task. This CI mutation is conditional and was not requested."
                    repositoryStatusWithoutOperation -> "No repository creation, fork, import or clone was started in this task. This status read is conditional on a retained lifecycle receipt: operation_id for a gateway transfer, or expected_repository_id and the exact original destination for native creation. A selected revision or source path cannot supply that identity; no unrelated repository is created just to exercise status."
                    notRequested -> if (unsupportedFixture && GitHubChecklistScope.supportedFixturePlan(task)) "No supported owned-resource cleanup recipe is admitted for this action. It is outside this preset, remains untested, and requires a separate authorized fixture lifecycle." else "Write operations are excluded by the requested read-only scope."
                    excluded -> "Account-wide repository discovery is outside this workspace's selected repository scope."
                    eligible.isEmpty() && observed.isNotEmpty() -> "Current catalog excludes the historical receipts; reauthorize this operation before using them as proof."
                    waitingApproval -> "The exact staged patch is waiting for its existing Approve & Commit card. Generic continuation cannot grant approval; preserve the task and fixture."
                    transportMissing -> "Saved, local or mocked responses cannot qualify this remote operation; an authorized live read or verified mutation is still required."
                    graphIncomplete -> "The graph response is incomplete. " + graphPrerequisites.joinToString("; ")
                    incomplete -> "The endpoint responded but traversal or log availability remains incomplete; follow the saved continuation or wait for the recorded state change."
                    failures.isNotEmpty() && (successes.isEmpty() || latest?.get("success") == JsonPrimitive(false)) -> "Resolve recorded errors: " + failures.map { it.text("error_code") }.distinct().joinToString(", ") + ". " + GitHubVerificationRecovery.advice(failures)
                    readyCall != null -> "Ready under the current authorized action schema; all required inputs are available. No qualifying execution has been observed."
                    repositoryPrerequisites?.requiredInputs?.isNotEmpty() == true -> "Repository lifecycle prerequisites: " + repositoryPrerequisites.requiredInputs.joinToString("; ")
                    graphPrerequisites.isNotEmpty() -> "Graph prerequisite: $graphObservation. " + graphPrerequisites.joinToString("; ")
                    conditionalDecision -> "Conditional user-decision behavior has no genuine decision in this task. It remains untested; test it in the interaction harness."
                    capability.tool == "github_watch_checks" -> "Requires an observed pull request, workflow run, or exact commit in the selected repository. A bounded inline check can run without a background subscription; persistent subscription behavior requires a saved chat and a separate observation."
                    !readOnly(capability) -> GitHubSelfTestFixtures.prerequisite(capability.tool, capability.action)
                    capability.tool == "github_issue" -> "No issue identifier observed from the issue inventory; a pull request number cannot satisfy this dependency."
                    capability.tool == "github_pr" && capability.action == "copilot_task_status" -> "Requires a task ID from the selected Copilot task inventory or a saved creation receipt."
                    capability.tool == "github_pr" && knownPull != null -> "Pull request #${knownPull.content} is recorded. This action still needs an eligible invocation under its current tool schema."
                    capability.tool == "github_pr" -> "No pull request identifier observed from the pull request inventory."
                    capability.tool == "github_ci" -> "Requires an observed workflow/run and a completed job for logs or a failed completed run for failure analysis."
                    capability.tool == "repository_graph" -> "Graph prerequisite: $graphObservation. Preparation has not executed this capability and does not change graph authorization."
                    capability.tool == "github_release" -> "Requires a tag or release observed in the repository inventory."
                    else -> "Requires an authorized tool and its observed repository revision or source path."
                }
                val status = when {
                    notRequested -> "NOT_REQUESTED"
                    excluded -> "OUT_OF_SCOPE"
                    waitingApproval -> "WAITING_FOR_APPROVAL"
                    fixtureApprovalUnknown || fixtureApprovalConditional || repositoryStatusWithoutOperation -> "CONDITIONAL"
                    incomplete || tools.isNotEmpty() && !authorized -> "BLOCKED"
                    latest?.get("success") == JsonPrimitive(false) -> if (latest["dispatched"] == JsonPrimitive(false)) "PREFLIGHT_REJECTED" else "FAILED"
                    transportMissing -> unqualifiedStatus
                    successes.isNotEmpty() && failures.isNotEmpty() -> "RECOVERED"
                    successes.isNotEmpty() -> "PASSED"
                    failures.isNotEmpty() && failures.all { it["dispatched"] == JsonPrimitive(false) } -> "PREFLIGHT_REJECTED"
                    failures.isNotEmpty() -> "FAILED"
                    readyCall != null || conditionalDecision || capability.automatic && tools.any { it.name.githubOperationFamily() == capability.tool } -> "UNTESTED"
                    else -> "BLOCKED"
                }
                val blockerCode = when {
                    status in setOf("PASSED", "RECOVERED") -> null
                    notRequested -> GitHubCriterionBlocker.OUT_OF_SCOPE
                    excluded -> GitHubCriterionBlocker.REPOSITORY_SCOPE_EXCLUDED
                    tools.isNotEmpty() && !authorized -> GitHubCriterionBlocker.AUTHORIZATION_REQUIRED
                    eligible.isEmpty() && observed.isNotEmpty() -> GitHubCriterionBlocker.HISTORICAL_CONTRACT_CHANGED
                    waitingApproval -> GitHubCriterionBlocker.APPROVAL_REQUIRED
                    fixtureApprovalUnknown -> GitHubCriterionBlocker.FIXTURE_APPROVAL_STATE_UNKNOWN
                    fixtureApprovalConditional -> GitHubCriterionBlocker.FIXTURE_APPROVAL_MODE_REQUIRED
                    awaitingFixtureStart && authorized -> GitHubCriterionBlocker.EXECUTION_NOT_EXERCISED
                    repositoryStatusWithoutOperation -> GitHubCriterionBlocker.REPOSITORY_LIFECYCLE_REQUIRED
                    transportMissing -> GitHubCriterionBlocker.LIVE_EVIDENCE_REQUIRED
                    graphIncomplete -> GitHubSelfTestGraph.blocker(capability.action, allTrusted) ?: GitHubCriterionBlocker.CONTINUATION_REQUIRED
                    incomplete -> GitHubCriterionBlocker.CONTINUATION_REQUIRED
                    status == "PREFLIGHT_REJECTED" -> GitHubCriterionBlocker.PRECHECK_REJECTED
                    status == "FAILED" -> GitHubCriterionBlocker.OPERATION_FAILED
                    status == "UNTESTED" -> GitHubCriterionBlocker.EXECUTION_NOT_EXERCISED
                    repositoryPrerequisites?.requiredInputs?.isNotEmpty() == true -> if (capability.action == "status") GitHubCriterionBlocker.REPOSITORY_LIFECYCLE_REQUIRED else GitHubCriterionBlocker.OPERATION_IDENTITY_REQUIRED
                    capability.tool == "repository_graph" && readOnly(capability) -> GitHubSelfTestGraph.blocker(capability.action, allTrusted) ?: GitHubCriterionBlocker.EXECUTION_NOT_EXERCISED
                    capability.tool == "github_decide" -> GitHubCriterionBlocker.USER_DECISION_REQUIRED
                    capability.tool == "github_watch_checks" -> GitHubCriterionBlocker.CHECK_SUBSCRIPTION_REQUIRED
                    !readOnly(capability) -> GitHubCriterionBlocker.OWNED_FIXTURE_REQUIRED
                    capability.tool == "github_issue" -> GitHubCriterionBlocker.ISSUE_ID_REQUIRED
                    capability.tool == "github_pr" && capability.action == "copilot_task_status" -> GitHubCriterionBlocker.OPERATION_IDENTITY_REQUIRED
                    capability.tool == "github_pr" && knownPull != null -> GitHubCriterionBlocker.EXECUTION_NOT_EXERCISED
                    capability.tool == "github_pr" -> GitHubCriterionBlocker.PULL_REQUEST_ID_REQUIRED
                    capability.tool == "github_ci" -> GitHubCriterionBlocker.WORKFLOW_RUN_OR_JOB_REQUIRED
                    capability.tool == "github_release" -> GitHubCriterionBlocker.RELEASE_OR_TAG_REQUIRED
                    else -> GitHubCriterionBlocker.REPOSITORY_INPUT_REQUIRED
                }
                buildJsonObject {
                    put("id", "${capability.tool}:${capability.action}")
                    put("scope", capability.scope)
                    put("required", integrationCoverage && !excluded && !notRequested && !conditionalDecision && !repositoryStatusWithoutOperation)
                    put(
                        "applicability",
                        if (ciActionWithoutRun || repositoryStatusWithoutOperation) {
                            "CONDITIONAL_RESOURCE_NOT_PRESENT"
                        } else if (fixtureApprovalUnknown) {
                            "CONDITIONAL_APPROVAL_UNKNOWN"
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
                    repositoryPrerequisites?.requiredInputs?.takeIf { it.isNotEmpty() }?.let { put("required_inputs", JsonArray(it.map(::JsonPrimitive))) }
                    if (graphPrerequisites.isNotEmpty() && status !in setOf("PASSED", "RECOVERED") && readyCall == null) put("required_inputs", JsonArray(graphPrerequisites.map(::JsonPrimitive)))
                    if (capability.tool == "repository_graph" && blockerCode?.name in GitHubSelfTestPrerequisites.graphInputBlockerCodes) {
                        put("repository_graph_observation", graphObservation)
                    }
                    put("evidence_origins", JsonArray(successes.ifEmpty { eligible }.map { GitHubEvidenceOrigin.of(it) }.distinct().map(::JsonPrimitive)))
                    put("historical_evidence_origins", JsonArray(eligible.map { GitHubEvidenceOrigin.of(it) }.distinct().map(::JsonPrimitive)))
                    if (eligible.any { GitHubPendingApproval.publication(it, allTrusted) != null }) {
                        put("publication_evidence", "REMOTE_STATE_VERIFIED")
                        put("publication_dispatch_attribution", "UNAVAILABLE_UNLESS_SEPARATELY_RECORDED")
                    }
                    put("live_verified", successes.any { GitHubEvidenceOrigin.of(it) == "LIVE" })
                    put(
                        "acceptance_basis",
                        when {
                            status !in setOf("PASSED", "RECOVERED") -> "UNVERIFIED"
                            successes.any { GitHubEvidenceOrigin.of(it) == "LIVE" } -> "LIVE_RESPONSE"
                            successes.any { GitHubEvidenceOrigin.of(it) in setOf("CACHED", "LOCAL") } -> "RETAINED_RESPONSE"
                            successes.any { GitHubEvidenceOrigin.of(it) == "MOCKED" } -> "MOCK_RESPONSE"
                            else -> "RESPONSE_ONLY"
                        }
                    )
                    put("response_validated", eligible.any { it["success"] == JsonPrimitive(true) })
                    put("waiting_for_approval", waitingApproval)
                    put("requires_live_transport", liveRequired)
                    if (capability.tool == "github_watch_checks") {
                        val inline = successes.any { (it["arguments"] as? JsonObject)?.get("wait_timeout_seconds") != null && GitHubBoundedCheckWait.hasObservation(data(it)) }
                        put("bounded_inline_observation_verified", inline)
                        put("background_subscription_verified", successes.any { (it["arguments"] as? JsonObject)?.get("wait_timeout_seconds") == null && data(it)?.get("subscription_id") is JsonPrimitive })
                    }
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
                    if (capability.tool == "repository_graph") {
                        val qualified = successes.lastOrNull()?.let(::data)
                        if (qualified?.get("qualification_scope") == JsonPrimitive(RepositorySourcePriority.BOUNDED_SAMPLE)) {
                            put("qualification_scope", RepositorySourcePriority.BOUNDED_SAMPLE)
                            qualified["qualification_paths"]?.let { put("qualification_paths", it) }
                            put("qualification_description", "Operation exercised on these named source files; full repository indexing remains separate.")
                        }
                    }
                    put("inventory_status", if (graphIncomplete || successes.any { data(it)?.get("complete") == JsonPrimitive(false) || (data(it)?.get("graph") as? JsonObject)?.get("complete") == JsonPrimitive(false) }) "PARTIAL" else "See independent inventory coverage")
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
    fun capabilitiesComplete(task: String, receipts: Map<String, JsonObject>, tools: List<AgentToolDefinition>, fixturePreflight: JsonObject? = null, evaluatedCapabilities: JsonArray? = null): Boolean {
        if (tools.isEmpty()) return false
        if (GitHubAuthorizedCatalog.required(tools, receipts) && GitHubAuthorizedCatalog.snapshot(receipts, tools)["complete"] != JsonPrimitive(true)) return false
        val coverage = (evaluatedCapabilities ?: rows(receipts, tools, task, fixturePreflight)).filterIsInstance<JsonObject>().filter { it["required"] == JsonPrimitive(true) }
        if (coverage.none { it.text("status") in setOf("PASSED", "RECOVERED") } || coverage.any { it.text("status") !in setOf("PASSED", "RECOVERED") }) return false
        // Enforce fixture proof for the same required actions used by the acceptance ledger.
        val requiredIds = coverage.mapTo(hashSetOf()) { it.text("id") }
        val mutations = catalog(tools).filter { "${it.tool}:${it.action}" in requiredIds && !readOnly(it) }
        if (mutations.any { !GitHubSelfTestFixtures.supports(it.tool, it.action) }) return false
        if (mutations.isNotEmpty() && !GitHubSelfTestFixtures.complete(receipts)) return false
        if (GitHubChecklistScope.exhaustive(task) && (pendingPages(receipts).isNotEmpty() || inventoryCoverage(receipts).any { (it as JsonObject).text("coverage") != "retrieval complete at observation" })) return false
        if (workflowExecutions(receipts).any { (it as JsonObject)["execution_verified"] != JsonPrimitive(true) }) return false
        return true
    }

    /** Compact public progress uses evaluated outcomes, never receipt or call counts. */
    fun progressSummary(checkpoint: JsonObject, next: ProviderEvent.ToolCall?): String {
        val catalog = (checkpoint["capabilities"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val rows = catalog.filter { it["required"] != JsonPrimitive(false) && it.text("status") !in setOf("OUT_OF_SCOPE", "NOT_REQUESTED") }
        val excluded = catalog.size - rows.size
        val completed = rows.count { it.text("acceptance_state").ifBlank { GitHubAcceptanceLedger.state(it.text("status")).name } == "PASS" }
        val waiting = rows.count { it.text("status") == "WAITING_FOR_APPROVAL" }
        val conditional = rows.count { it.text("status") == "CONDITIONAL" }
        val failed = rows.count { it.text("status") in setOf("FAILED", "PREFLIGHT_REJECTED") }
        val blocked = rows.count { it.text("status") == "BLOCKED" }
        val nextAction = if (next == null) {
            val criteria = (checkpoint["requirements"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter { it["required"] != JsonPrimitive(false) }
            when {
                criteria.isNotEmpty() && criteria.all { it.text("acceptance_state") == "PASS" } -> "All requested criteria are verified."
                rows.isNotEmpty() && completed == rows.size -> "Review the remaining task criteria."
                else -> "Review required inputs and approvals in the results."
            }
        } else {
            val tool = next.name.githubOperationFamily()
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
        return "Checks: $completed/${rows.size} verified" + (if (waiting > 0) "; $waiting waiting for approval" else "") + (if (conditional > 0) "; $conditional need fixture approval" else "") + (if (failed > 0) "; $failed failed" else "") + (if (blocked > 0) "; $blocked blocked" else "") + (if (excluded > 0) "; $excluded outside required scope" else "") + ". $nextAction"
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

    fun checkpoint(task: String, receipts: Map<String, JsonObject>, tools: List<AgentToolDefinition> = emptyList(), remainingToolCalls: Int? = null, fixturePreflight: JsonObject? = null, stageBudget: JsonObject? = null, acceptedContract: JsonObject? = null, executionPlan: JsonObject? = null): JsonObject = buildJsonObject {
        put("schema", "github.test-checklist.v3")
        put("request_digest", GitHubEvidenceBook.digest(task))
        GitHubChecklistSchedule.current(executionPlan, receipts)?.let { put("execution_plan", it) }
        val integrationCoverage = integration(task)
        val catalog = coverageCatalog(receipts, tools)
        val capabilities = rows(receipts, tools, task, fixturePreflight, catalog, observedOnly = !integrationCoverage)
        put("capabilities", capabilities)
        put("coverage_scope", if (integrationCoverage) "FULL_CATALOG" else "OBSERVED_CAPABILITIES")
        put("catalog_capability_count", catalog.cumulative.size)
        put("retained_capability_count", capabilities.size)
        put("omitted_unobserved_capability_count", catalog.cumulative.size - capabilities.size)
        put("catalog_count_scope", "Known catalog and historical observations; not a final authorized denominator or task acceptance count.")
        val requirements = if (integrationCoverage) {
            GitHubIntegrationAcceptance.requirements(task, receipts, tools, fixturePreflight = fixturePreflight, evaluatedCapabilities = capabilities)
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
        val budget = stageBudget ?: GitHubChecklistBudget.checkpoint(receipts, remainingToolCalls, GitHubSelfTestFixtures.minimumCalls(tools), acceptedContract)
        put("stage_budget", budget)
        if (integrationCoverage) put("preparation", GitHubIntegrationPreparation.snapshot(task, receipts, tools, capabilities, remainingToolCalls, fixturePreflight, budget, acceptedContract))
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
        put("pagination_policy", "Reuse complete persisted originals and their exact remote recipes; consume display fragments only when no complete original was retained; up to 32 definition pages per revision and eight exact-head executed-job continuation pages independently of two remote inventory samples, or eight while locating an issue dependency, unless complete inventory is explicitly requested; restore retained pages locally before one bounded read-only source recovery")
        val acceptance = if (integrationCoverage) {
            listOf("Each requested capability has a result or explicit untested/blocker reason", "Every claim cites matching branch/PR/SHA receipts", "Complete durable sources or required local fragments, and remote pages, consumed or explicitly pending", "Application test results bound to newly executed runs at tested SHA", "Coverage, measured score or limitation, failures and solutions retained", "Authorized isolated write fixtures verified and cleaned up")
        } else {
            listOf("Only the user's requested outputs are required acceptance criteria", "Every claim cites matching branch/PR/SHA receipts", "Read-only reviews require exact comparison revisions and relevant source reads, not unrelated mutation fixtures", "Unverified outputs and pending remote pages remain explicit")
        }
        put("acceptance", JsonArray(acceptance.map(::JsonPrimitive)))
        put("pending_pages", JsonArray(pendingPages(receipts)))
        val pagination = paginationResume(task, receipts, tools, (budget["remaining_calls"] as? JsonPrimitive)?.intOrNull)
        val deadline = budget["stop_reason"] == JsonPrimitive("ACCEPTED_TASK_DEADLINE_REACHED")
        put(
            "pagination_resume",
            if (deadline) {
                JsonObject(
                    pagination + buildJsonObject {
                        put("next_action", "Authorize a new time window before the saved read can run.")
                        put("recovery_action", "AUTHORIZE_NEW_TIME_WINDOW")
                        put("resume_allowed", false)
                    }
                )
            } else {
                pagination
            }
        )
        put("fixtures", GitHubSelfTestFixtures.checkpoint(receipts))
        put("inventory_coverage", inventoryCoverage(receipts, (budget["remaining_calls"] as? JsonPrimitive)?.intOrNull))
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
        if (receipts.values.any { "profile_workspace_check" in it || "direct_coding_observation" in it }) {
            val measured = GitHubIntegrationAcceptance.evidenceClasses(receipts)
            val paired = (measured["paired_coding_repairs"] as? JsonArray).orEmpty()
            val direct = (measured["direct_coding_observations"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            append("**Coding session evidence**\n\n")
            append("Paired Gateway checks: ${measured["profile_workspace_check_count"]}. Verified Kotlin test repairs: ${paired.size}.\n")
            if (direct.isNotEmpty()) {
                val native = direct.count { it.text("executor") == "github_api" }
                val hosted = direct.count { it.text("executor") == "github_copilot" }
                append("Recent Android-controlled observations: $native native GitHub operations and $hosted Copilot task operations.\n")
            }
            append('\n')
        }
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
            append("\n**${item.text("tool").githubOperationFamily()} / ${item.text("operation")} inventory:** ${inventorySummary(item)}." + scope.takeIf(String::isNotBlank)?.let { " Filters: $it." }.orEmpty() + "\n")
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
        val acquisitionAllowed = GitHubChecklistBudget.acquisitionAllowed(receipts, remaining)
        val next = if (acquisitionAllowed) continuation(task, tools, receipts, localOnly = true) ?: continuation(task, tools, receipts, localOnly = false) else null
        val rejected = pending.count { page -> observations.lastOrNull { it["tool"] == page["tool"] && it["arguments"] == page["arguments"] }?.get("success") == JsonPrimitive(false) }
        return buildJsonObject {
            put("scope_version", if (GitHubChecklistScope.PRESET_SCOPE in task) 2 else 1)
            put("catalog_scope", if (integration(task)) "FULL_CATALOG" else "OBSERVED_CAPABILITIES")
            put("mode", if (exhaustive) "COMPLETE_INVENTORY_REQUESTED" else "BOUNDED_SMOKE_SAMPLE")
            put("pending_count", pending.size)
            put("failed_page_count", rejected)
            put("remote_samples_spent", remoteSamples(observations))
            put("complete", pending.isEmpty())
            put("acquisition_allowed", acquisitionAllowed)
            put("cleanup_reserve", GitHubChecklistBudget.cleanupReserve(receipts))
            GitHubSelfTestWorkflowJobs.dependencyObservation(tools, observations)?.let { observed ->
                val current = GitHubSelfTestPrerequisites.selected(observations)
                val runPage = next?.name?.githubOperationFamily() == "github_ci" && next.arguments.text("action") == "runs" && current != null && next.arguments["commit_sha"] == current.head
                put("workflow_dependency_observation", if (observed == "MISSING_EXECUTED_JOB" && runPage) "RUN_PAGE_AVAILABLE" else observed)
            }
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
                    !acquisitionAllowed -> "Acquisition budget is reserved for verification and owned-fixture cleanup. Reconcile the fixture before further optional reads; a larger allowance requires explicit authorization."
                    next != null -> "Continue the exact next_call read under the existing authorization and budget. No writes, decisions, workflow dispatch or merges are implied."
                    rejected > 0 -> "Resolve the failed page's exact permission, validation or availability blocker before resuming. Unchanged failures are not retried."
                    !exhaustive -> "Ordinary integration tests sample remote inventory within bounded limits. Request testing all tools and completing pending pagination to traverse retained read recipes; other permissions and budgets still apply."
                    else -> "No currently authorized read recipe is executable. Restore the exact tool/schema/connection, then resume the saved checkpoint; do not invent a cursor or infer completeness."
                }
            )
        }
    }

    fun inventorySummary(row: JsonObject): String = if (row["fetched_count_scope"] == JsonPrimitive("QUALIFIED_DISPATCH_PAGE_METADATA")) {
        "${row.text("fetched")} of ${row.text("total")} fetched according to retained upstream page metadata; unique item identities and consumption are not fully retained; ${row.text("coverage")}"
    } else {
        "${row.text("retrieved")} of ${row.text("total")} retrieved; ${row.text("coverage")}"
    }

    fun inventoryCoverage(receipts: Map<String, JsonObject>, remainingCalls: Int? = null): JsonArray {
        val pending = pendingPages(receipts)
        val trusted = trusted(receipts)
        fun filters(receipt: JsonObject, useObservedRevision: Boolean = true): JsonObject {
            val args = receipt["arguments"] as? JsonObject
            val observation = receipt["inventory_observation"] as? JsonObject
            val result = data(receipt)
            val pageSize = args?.get("per_page") ?: observation?.get("per_page") ?: result?.get("per_page")
            val observedCommit = if (useObservedRevision && receipt["success"] == JsonPrimitive(true)) observation?.get("commit_sha") ?: result?.get("commit_sha") else null
            val query = GitHubInventoryQuery.arguments(receipt.text("tool"), JsonObject(args.orEmpty() + if (pageSize == null) emptyMap() else mapOf("per_page" to pageSize)), observedCommit)
            return JsonObject(query.filterKeys { it !in setOf("action", "owner", "repo") })
        }
        data class InventoryKey(val first: String, val second: String?, val third: JsonObject, val repository: String, val binding: String)
        val groups = trusted.filter { receipt ->
            receipt["success"] == JsonPrimitive(true) &&
                data(receipt)?.text("schema") != "github.repeat.v1" &&
                (receipt["arguments"] as? JsonObject)?.text("action") in setOf("workflows", "branches", "list", "commits", "runs", "tags")
        }.groupBy {
            val args = it["arguments"] as? JsonObject
            InventoryKey(it.text("tool"), args?.text("action"), filters(it), it.text("repository").lowercase(java.util.Locale.ROOT), it.text("binding"))
        }
        return JsonArray(
            groups.mapNotNull { (key, observations) ->
                fun page(receipt: JsonObject) =
                    ((receipt["arguments"] as? JsonObject)?.get("page") as? JsonPrimitive)?.longOrNull
                        ?: (data(receipt)?.get("page") as? JsonPrimitive)?.longOrNull ?: 1L
                fun reused(receipt: JsonObject) = receipt["shared_result"] == JsonPrimitive(true) ||
                    GitHubEvidenceOrigin.of(receipt) == "CACHED" ||
                    (receipt["execution"] as? JsonObject)?.get("observation_origin") == JsonPrimitive("RETAINED_OBSERVATION")
                // A deliberate page-one refresh starts another traversal. Earlier pages
                // remain in the evidence book, but cannot complete the fresh inventory.
                val start = observations.indexOfLast { page(it) == 1L && !reused(it) }
                val traversal = observations.drop(start.coerceAtLeast(0))
                val anchorTime = observations.getOrNull(start)?.get("observed_at_ms")?.let { (it as? JsonPrimitive)?.longOrNull }?.takeIf { it > 0 }
                val byPage = traversal.groupBy(::page).mapValues { (_, versions) -> versions.lastOrNull { !reused(it) } ?: versions.last() }
                val staleCachedPages = if (start < 0) {
                    0
                } else {
                    byPage.values.count { receipt ->
                        val observedAt = (receipt["observed_at_ms"] as? JsonPrimitive)?.longOrNull
                        reused(receipt) && (anchorTime == null || observedAt == null || observedAt < anchorTime)
                    }
                }
                val pages = byPage.toSortedMap().values.mapNotNull(GitHubInventoryObservation::planningRoot)
                val inventories = byPage.toSortedMap().values.map(GitHubInventoryObservation::of)
                val counted = inventories.all { it != null }
                val canonical = counted && inventories.none { it?.get("scope") == JsonPrimitive("QUALIFIED_DISPATCH_PAGE_METADATA") }
                val arrays = pages.mapNotNull { page -> (page["workflows"] ?: page["items"] ?: page["pull_requests"] ?: page["workflow_runs"] ?: page["tags"] ?: page["recent_commits"] ?: page["commits"] ?: page["issues"] ?: page["releases"] ?: page["branches"]) as? JsonArray }
                val records = arrays.flatMap { it.mapNotNull { item -> item as? JsonObject } }
                val identities = if (counted) inventories.flatMap { (it?.get("record_keys") as? JsonArray).orEmpty() }.distinct() else records.mapNotNull { item -> item["id"] ?: item["number"] ?: item["name"] ?: item["sha"] }.distinct()
                val fetched = if (counted) inventories.sumOf { (it?.get("fetched") as? JsonPrimitive)?.longOrNull ?: 0 } else records.size.toLong()
                val displayed = inventories.mapNotNull { (it?.get("displayed") as? JsonPrimitive)?.longOrNull }.takeIf { it.size == inventories.size }?.sum()
                val coveragePages = if (counted) inventories.filterNotNull() else pages
                val totals = coveragePages.mapNotNull { (it["total_count"] as? JsonPrimitive)?.longOrNull }.distinct()
                val total = totals.singleOrNull()
                val viewIds = observations.mapNotNull { receipt ->
                    val view = (receipt["view"] as? JsonObject) ?: (receipt["result"] as? JsonObject)
                    (((view?.get("recovery_call") as? JsonObject)?.get("arguments") as? JsonObject)?.text("view_cursor"))?.substringBefore(':')?.takeIf { it.isNotBlank() }
                }.toSet()
                fun belongs(recipe: JsonObject): Boolean {
                    if (recipe.text("tool") != key.first) return false
                    if ("repository" in recipe &&
                        recipe.text("repository").lowercase(java.util.Locale.ROOT) != key.repository ||
                        "binding" in recipe &&
                        recipe.text("binding") != key.binding
                    ) {
                        return false
                    }
                    val args = recipe["arguments"] as? JsonObject ?: return false
                    if ("view_cursor" in args) return args.text("view_cursor").substringBefore(':') in viewIds
                    val selected = filters(recipe)
                    // Failed reads may not reveal the default page size; keep their
                    // blocker for every otherwise matching inventory until recovered.
                    val expected = if ("per_page" in selected) key.third else JsonObject(key.third.filterKeys { it != "per_page" })
                    if (args.text("action") != key.second) return false
                    if (selected == expected) return true
                    // A failed refresh using the original symbolic ref has no observed SHA.
                    return key.second == "commits" && "commit_sha" !in selected && traversal.any { filters(it, useObservedRevision = false) == selected }
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
                    val reopened = recovery != null && later.any { it["success"] == JsonPrimitive(true) && sameCall(it, recovery, failed) }
                    !reopened && later.none { it["success"] == JsonPrimitive(true) && sameCall(it, failed, failed) }
                }
                val complete = contiguous &&
                    staleCachedPages == 0 &&
                    coveragePages.lastOrNull()?.get("has_more") == JsonPrimitive(false) &&
                    coveragePages.none { it["complete"] == JsonPrimitive(false) } &&
                    coveragePages.none { it["pagination_inconsistent"] == JsonPrimitive(true) || it["upstream_changed_during_traversal"] == JsonPrimitive(true) } &&
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
                    put("prior_traversal_observations", start.coerceAtLeast(0))
                    put("cached_page_freshness_unverified", staleCachedPages)
                    put("pending_continuations", pending.count(::belongs))
                    put("filters", key.third)
                    put("retrieved", identities.size)
                    put("fetched", if (counted) JsonPrimitive(fetched) else kotlinx.serialization.json.JsonNull)
                    put("fetched_lower_bound", fetched)
                    put(
                        "fetched_count_scope",
                        if (canonical) {
                            "CANONICAL_RETURNED_PAGES"
                        } else if (counted) {
                            "QUALIFIED_DISPATCH_PAGE_METADATA"
                        } else {
                            "RETAINED_PROJECTION_ONLY"
                        }
                    )
                    put("displayed", displayed?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                    put("consumed", kotlinx.serialization.json.JsonNull)
                    put("consumption_status", "NOT_RECORDED")
                    put("retained_review_records", records.size)
                    val perPage = (key.third["per_page"] as? JsonPrimitive)?.longOrNull
                    if (total != null && total > 0 && perPage != null && perPage > 0) {
                        val minimum = (total - 1) / perPage + 1
                        val outstanding = (minimum - byPage.keys.count { it in 1..minimum }).coerceAtLeast(0)
                        put("minimum_pages", minimum)
                        put("minimum_remaining_page_calls", outstanding)
                        remainingCalls?.let { put("fits_remaining_call_budget", outstanding <= it) }
                    }
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
                it.text("tool").githubOperationFamily() == "github_ci" &&
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
                if (read["success"] != JsonPrimitive(true) || read.text("tool").githubOperationFamily() != "github_ci" || (read["arguments"] as? JsonObject)?.text("action") !in setOf("run", "status")) return@firstNotNullOfOrNull null
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
            val jobReceipts = trusted(receipts).filter { read ->
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
                    read.text("tool").githubOperationFamily() == "github_ci" &&
                    (read["arguments"] as? JsonObject)?.text("action") == "jobs"
            }
            val jobs = GitHubWorkflowJobEvidence.completeJobs(jobReceipts.mapNotNull(::data))
            val jobsVerified = jobs != null && jobs.all { it.text("status") == "completed" && it.text("conclusion") in setOf("success", "skipped", "neutral") }
            buildJsonObject {
                val testJobs = jobs.orEmpty().filter { Regex("(?i)\\b(?:tests?|unit|instrumentation)\\b").containsMatchIn(it.text("name")) }
                if (jobsVerified && testJobs.isNotEmpty() && testJobs.all { it.text("conclusion") == "success" }) {
                    put("test_job_receipts", JsonArray(jobReceipts.mapNotNull { it["receipt_id"] }))
                }
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
