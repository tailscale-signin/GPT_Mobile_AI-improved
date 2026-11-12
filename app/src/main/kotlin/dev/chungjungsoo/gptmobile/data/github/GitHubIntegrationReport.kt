package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Measured acceptance and call observations remain separate from inventory traversal and device tests. */
internal object GitHubIntegrationReport {
    fun stopReason(task: String, receipts: Map<String, JsonObject>, tools: List<AgentToolDefinition>, remainingToolCalls: Int? = null): JsonObject {
        val remaining = GitHubChecklistBudget.remaining(receipts, remainingToolCalls)
        val capabilities = GitHubTestChecklist.rows(receipts, tools, task).filterIsInstance<JsonObject>()
        val catalog = GitHubAuthorizedCatalog.snapshot(receipts, tools)
        val fixtures = GitHubSelfTestFixtures.checkpoint(receipts).filterIsInstance<JsonObject>()
        val openFixtures = fixtures.filter { it["final_state_verified"] != JsonPrimitive(true) }
        val cleanupStoppedBeforeDispatch = openFixtures.isNotEmpty() && openFixtures.all { it["cleanup_dispatched"] == JsonPrimitive(false) }
        val pending = GitHubTestChecklist.pendingPages(receipts)
        val exhaustiveInventory = GitHubChecklistScope.exhaustive(task)
        val reason = when {
            GitHubTestChecklist.apiComplete(task, receipts, tools) -> "ACCEPTANCE_COMPLETE" to "Every requested criterion has qualifying evidence."
            GitHubPendingApproval.snapshot(receipts).isNotEmpty() -> "WAITING_FOR_APPROVAL" to "The existing native Approve & Commit card must be resolved. Its exact patch, task identity and fixture are retained; another Continue does not approve the commit."
            remaining == 0 -> "TOOL_BUDGET_EXHAUSTED" to "The recorded authorized tool-call budget has no remaining calls. Resume requires a new authorized budget."
            remaining != null && remaining <= GitHubChecklistBudget.VERIFICATION_RESERVE -> "VERIFICATION_RESERVE_REACHED" to "The remaining $remaining calls are reserved for verification and cleanup; acquisition stopped before the hard ceiling."
            cleanupStoppedBeforeDispatch -> "FIXTURE_CLEANUP_UNVERIFIED" to "GitHub cleanup stopped during preflight. The fixture still needs a guarded cleanup and a fresh absence check."
            openFixtures.isNotEmpty() -> "FIXTURE_CLEANUP_UNVERIFIED" to "An owned fixture has unverified final state or cleanup. Reconcile its retained mutation receipt before further writes."
            GitHubAuthorizedCatalog.required(tools, receipts) && catalog["complete"] != JsonPrimitive(true) -> "AUTHORIZED_CATALOG_INCOMPLETE" to "Authorized discovery is ${catalog.text("status").lowercase()}; ${catalog.text("blocker").ifBlank { "the retained version-bound continuation must complete before the denominator is final" }}."
            capabilities.any { it.text("status") in setOf("FAILED", "PREFLIGHT_REJECTED") } -> "RECORDED_OPERATION_BLOCKERS" to "Unresolved operation errors remain. The exact error codes, resources and receipt IDs identify the recovery prerequisites."
            pending.isNotEmpty() && exhaustiveInventory -> "INVENTORY_CONTINUATION_BLOCKED" to "Requested inventory traversal has ${pending.size} unfinished exact recipes; no executable authorized continuation was selected."
            capabilities.any { it["required"] != JsonPrimitive(false) && it.text("status") !in setOf("PASSED", "RECOVERED", "OUT_OF_SCOPE", "NOT_REQUESTED") } -> "CAPABILITY_PREREQUISITES_MISSING" to "Remaining required capabilities need observed resource identifiers, supported fixture lifecycles or current authorization. No further automatic operation is currently admissible."
            else -> "ACCEPTANCE_EVIDENCE_MISSING" to "API observations are retained, but one or more independent acceptance criteria lack qualifying execution or identity evidence."
        }
        return buildJsonObject {
            put("code", reason.first)
            put("detail", reason.second)
            if (reason.first == "AUTHORIZED_CATALOG_INCOMPLETE") {
                put("blocker", catalog["blocker"] ?: JsonPrimitive("CATALOG_INCOMPLETE"))
                put("affected_contracts", catalog["requested_contract_changes"] ?: JsonArray(emptyList()))
            }
            put(
                "recovery_action",
                when (reason.first) {
                    "AUTHORIZED_CATALOG_INCOMPLETE" -> if (catalog.text("blocker") == "CATALOG_CONTRACT_CHANGED") {
                        "RECONCILE_CATALOG_CONTRACTS"
                    } else if (catalog["continuation_call"] is JsonObject) {
                        "RESUME_ISSUED_CATALOG_CONTINUATION"
                    } else {
                        "RECONCILE_CATALOG_SNAPSHOT"
                    }
                    "WAITING_FOR_APPROVAL" -> "RESOLVE_BOUND_APPROVAL"
                    "FIXTURE_CLEANUP_UNVERIFIED" -> if (cleanupStoppedBeforeDispatch) "RECHECK_OWNED_FIXTURE_PREFLIGHT" else "RECONCILE_OWNED_FIXTURE"
                    "TOOL_BUDGET_EXHAUSTED", "VERIFICATION_RESERVE_REACHED" -> "REVIEW_REMAINING_SCOPE_AND_BUDGET"
                    "CAPABILITY_PREREQUISITES_MISSING" -> "RESOLVE_CAPABILITY_PREREQUISITE"
                    "ACCEPTANCE_COMPLETE" -> "NONE"
                    else -> "RESUME_UNVERIFIED_REQUIREMENTS"
                }
            )
            remaining?.let { put("remaining_tool_calls", it) }
            put("budget_exhausted", remaining == 0)
            put("pending_continuations", pending.size)
            put("receipt_count", receipts.size)
        }
    }

