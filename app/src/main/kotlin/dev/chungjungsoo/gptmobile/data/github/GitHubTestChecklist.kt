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

    fun integration(task: String): Boolean = GitHubTaskCoverage.matches(task) && Regex("(?i)\\b(?:tools?|integration|functionality)\\b").containsMatchIn(task)
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
        // Inventory completeness is requested; a sample of commit/run history is not an entire-history audit.
        val page = pendingPages(receipts).firstOrNull { recipe ->
            val args = recipe["arguments"] as? JsonObject ?: return@firstOrNull false
            tools.any { it.name == recipe.text("tool") && dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(args, it.inputSchema).isEmpty() } &&
                dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isReplayableRead(recipe.text("tool"), args) &&
                (args.containsKey("view_cursor") || args.text("action") in setOf("branches", "workflows", "list") || Regex("(?i)\\b(?:all|entire|complete|full)\\s+(?:workflow |ci |run )?history\\b").containsMatchIn(task))
        }
        if (page != null) return call(page.text("tool"), page["arguments"] as JsonObject)
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
                    if (capability.action in setOf("branches", "workflows", "list")) put("per_page", 100)
                    if (capability.action in setOf("commits", "runs")) put("per_page", 5)
                    if (capability.action in setOf("checks", "commits") && head != null) put("commit_sha", head)
                    if (capability.action == "list") put("state", "all")
                }
            )
        }
        return null
    }

    private fun call(tool: String, args: JsonObject) = ProviderEvent.ToolCall("checklist-" + GitHubEvidenceBook.digest("$tool:$args").take(24), tool, args)

    fun rows(receipts: Map<String, JsonObject>): JsonArray = JsonArray(
        capabilities.map { capability ->
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
                    if (observed.isEmpty() && !capability.automatic) {
                        "Prepare an isolated fixture and use ordinary authorization; verify the exact revision and clean up. Never merge or cancel unrelated work."
                    } else if (observed.isEmpty()) {
                        "Execute the missing read"
                    } else if (successes.isEmpty()) {
                        "Resolve the recorded failure before retrying"
                    } else {
                        "Retain the successful receipt; inspect outstanding pages independently"
                    }
                )
            }
        }
    )

    fun checkpoint(task: String, receipts: Map<String, JsonObject>): JsonObject = buildJsonObject {
        put("schema", "github.test-checklist.v1")
        put("request_digest", GitHubEvidenceBook.digest(task))
        put("capabilities", rows(receipts))
        put("acceptance", JsonArray(listOf("Each requested capability has a result or explicit untested/blocker reason", "Every claim cites matching branch/PR/SHA receipts", "Required local and remote pages consumed or pending", "Application test results bound to newly executed runs at tested SHA", "Coverage, measured score or limitation, failures and solutions retained", "Authorized isolated write fixtures verified and cleaned up").map(::JsonPrimitive)))
        put("pending_pages", JsonArray(pendingPages(receipts)))
        put("inventory_coverage", inventoryCoverage(receipts))
        put("evidence_count", trusted(receipts).size)
        put("new_workflow_executions", workflowExecutions(receipts))
        put("application_tests", "UNTESTED unless a newly executed test run/job result is bound to the exact requested SHA; generic workflow success is not application coverage")
        put("device_tests", "UNTESTED without a device execution receipt")
        put("overall_health", "NOT_MEASURED")
    }

    fun render(receipts: Map<String, JsonObject>): String = buildString {
        val rows = rows(receipts)
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
                val complete = pages.lastOrNull()?.get("has_more") == JsonPrimitive(false) &&
                    pending.none { it.text("tool") == key.first } &&
                    trusted(receipts).none { it.text("tool") == key.first && it["success"] != JsonPrimitive(true) && (it["arguments"] as? JsonObject)?.containsKey("view_cursor") == true }
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
                if (args?.text("action") != "dispatch" || mutationTime == null || readTime == null || readTime <= mutationTime) return@firstNotNullOfOrNull null
                if (read["success"] != JsonPrimitive(true) || read.text("tool").substringBefore("__") != "github_ci" || (read["arguments"] as? JsonObject)?.text("action") !in setOf("run", "status")) return@firstNotNullOfOrNull null
                data(read)?.takeIf { id != null && sha != null && it["id"] == id && it["head_sha"] == sha }
            }
            buildJsonObject {
                mutation["receipt_id"]?.let { put("dispatch_receipt", it) }
                id?.let { put("run_id", it) }
                sha?.let { put("commit_sha", it) }
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
