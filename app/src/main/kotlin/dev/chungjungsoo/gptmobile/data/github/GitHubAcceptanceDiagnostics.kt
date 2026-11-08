package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Exportable acceptance evidence: fixed labels and digests, never task text or tool payloads. */
internal object GitHubAcceptanceDiagnostics {
    private const val SCHEMA = "github.acceptance-diagnostics.v1"
    private const val LIMIT = 64
    private val classes = setOf("LIVE_ACCOUNT", "CACHED", "LOCAL", "MOCKED", "HISTORICAL_CI_OBSERVATION", "CURRENT_DEVICE_EXECUTION", "APP_GATEWAY", "NOT_TESTED")
    private val tools = setOf("github_context", "github_read", "github_search", "github_stage_patch", "github_write", "github_commit", "github_pr", "github_issue", "github_release", "github_ci", "github_watch_checks", "repository_graph")
    private val actions = setOf("default", "status", "resolve", "branch", "branches", "branch_dependencies", "commits", "commit", "commit_search", "checks", "changes", "list", "get", "search", "search_code", "search_repository", "ranges", "files", "history", "headers", "context", "comments", "reviews", "create", "update", "close", "comment", "reply", "review", "merge", "write", "delete_branch", "delete_fixture_branch", "commit_staged_patch", "definitions", "workflows", "runs", "run", "jobs", "job", "logs", "artifacts", "failure", "dispatch", "rerun", "rerun_failed_jobs", "rerun_all", "cancel", "index", "query", "node", "callers", "callees", "path", "impact", "architecture", "diff", "diagnose", "contracts", "prs", "symbols", "neighbors", "note", "notes", "requirements", "wiki", "metrics", "federated", "provider", "publish", "create_tag", "tags", "tag", "detail", "by_tag", "repository", "repositories", "capabilities", "efficiency", "recovery")
    private val labels = mapOf(
        "BUILD_REVISION_BINDING" to "Running build and repository revision",
        "AUTHORIZED_CATALOG" to "Authorized tool catalog",
        "FIXTURE_LIFECYCLE" to "Disposable fixture lifecycle",
        "TOOL_ACTION_COVERAGE" to "GitHub tool action coverage",
        "EVIDENCE_SEPARATION" to "Evidence provenance separation",
        "DEVICE_EXECUTION" to "Current device execution",
        "REPOSITORY_GRAPH" to "Selected repository graph",
        "CI_VERIFICATION" to "CI execution verification",
        "API_OPERATION" to "GitHub API operation",
        "REQUIREMENT" to "Requested acceptance criterion"
    )

    fun category(text: String): String {
        val value = text.lowercase()
        return when {
            listOf("separate", "separation", "distinguish").any { it in value } && "device" in value && ("mock" in value || "live" in value) -> "EVIDENCE_SEPARATION"
            ("bind" in value || "binding" in value) && ("build" in value || "revision" in value) -> "BUILD_REVISION_BINDING"
            ("catalog" in value || "catalogue" in value) && ("discover" in value || "exhaust" in value || "authorized" in value) -> "AUTHORIZED_CATALOG"
            ("fixture" in value || "disposable" in value) && listOf("mutation", "write", "cleanup", "delet").any { it in value } -> "FIXTURE_LIFECYCLE"
            "graph" in value -> "REPOSITORY_GRAPH"
            Regex("\\b(device|android|instrumentation)\\b").containsMatchIn(value) -> "DEVICE_EXECUTION"
            Regex("\\b(ci|workflow)\\b").containsMatchIn(value) -> "CI_VERIFICATION"
            GitHubTaskCoverage.matches(text) && ("tool" in value || "integration" in value) -> "TOOL_ACTION_COVERAGE"
            else -> "REQUIREMENT"
        }
    }

