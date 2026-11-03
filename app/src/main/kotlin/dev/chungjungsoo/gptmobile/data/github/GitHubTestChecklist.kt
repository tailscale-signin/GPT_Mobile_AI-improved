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

    fun integration(task: String): Boolean = GitHubTaskCoverage.matches(task) && (Regex("(?i)\\b(?:tools?|integration|functionality)\\b").containsMatchIn(task) || task.trim().matches(Regex("(?i)(?:please\\s+)?test\\s+all[.!]?")))
    private fun trusted(receipts: Map<String, JsonObject>) = receipts.values.filter { it["trusted"] == JsonPrimitive(true) }
    private fun matches(receipt: JsonObject, capability: Capability): Boolean = receipt.text("tool").substringBefore("__") == capability.tool &&
        (receipt["arguments"] as? JsonObject)?.containsKey("view_cursor") != true &&
        (capability.action.isBlank() || (receipt["arguments"] as? JsonObject)?.text("action") == capability.action)

    /** Follow only exact issued read recipes. Local fragments and remote pages are independent. */
    fun pendingPages(receipts: Map<String, JsonObject>): List<JsonObject> {
        val observations = trusted(receipts)
        val completed = observations.map { it.text("tool") to it["arguments"] }.toSet()
        return observations.filter { it["success"] == JsonPrimitive(true) }.flatMap { receipt ->
            val view = (receipt["view"] as? JsonObject) ?: (receipt["result"] as? JsonObject) ?: return@flatMap emptyList()
            buildList {
                (view["continuation_call"] as? JsonObject)?.let(::add)
                if (view["local_page_complete"] != JsonPrimitive(false)) {
                    (view["remote_continuations"] as? JsonArray).orEmpty().forEach { entry -> ((entry as? JsonObject)?.get("call") as? JsonObject)?.let(::add) }
                }
            }
        }.distinct().filter { it.text("tool") to it["arguments"] !in completed }
    }

    fun nextCall(task: String, tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>): ProviderEvent.ToolCall? {
        if (!integration(task) || tools.isEmpty()) return null
        val observations = trusted(receipts)
        val watch = tools.firstOrNull { it.name.substringBefore("__") == "github_watch_checks" }
        val selectedHead = observations.asReversed().firstNotNullOfOrNull { receipt ->
            if (!matches(receipt, capabilities.first()) || receipt["success"] != JsonPrimitive(true)) null else data(receipt)?.get("commit_sha")
        }
        if (watch != null && selectedHead != null) {
            for (receipt in observations.asReversed().filter { it["success"] == JsonPrimitive(true) && it.text("tool").substringBefore("__") == "github_ci" }) {
                val root = data(receipt) ?: continue
                val runs = (root["workflow_runs"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: listOf(root)
                for (run in runs) {
                    if (run["head_sha"] != selectedHead || run.text("status") !in setOf("queued", "in_progress", "waiting", "pending", "requested")) continue
                    val id = run["id"] ?: continue
                    val args = buildJsonObject {
                        put("run_id", id)
                        put("commit_sha", selectedHead)
                    }
                    if (observations.none { it.text("tool") == watch.name && (it["arguments"] as? JsonObject)?.get("run_id") == id }) return call(watch.name, args)
                }
            }
        }
        for (capability in capabilities.filter { it.automatic }) {
            if (observations.any { matches(it, capability) }) continue
            val tool = tools.firstOrNull { it.name.substringBefore("__") == capability.tool } ?: continue
            val head = observations.asReversed().firstNotNullOfOrNull { receipt ->
                if (!matches(receipt, capabilities.first()) || receipt["success"] != JsonPrimitive(true)) return@firstNotNullOfOrNull null
                val root = receipt["result"] as? JsonObject
                ((root?.get("data") as? JsonObject) ?: root)?.get("commit_sha")
            }
            return call(
                tool.name,
                buildJsonObject {
                    put("action", capability.action)
                    if (capability.action in setOf("branches", "workflows", "list")) put("per_page", 5)
                    if (capability.action in setOf("commits", "runs")) put("per_page", 5)
                    if (capability.action in setOf("checks", "commits") && head != null) put("commit_sha", head)
                    if (capability.action == "list") put("state", "all")
                }
            )
        }
        representativeRead(tools, observations)?.let { return it }
        val exhaustInventories = Regex("(?i)\\b(?:all|entire|complete|full)\\s+(?:branches|workflows|pull requests|inventor(?:y|ies)|history)\\b").containsMatchIn(task)
        val sampledPages = observations.count { (it["arguments"] as? JsonObject)?.containsKey("view_cursor") == true || ((it["arguments"] as? JsonObject)?.get("page") as? JsonPrimitive)?.longOrNull?.let { n -> n > 1 } == true }
        // Capability sampling comes first. Pagination is a bounded separate test unless an inventory is explicitly requested.
        val page = pendingPages(receipts).firstOrNull { recipe ->
            val args = recipe["arguments"] as? JsonObject ?: return@firstOrNull false
            tools.any { it.name == recipe.text("tool") && dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(args, it.inputSchema).isEmpty() } &&
                dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isReplayableRead(recipe.text("tool"), args) &&
                (exhaustInventories || sampledPages < 2) &&
                (args.containsKey("view_cursor") || args.text("action") in setOf("branches", "workflows", "list") || Regex("(?i)\\b(?:all|entire|complete|full)\\s+(?:workflow |ci |run )?history\\b").containsMatchIn(task))
        }
        if (page != null) return call(page.text("tool"), page["arguments"] as JsonObject)
        return null
    }

    /** Read prerequisites are extracted from successful observations, never invented. */
    private fun representativeRead(tools: List<AgentToolDefinition>, observations: List<JsonObject>): ProviderEvent.ToolCall? {
        val roots = observations.filter { it["success"] == JsonPrimitive(true) }.mapNotNull(::data)
        fun firstObject(key: String) = roots.asReversed().firstNotNullOfOrNull { (it[key] as? JsonArray)?.firstOrNull() as? JsonObject }
        val run = firstObject("workflow_runs")
        val job = firstObject("jobs")
        val pr = firstObject("pull_requests") ?: firstObject("items")?.takeIf { it["number"] != null }
        val recipes = buildList<Pair<String, JsonObject>> {
            add(
                "github_search" to buildJsonObject {
                    put("action", "search")
                    put("query", "README")
                    put("mode", "paths")
                }
            )
            val paths = roots.asReversed().firstNotNullOfOrNull { root ->
                listOf("matches", "results", "files", "items").firstNotNullOfOrNull { key -> (root[key] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.text("path")?.takeIf(String::isNotBlank) } }
            }
            if (paths != null) {
                add(
                    "github_read" to buildJsonObject {
                        put("action", "ranges")
                        put(
                            "ranges",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("path", paths)
                                        put("start_line", 1)
                                        put("end_line", 40)
                                    }
                                )
                            )
                        )
                    }
                )
            }
            run?.get("id")?.let { id ->
                listOf("run", "jobs", "artifacts").forEach { action ->
                    add(
                        "github_ci" to buildJsonObject {
                            put("action", action)
                            put("run_id", id)
                            if (action != "run") put("per_page", 5)
                        }
                    )
                }
            }
            job?.get("id")?.let { id ->
                listOf("job", "logs").forEach { action ->
                    add(
                        "github_ci" to buildJsonObject {
                            put("action", action)
                            put("job_id", id)
                            if (action == "logs") {
                                put("start_line", 1)
                                put("end_line", 40)
                            }
                        }
                    )
                }
            }
            pr?.get("number")?.let { id ->
                listOf("headers", "context", "comments", "reviews").forEach { action ->
                    add(
                        "github_pr" to buildJsonObject {
                            put("action", action)
                            put("pull_number", id)
                        }
                    )
                }
            }
        }
        for ((name, args) in recipes) {
            val tool = tools.firstOrNull { it.name.substringBefore("__") == name } ?: continue
            if (observations.any { it.text("tool") == tool.name && (it["arguments"] as? JsonObject)?.text("action") == args.text("action") }) continue
            if (dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(args, tool.inputSchema).isEmpty()) return call(tool.name, args)
        }
        return null
    }

    private fun call(tool: String, args: JsonObject) = ProviderEvent.ToolCall("checklist-" + GitHubEvidenceBook.digest("$tool:$args").take(24), tool, args)

    private fun catalog(tools: List<AgentToolDefinition>): List<Capability> {
        if (tools.isEmpty()) return capabilities
        return tools.filter { it.name.substringBefore("__").startsWith("github_") }.flatMap { tool ->
            val name = tool.name.substringBefore("__")
            val properties = tool.inputSchema["properties"] as? JsonObject
            val actions = ((properties?.get("action") as? JsonObject)?.get("enum") as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty().ifEmpty { listOf("") }
            actions.map { action -> capabilities.firstOrNull { it.tool == name && it.action == action } ?: Capability(name, action, "$name / ${action.ifBlank { "default" }}") }
        }.distinctBy { it.tool to it.action }
    }

    private fun readOnly(capability: Capability): Boolean = dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isReplayableRead(capability.tool, buildJsonObject { if (capability.action.isNotBlank()) put("action", capability.action) })

    fun rows(receipts: Map<String, JsonObject>, tools: List<AgentToolDefinition> = emptyList()): JsonArray = JsonArray(
        catalog(tools).map { capability ->
            val observed = trusted(receipts).filter { matches(it, capability) }
            val successes = observed.filter { it["success"] == JsonPrimitive(true) }
            buildJsonObject {
                put("id", "${capability.tool}:${capability.action}")
                put("scope", capability.scope)
                put(
                    "status",
                    when {
                        successes.isNotEmpty() -> "PASSED"
                        observed.isNotEmpty() -> "FAILED"
                        else -> "UNTESTED"
                    }
                )
                put("evidence_scope", "API operation only; application behavior and full inventory require separate evidence")
                put("receipt_ids", JsonArray(observed.mapNotNull { it["receipt_id"] }))
                put(
                    "next_action",
                    if (observed.isEmpty() && !capability.automatic && !readOnly(capability)) {
                        "Prepare an isolated fixture and use ordinary authorization; verify the exact revision and clean up. Never merge or cancel unrelated work."
                    } else if (observed.isEmpty()) {
                        "Execute this read using an observed source path, PR, run or job identifier where required; no write fixture is needed"
                    } else if (successes.isEmpty()) {
                        "Resolve the recorded failure before retrying"
                    } else {
                        "Retain the successful receipt; inspect outstanding pages independently"
                    }
                )
            }
        }
    )

    /** API completion never implies device, benchmark or application-wide acceptance. */
    fun apiComplete(task: String, receipts: Map<String, JsonObject>, tools: List<AgentToolDefinition>): Boolean {
        if (!integration(task) || GitHubRequestCompletion.requirements(task).size != 1 || tools.isEmpty() || Regex("(?i)device|android|app(?:lication)? health|stress|benchmark|reboot|process death|install apk|\\b(?:fix|implement|repair|improve|performance|latency|features?)\\b").containsMatchIn(task)) return false
        // Mutation smoke tests also need owned-fixture cleanup proof, which API success alone does not carry.
        if (catalog(tools).any { !readOnly(it) }) return false
        val coverage = rows(receipts, tools)
        if (coverage.isEmpty() || coverage.any { (it as JsonObject)["status"] != JsonPrimitive("PASSED") }) return false
        val exhaustive = Regex("(?i)(?:all|complete|full|entire)\\s+(?:branches|pull requests|commits|workflows|inventory|history)").containsMatchIn(task)
        if (exhaustive && pendingPages(receipts).isNotEmpty()) return false
        if (workflowExecutions(receipts).any { (it as JsonObject)["execution_verified"] != JsonPrimitive(true) }) return false
        return true
    }

    fun checkpoint(task: String, receipts: Map<String, JsonObject>, tools: List<AgentToolDefinition> = emptyList()): JsonObject = buildJsonObject {
        put("schema", "github.test-checklist.v1")
        put("request_digest", GitHubEvidenceBook.digest(task))
        put("capabilities", rows(receipts, tools))
        put("coverage_source", if (tools.isEmpty()) "BASELINE_CATALOG" else "AUTHORIZED_TOOL_ACTION_SCHEMAS")
        put("pagination_policy", "Representative operations first; at most two continuation samples unless a complete inventory is explicitly requested")
        put("acceptance", JsonArray(listOf("Each requested capability has a result or explicit untested/blocker reason", "Every claim cites matching branch/PR/SHA receipts", "Required local and remote pages consumed or pending", "Application test results bound to newly executed runs at tested SHA", "Coverage, measured score or limitation, failures and solutions retained", "Authorized isolated write fixtures verified and cleaned up").map(::JsonPrimitive)))
        put("pending_pages", JsonArray(pendingPages(receipts)))
        put("inventory_coverage", inventoryCoverage(receipts))
        put("evidence_count", trusted(receipts).size)
        put("new_workflow_executions", workflowExecutions(receipts))
        put("application_tests", "UNTESTED unless a newly executed test run/job result is bound to the exact requested SHA; generic workflow success is not application coverage")
        put("device_tests", "UNTESTED without a device execution receipt")
        put("overall_health", "NOT_MEASURED")
    }

    fun render(receipts: Map<String, JsonObject>, tools: List<AgentToolDefinition> = emptyList()): String = buildString {
        val rows = rows(receipts, tools)
        val passed = rows.count { (it as JsonObject)["status"] == JsonPrimitive("PASSED") }
        val observed = rows.count { (it as JsonObject)["status"] != JsonPrimitive("UNTESTED") }
        append("\n**Capability coverage:** $observed of ${rows.size} API capabilities observed. **Measured API score:** ")
        append(if (observed == 0) "Not measured." else "$passed/$observed successful capabilities (one point per capability with a successful receipt; failures remain in the operation table).")
        append(" **Overall integration health: Not measured.** API success is not application or device-test coverage.\n\n")
        append("| Capability | Result |\n| --- | --- |\n")
        rows.forEach {
            val row = it as JsonObject
            append("| ${row.text("scope")} | ${if (row.text("status") == "PASSED") "API succeeded" else row.text("status")} |\n")
        }
        inventoryCoverage(receipts).forEach { raw ->
            val item = raw as JsonObject
            append("\n**${item.text("operation")} inventory:** ${item.text("retrieved")} of ${item.text("total")} retrieved; ${item.text("coverage")}.\n")
        }
        val pages = pendingPages(receipts)
        if (pages.isNotEmpty()) append("\n**Pending pagination:** ${pages.size} issued continuation(s) remain unread; inventory completeness is not established. Local fragments must finish before remote pages.\n")
        append("\n**Solutions / next actions:** finish pending reads using the saved continuation arguments; resolve recorded failures; exercise missing writes on an authorized isolated branch or test repository; execute application tests at the exact SHA and retain run/job links. Watch pending checks to a bounded deadline, then keep a pending checkpoint. Cleanup requires verifying fixture ownership. Merge and cancellation are never implied for existing work.\n")
    }

    fun inventoryCoverage(receipts: Map<String, JsonObject>): JsonArray {
        val pending = pendingPages(receipts)
        val groups = trusted(receipts).filter { receipt ->
            receipt["success"] == JsonPrimitive(true) && (receipt["arguments"] as? JsonObject)?.text("action") in setOf("workflows", "branches", "list")
        }.groupBy {
            val args = it["arguments"] as? JsonObject
            Triple(it.text("tool"), args?.text("action"), JsonObject(args.orEmpty().filterKeys { key -> key in setOf("state", "ref", "branch", "commit_sha", "workflow_id", "head", "base") }))
        }
        return JsonArray(
            groups.mapNotNull { (key, observations) ->
                val pages = observations.mapNotNull(::data)
                val arrays = pages.mapNotNull { page -> (page["workflows"] ?: page["items"] ?: page["pull_requests"]) as? JsonArray }
                if (arrays.isEmpty()) return@mapNotNull null
                val records = arrays.flatMap { it.mapNotNull { item -> item as? JsonObject } }
                val identities = records.mapNotNull { item -> item["id"] ?: item["number"] ?: item["name"] }.distinct()
                if (records.size > 0 && identities.isEmpty()) return@mapNotNull null
                val total = pages.asReversed().firstNotNullOfOrNull { (it["total_count"] as? JsonPrimitive)?.longOrNull }
                val viewIds = observations.mapNotNull { receipt ->
                    val view = (receipt["view"] as? JsonObject) ?: (receipt["result"] as? JsonObject)
                    (((view?.get("recovery_call") as? JsonObject)?.get("arguments") as? JsonObject)?.text("view_cursor"))?.substringBefore(':')?.takeIf { it.isNotBlank() }
                }.toSet()
                fun belongs(recipe: JsonObject): Boolean {
                    if (recipe.text("tool") != key.first) return false
                    val args = recipe["arguments"] as? JsonObject ?: return false
                    if ("view_cursor" in args) return args.text("view_cursor").substringBefore(':') in viewIds
                    return args.text("action") == key.second && JsonObject(args.filterKeys { it in setOf("state", "ref", "branch", "commit_sha", "workflow_id", "head", "base") }) == key.third
                }
                val complete = pages.lastOrNull()?.get("has_more") == JsonPrimitive(false) &&
                    pending.none(::belongs) &&
                    trusted(receipts).none { it["success"] != JsonPrimitive(true) && belongs(it) }
                buildJsonObject {
                    put("operation", key.second.orEmpty())
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
