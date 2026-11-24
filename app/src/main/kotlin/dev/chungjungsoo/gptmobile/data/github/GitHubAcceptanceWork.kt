package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Read-only planning view over the accepted ledger. Tool families are discovery hints. */
internal object GitHubAcceptanceWork {
    data class Step(val action: String, val tools: List<String> = emptyList())

    fun outstanding(report: JsonObject?): List<JsonObject> {
        val ledger = report?.get("acceptance_ledger") as? JsonObject
        val rows = (ledger?.get("requirements") ?: report?.get("remaining_requirements") ?: report?.get("requirements")) as? JsonArray
        return rows.orEmpty().filterIsInstance<JsonObject>().filter {
            it["required"] != JsonPrimitive(false) &&
                it.text("state").ifBlank { it.text("acceptance_state").ifBlank { it.text("status") } } !in setOf("PASS", "PASSED", "RECOVERED", "OUT_OF_SCOPE", "NOT_REQUESTED")
        }.sortedBy { deferred(it) }
    }

    /** Rehydrate proof for actionable criteria and their prerequisites, including already-passed prerequisites. */
    fun replayReceiptIds(report: JsonObject?): Set<String> {
        val ledger = report?.get("acceptance_ledger") as? JsonObject ?: return emptySet()
        val rows = (ledger["requirements"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().associateBy { it.text("id") }
        val pending = ArrayDeque(outstanding(report))
        val visited = mutableSetOf<String>()
        val receipts = linkedSetOf<String>()
        while (pending.isNotEmpty()) {
            val row = pending.removeFirst()
            val id = row.text("id")
            if (id.isBlank() || !visited.add(id) || row["required"] == JsonPrimitive(false) || gated(row)) continue
            (row["receipt_ids"] as? JsonArray).orEmpty().filterIsInstance<JsonPrimitive>().forEach { receipts += it.content }
            val contract = row["criterion_contract"] as? JsonObject
            for (dependencies in listOf(row["pending_dependencies"], contract?.get("depends_on"))) {
                (dependencies as? JsonArray).orEmpty().filterIsInstance<JsonPrimitive>().forEach { rows[it.content]?.let(pending::addLast) }
            }
        }
        return receipts
    }

    private fun gated(row: JsonObject): Boolean = row.text("acceptance_mismatch") == "UNRESOLVED_USER_DECISION" ||
        row.text("blocker_code") in setOf("AUTHORIZATION_REQUIRED", "USER_DECISION_REQUIRED", "APPROVAL_REQUIRED", "USER_INPUT_REQUIRED", "OUT_OF_SCOPE", "REPOSITORY_SCOPE_EXCLUDED") ||
        (row["readiness"] as? JsonObject)?.text("authorization") in setOf("DENIED", "NOT_AUTHORIZED")

    fun deferred(row: JsonObject): Boolean = gated(row) || (row["pending_dependencies"] as? JsonArray).orEmpty().isNotEmpty()

    fun step(row: JsonObject): Step? {
        if (gated(row)) return Step("Wait for the recorded decision or permission.")
        if (deferred(row)) return Step("Verify the listed prerequisite criteria first.")
        val mismatch = row.text("acceptance_mismatch")
        val linked = (row["eligible_receipt_count"] as? JsonPrimitive)?.intOrNull ?: 0
        if (linked > 0 && mismatch in setOf("NO_MATCHING_CLAIMS", "ACCEPTANCE_MAPPING_REQUIRED", "UNSUPPORTED_CLAIMS", "LINKED_RECEIPT_INELIGIBLE")) {
            return Step("Check retained observations against this criterion and link eligible claims and receipts. Acquire only the missing proof.")
        }
        val producer = (row["criterion_contract"] as? JsonObject)?.text("evidence_producer")
        return when (producer) {
            "COMPARISON_OBSERVATION", "DIFF_REVIEW" -> Step("Inspect the comparison between the accepted base and head.", listOf("github_pr", "github_read"))
            "SOURCE_OBSERVATION", "DEFECT_ANALYSIS" -> Step("Locate and inspect the source range and relevant tests at the selected revision.", listOf("github_search", "github_read"))
            "REVISION_AND_TESTS", "REGRESSION_AND_TESTS" -> Step("Identify the published change or missing implementation. Reconcile existing changes before an authorized edit, then verify tests at the resulting revision.", listOf("github_context", "github_read", "github_ci"))
            "VALIDATION_JOBS" -> Step("Read executed test jobs at the implementation revision and latest workflow attempt.", listOf("github_ci"))
            "DRAFT_PULL_REQUEST" -> Step("Find the matching pull request by head and base and verify its current draft state.", listOf("github_pr"))
            "EVIDENCE_REPORT" -> Step("Synthesize verified outcomes with their source observations and identify any unverified work.")
            else -> null
        }
    }

    fun projection(rows: List<JsonObject>, acceptedRequest: String = "", limit: Int = 8): JsonObject {
        val selected = mutableListOf<JsonObject>()
        var chars = 0
        for (row in rows.take(limit)) {
            val step = step(row)
            val item = buildJsonObject {
                put("id", row.text("id").take(128))
                val criterion = row.text("criterion").ifBlank { row.text("requirement") }
                if (criterion.isNotBlank() && criterion in acceptedRequest) put("criterion", criterion.take(180))
                put("deferred", deferred(row))
                put("next_step", step?.action ?: "Inspect the matching evidence for this criterion.")
                val deps = (row["pending_dependencies"] as? JsonArray).orEmpty().filterIsInstance<JsonPrimitive>().take(3)
                if (deps.isNotEmpty()) put("depends_on", JsonArray(deps.map { JsonPrimitive(it.content.take(64)) }))
                GitHubCriterionBlocker.from(row.text("blocker_code"))?.let { put("blocker", it.name) }
                val tools = step?.tools.orEmpty()
                if (tools.isNotEmpty()) put("tool_families", JsonArray(tools.map(::JsonPrimitive)))
            }
            chars += item.toString().length + 1
            if (chars > 1800) break
            selected += item
        }
        return buildJsonObject {
            put("outstanding", rows.size)
            put("omitted", rows.size - selected.size)
            put("criteria", JsonArray(selected))
        }
    }

    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.content.orEmpty()
}