    fun snapshot(ledger: JsonObject, receipts: Map<String, JsonObject> = emptyMap(), limit: Int = LIMIT): JsonObject {
        require(limit in 1..LIMIT)
        val all = listOf("requirements", "capabilities").flatMap { (ledger[it] as? JsonArray).orEmpty() }.filterIsInstance<JsonObject>()
        val byId = receipts.values.associateBy { it.text("receipt_id") }
        val rows = all.mapIndexed { index, row ->
            val kind = row.text("kind").takeIf { it in setOf("REQUIREMENT", "CAPABILITY") } ?: "REQUIREMENT"
            val category = if (kind == "CAPABILITY") "API_OPERATION" else row.text("category").takeIf { it in labels } ?: category(row.text("criterion"))
            val state = row.text("state").takeIf { it in setOf("PASS", "FAIL", "BLOCKED", "NOT_TESTED") } ?: "NOT_TESTED"
            val proof = (row["receipt_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.distinct()
            val observed = proof.mapNotNull(byId::get)
            val classified = observed.flatMap(::evidenceClasses).groupingBy { it }.eachCount()
            val stored = row["evidence_class_counts"] as? JsonObject
            val counts = buildJsonObject {
                classes.sorted().forEach { name ->
                    val count = classified[name] ?: (stored?.get(name) as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0) ?: 0
                    put(name, count)
                }
            }
            buildJsonObject {
                put("ordinal", index + 1)
                put("id_sha256", GitHubEvidenceBook.digest(row.text("id").ifBlank { "$kind:$index" }))
                put("kind", kind)
                put("category", category)
                put("label", labels.getValue(category))
                operation(row.text("id"))?.let { put("operation", it) }
                put("required", row["required"] != JsonPrimitive(false))
                put("state", state)
                put("blocker", blocker(category, state))
                put("next_action", nextAction(category, state))
                put("evidence_receipt_count", proof.size)
                put("evidence_receipt_digests", JsonArray(proof.take(8).map { JsonPrimitive(GitHubEvidenceBook.digest(it)) }))
                put("evidence_class_counts", counts)
            }
        }
        val included = JsonArray(rows.take(limit))
        return buildJsonObject {
            put("schema", SCHEMA)
            put("criterion_count", rows.size)
            put("included_count", included.size)
            put("omitted_count", rows.size - included.size)
            put("complete", rows.size == included.size)
            put("criteria_sha256", GitHubEvidenceBook.digest(JsonArray(rows).toString()))
            put("included_sha256", GitHubEvidenceBook.digest(included.toString()))
            put("criteria", included)
        }
    }

    fun valid(value: JsonObject?): Boolean {
        if (value?.text("schema") != SCHEMA) return false
        val rows = value["criteria"] as? JsonArray ?: return false
        val count = (value["criterion_count"] as? JsonPrimitive)?.intOrNull ?: return false
        val omitted = (value["omitted_count"] as? JsonPrimitive)?.intOrNull ?: return false
        return rows.size <= LIMIT &&
            count == rows.size + omitted &&
            omitted >= 0 &&
            value["included_count"] == JsonPrimitive(rows.size) &&
            value["complete"] == JsonPrimitive(omitted == 0) &&
            value.text("included_sha256") == GitHubEvidenceBook.digest(rows.toString()) &&
            value.text("criteria_sha256").matches(Regex("[a-f0-9]{64}")) &&
            (omitted > 0 || value["criteria_sha256"] == value["included_sha256"]) &&
            rows.all { element ->
                val row = element as? JsonObject ?: return@all false
                row.keys == setOf("ordinal", "id_sha256", "kind", "category", "label", "required", "state", "blocker", "next_action", "evidence_receipt_count", "evidence_receipt_digests", "evidence_class_counts") + listOfNotNull("operation".takeIf { "operation" in row }) &&
                    (row["ordinal"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.let { it in 1..count } == true &&
                    row["required"] in setOf(JsonPrimitive(true), JsonPrimitive(false)) &&
                    (row["evidence_receipt_count"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.let { it >= 0 } == true &&
                    row.text("category") in labels &&
                    row.text("label") == labels[row.text("category")] &&
                    row.text("kind") in setOf("REQUIREMENT", "CAPABILITY") &&
                    row.text("state") in setOf("PASS", "FAIL", "BLOCKED", "NOT_TESTED") &&
                    row.text("id_sha256").matches(Regex("[a-f0-9]{64}")) &&
                    row.text("blocker") == blocker(row.text("category"), row.text("state")) &&
                    row.text("next_action") == nextAction(row.text("category"), row.text("state")) &&
                    (row["operation"] == null || operation(row.text("operation")) == row.text("operation")) &&
                    (row["evidence_receipt_digests"] as? JsonArray)?.let { it.size <= 8 && it.all { id -> (id as? JsonPrimitive)?.contentOrNull?.matches(Regex("[a-f0-9]{64}")) == true } } == true &&
                    (row["evidence_class_counts"] as? JsonObject)?.let { it.keys == classes && it.values.all { countValue -> (countValue as? JsonPrimitive)?.intOrNull?.let { it >= 0 } == true } } == true
            }
    }

    private fun operation(id: String): String? {
        val tool = id.substringBefore(':').substringBefore("__")
        val action = id.substringAfter(':', "default").ifBlank { "default" }
        return "$tool:$action".takeIf { tool in tools && action in actions }
    }

    private fun blocker(category: String, state: String): String = when {
        state == "PASS" -> "NONE"
        state == "FAIL" -> "VERIFICATION_FAILED"
        state == "BLOCKED" -> "REQUIRED_OPERATION_BLOCKED"
        category == "DEVICE_EXECUTION" -> "DEVICE_EXECUTION_NOT_OBSERVED"
        category == "AUTHORIZED_CATALOG" -> "CATALOG_NOT_EXHAUSTED"
        category == "FIXTURE_LIFECYCLE" -> "FIXTURE_PROOF_INCOMPLETE"
        category == "REPOSITORY_GRAPH" -> "GRAPH_QUALIFICATION_INCOMPLETE"
        else -> "MATCHING_EVIDENCE_MISSING"
    }

    private fun nextAction(category: String, state: String): String = when {
        state == "PASS" -> "RETAIN_VERIFIED_EVIDENCE"
        category == "AUTHORIZED_CATALOG" -> "FOLLOW_VERSION_BOUND_CATALOG_CURSOR"
        category == "FIXTURE_LIFECYCLE" -> "RECONCILE_OWNED_FIXTURE_THEN_VERIFY_ABSENCE"
        category == "BUILD_REVISION_BINDING" -> "OBSERVE_EXACT_BUILD_AND_REPOSITORY_REVISION"
        category == "DEVICE_EXECUTION" -> "RUN_CURRENT_BUILD_ON_RECORDED_DEVICE"
        category == "REPOSITORY_GRAPH" -> "QUALIFY_SELECTED_GATEWAY_AND_COMMIT"
        category == "EVIDENCE_SEPARATION" -> "CLASSIFY_RETAINED_EVIDENCE_SOURCES"
        category in setOf("API_OPERATION", "TOOL_ACTION_COVERAGE") -> "PERFORM_UNVERIFIED_AUTHORIZED_OPERATION"
        category == "CI_VERIFICATION" -> "VERIFY_OWNED_WORKFLOW_AT_EXACT_REVISION"
        else -> "INSPECT_PROTECTED_CRITERION_AND_MATCHING_EVIDENCE"
    }

    private fun evidenceClasses(receipt: JsonObject): Set<String> {
        if (receipt["trusted"] != JsonPrimitive(true)) return setOf("NOT_TESTED")
        val tool = receipt.text("tool").substringBefore("__")
        val action = (receipt["arguments"] as? JsonObject)?.text("action")
        return buildSet {
            add(
                when (GitHubEvidenceOrigin.of(receipt)) {
                    "LIVE" -> if (tool == "repository_graph") "APP_GATEWAY" else "LIVE_ACCOUNT"
                    "CACHED" -> "CACHED"
                    "LOCAL" -> "LOCAL"
                    "MOCKED" -> "MOCKED"
                    else -> "NOT_TESTED"
                }
            )
            if (tool == "github_ci" && action in setOf("runs", "run", "jobs", "logs", "workflows", "artifacts", "failure")) add("HISTORICAL_CI_OBSERVATION")
        }
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