    fun render(task: String, receipts: Map<String, JsonObject>, tools: List<AgentToolDefinition>, remainingToolCalls: Int? = null, stageBudget: JsonObject? = null, acceptedContract: JsonObject? = null): String = buildString {
        val checkpoint = GitHubTestChecklist.checkpoint(task, receipts, tools, remainingToolCalls, stageBudget = stageBudget, acceptedContract = acceptedContract)
        val capabilities = checkpoint.array("capabilities")
        val criteria = checkpoint.array("requirements")
        val catalog = checkpoint["authorized_catalog"] as JsonObject
        val requiredCriteria = criteria.filter { it["required"] != JsonPrimitive(false) }
        val requiredCapabilities = capabilities.filter { it["required"] == JsonPrimitive(true) }
        val passedCapabilities = requiredCapabilities.filter { it.text("acceptance_state") == "PASS" }
        val accepted = requiredCriteria.count { it.text("acceptance_state") == "PASS" }
        val complete = requiredCriteria.isNotEmpty() && accepted == requiredCriteria.size
        val activeNames = tools.filter { it.modelVisible && (it.name.substringBefore("__").startsWith("github_") || it.name.substringBefore("__") == "repository_graph") }.map { it.name.substringBefore("__") }.toSet()
        val observations = receipts.values.filter { it["trusted"] == JsonPrimitive(true) && it["verification_eligible"] != JsonPrimitive(false) }
        val exercised = observations.filter { GitHubEvidenceOrigin.of(it) == "LIVE" }.map { it.text("tool").substringBefore("__") }.toSet().intersect(activeNames).size
        val outcomes = capabilities.filter { it.text("id").substringBefore(':') in activeNames }.groupBy { it.text("id").substringBefore(':') }.mapValues { (_, rows) ->
            when {
                rows.all { it.text("status") in setOf("OUT_OF_SCOPE", "NOT_REQUESTED") } -> "OUT_OF_SCOPE"
                rows.any { it.text("status") == "FAILED" } -> "FAILED"
                rows.all { it.text("status") in setOf("PASSED", "RECOVERED", "OUT_OF_SCOPE", "NOT_REQUESTED") } -> "PASSED"
                rows.any { it.text("status") in setOf("PASSED", "RECOVERED") } -> "PARTIAL"
                else -> "BLOCKED"
            }
        }
        append("**Task outcome: ${if (complete) "acceptance verified" else "partial acceptance"}.** $accepted/${requiredCriteria.size} required task criteria verified. ")
        append("${passedCapabilities.size}/${requiredCapabilities.size} required API capabilities verified; ${capabilities.size - requiredCapabilities.size} capability rows are excluded from that denominator. Task criteria and API capabilities are independent counts.\n\n")
        append("Of the passed required capabilities, ${passedCapabilities.count { it["live_verified"] == JsonPrimitive(true) }} have live response evidence and ${passedCapabilities.count { it["live_verified"] != JsonPrimitive(true) }} qualify only the recorded response scope. Cached or local acceptance does not establish live transport or application/device execution.\n\n")
        append("$exercised/${activeNames.size} known authorized GitHub tools have live observations. ")
        append(if (catalog["denominator_final"] == JsonPrimitive(true)) "The authorized catalog is complete.\n\n" else "The all-tools denominator remains provisional until authorized catalog discovery is exhausted.\n\n")
        append("Discovery: ${catalog.text("discovery_calls_succeeded")}/${catalog.text("discovery_calls_observed")} retained calls succeeded; ${catalog.text("inventory_pages_verified")} version-bound pages verified. ")
        append("${catalog.text("observed_tool_count")} authorized tool names observed, including ${catalog.text("observed_github_tool_count")} GitHub tools. Inventory discovery does not establish tool execution or operation verification.\n\n")
        append("### Tool outcomes\n\n| Outcome | Tools | Meaning |\n| --- | ---: | --- |\n")
        linkedMapOf(
            "PASSED" to "Every requested action of this tool has a qualifying response; execution scope is recorded per capability.",
            "FAILED" to "A dispatched operation has an unresolved failure.",
            "PARTIAL" to "Some requested actions passed; other actions remain unverified.",
            "BLOCKED" to "No qualifying coverage yet; prerequisites, authorization or preflight validation prevent acceptance.",
            "OUT_OF_SCOPE" to "Every action is excluded by the selected repository or explicit read-only scope."
        ).forEach { (state, meaning) -> append("| $state | ${outcomes.values.count { it == state }} | $meaning |\n") }
        val dispatched = observations.count { it["dispatched"] == JsonPrimitive(true) }
        val requests = observations.mapNotNull { it["execution"] as? JsonObject }
        val reads = requests.mapNotNull { it.number("read_requests") }.takeIf { it.isNotEmpty() }?.sum()?.toString() ?: "unknown"
        val writes = requests.mapNotNull { it.number("mutation_requests") }.takeIf { it.isNotEmpty() }?.sum()?.toString() ?: "unknown"
        append("\nRecorded tool receipts: ${observations.size}; dispatched tool calls: $dispatched; recorded HTTP reads: $reads; mutation requests: $writes. Missing transport counters are unknown, not measured zero. Repeated pages increase calls, not the number of tools exercised.\n")
        val kinds = observations.groupingBy(GitHubExecutionLedger::observationKind).eachCount()
        append("\n| Recorded outcome | Count |\n| --- | ---: |\n")
        GitHubExecutionLedger.ObservationKind.entries.forEach { kind -> append("| ${kind.name} | ${kinds.getOrDefault(kind, 0)} |\n") }
        append("\nThese categories separate remote responses, completed mutations, saved/cache results, suppressed duplicates and pending approval. A successful local tool response is not proof that a remote write occurred.\n")
        append("\n### Independent acceptance criteria\n\n| Criterion | Result | Evidence / remaining prerequisite |\n| --- | --- | --- |\n")
        criteria.forEach { row -> append("| ${safe(row.text("requirement"))} | ${row.text("acceptance_state")} | ${safe(row.text("acceptance"))} ${ids(row["evidence_receipt_ids"] as? JsonArray)} |\n") }
        append("\n### Issues and exact observations\n\n")
        val operations = GitHubTaskCoverage.rows(receipts)
        if (operations.isNotEmpty()) {
            append("| Observed operation | Exact resource | Successful / failed observations | Receipts |\n| --- | --- | --- | --- |\n")
            operations.sortedByDescending { it.number("failed_observations") ?: 0 }.take(12).forEach { row ->
                val resource = listOf("job_id", "run_id", "workflow_id", "pull_number", "issue_number", "artifact_id", "branch", "commit_sha").mapNotNull { key -> row.text(key).takeIf { it.isNotBlank() && it != "null" }?.let { "$key=$it" } }.joinToString(", ")
                append("| ${safe(row.text("tool"))} / ${safe(row.text("action"))} | ${safe(resource.ifBlank { "not recorded" })} | ${row.text("successful_observations")} / ${row.text("failed_observations")} | ${ids(row["receipt_ids"] as? JsonArray)} |\n")
            }
            append("\nFile reads establish source inspection. Existing CI observations do not establish a newly executed app or device test.\n\n")
        }
        val problems = capabilities.filter { it.text("status") !in setOf("PASSED", "RECOVERED", "OUT_OF_SCOPE", "NOT_REQUESTED") }
        if (problems.isEmpty()) {
            append("No unresolved capability errors are recorded. Independent acceptance and inventory coverage still apply.\n")
        } else {
            append("| Capability | Outcome | Receipt / prerequisite |\n| --- | --- | --- |\n")
            problems.take(12).forEach { row -> append("| ${safe(row.text("id"))} | ${row.text("status")} | ${ids(row["receipt_ids"] as? JsonArray)} ${safe(row.text("next_action"))} |\n") }
            if (problems.size > 12) append("\n${problems.size - 12} additional capability rows remain in the retained checklist.\n")
        }
        val failed = observations.filter { it["success"] != JsonPrimitive(true) }
        failed.takeLast(8).forEach { receipt ->
            val args = receipt["arguments"] as? JsonObject
            val resource = listOf("job_id", "run_id", "workflow_id", "pull_number", "issue_number", "branch", "path").mapNotNull { key -> args?.text(key)?.takeIf(String::isNotBlank)?.let { "$key=$it" } }.joinToString(", ")
            append("\n- ${safe(receipt.text("receipt_id"))}: ${safe(receipt.text("tool"))} ${safe(args?.text("action").orEmpty())}; ${safe(resource.ifBlank { "resource not recorded" })}; ${safe(receipt.text("error_code"))}.\n")
        }
        append("\n### Fixture cleanup\n\n")
        val fixtures = checkpoint.array("fixtures")
        if (fixtures.isEmpty()) append("No owned disposable fixture lifecycle is verified. Write capabilities keep their explicit prerequisites.\n")
        fixtures.forEach { fixture -> append("- ${safe(fixture.text("branch"))}: ownership ${fixture.text("ownership_verified")}; cleanup ${safe(fixture.text("cleanup_status"))}; creation ${safe(fixture.text("creation_receipt"))}; cleanup receipt ${safe(fixture.text("cleanup_receipt").ifBlank { "missing" })}.\n") }
        append("\nCleanup removes the disposable resource only when verified; retained API receipts and remote audit history remain evidence of the test.\n")
        append("\n### Build, source and evidence classes\n\n")
        val binding = checkpoint["runtime_binding"] as JsonObject
        val runtime = binding["runtime_identity"] as JsonObject
        append("| Identity | Observed value |\n| --- | --- |\n")
        append("| Running app | ${safe(runtime.text("application_id"))} ${safe(runtime.text("version_name"))} (${safe(runtime.text("version_code"))}), ${safe(runtime.text("build_type"))} |\n")
        append("| App source commit | ${safe(runtime.text("app_source_sha"))} |\n")
        append("| Target repository revision | ${safe(binding.text("repository_revision").ifBlank { "unverified for this running build" })} |\n")
        append("| Current device execution | NOT_TESTED; process/build metadata is not a device execution test |\n")
        val device = runtime["device_environment"] as? JsonObject
        device?.let { append("| Runtime device metadata | ${safe(it.text("manufacturer"))} ${safe(it.text("model"))}, device=${safe(it.text("device"))}, SDK=${it.text("sdk_int")} |\n") }
        append("\n${safe(binding.text("reason"))}\n\n| Evidence class | Receipts | Scope |\n| --- | ---: | --- |\n")
        val classes = checkpoint["evidence_classes"] as JsonObject
        for (name in listOf("LIVE_ACCOUNT", "CACHED_OR_LOCAL", "MOCKED", "HISTORICAL_CI_OBSERVATION", "APP_GATEWAY", "CURRENT_DEVICE_EXECUTION")) {
            val value = classes[name] as JsonObject
            append("| $name | ${value.text("count")} | ${if (name == "HISTORICAL_CI_OBSERVATION") "Observed remote CI; does not establish a newly executed app/device test" else value.text("status").ifBlank { "${ids(value["receipt_ids"] as? JsonArray)}" }} |\n")
        }
        append("\n### Repository inventory and stop reason\n\n")
        checkpoint.array("inventory_coverage").forEach { row -> append("- ${safe(row.text("tool"))}/${safe(row.text("operation"))}: ${safe(GitHubTestChecklist.inventorySummary(row))}.\n") }
        val budget = checkpoint["stage_budget"] as JsonObject
        val stop = stopReason(task, receipts, tools, budget.number("remaining_calls"))
        append("\n**${stop.text("code")}**: ${safe(stop.text("detail"))}\n")
        if ((stop.number("pending_continuations") ?: 0) > 0) {
            append("\nRetained continuation recipes: ${stop.text("pending_continuations")}. Further reads retain the task's scope, authorization and remaining budget.\n")
        }
        append("\nEffective tool-call limit: ${budget.text("effective_call_limit").ifBlank { "not recorded" }} (${budget.text("limit_source")}). Calls used: ${budget.text("used_calls").ifBlank { "not recorded" }}. Budget remaining: ${budget.text("remaining_calls").ifBlank { "not recorded" }}; verification reserve: ${budget.text("verification_reserve")}. ${budget.text("limit_rule")} Elapsed run time is not inferred from tool counts. The checkpoint preserves unmet criteria, exact continuation recipes, fixture state and receipt IDs for resumption.\n")
        append("\n**Overall integration health: Not measured.** API response acceptance, traversal completeness, new CI execution and current-device tests are independent.\n")
    }

    private fun ids(value: JsonArray?) = value.orEmpty().take(3).joinToString(", ") { safe((it as? JsonPrimitive)?.contentOrNull.orEmpty()) }.ifBlank { "No qualifying receipt." }
    private fun JsonObject.array(key: String) = (get(key) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
    private fun safe(value: String) = value.replace(Regex("[\\p{Cntrl}<>`*\\[\\]]"), " ").replace("|", "\\|")
}
