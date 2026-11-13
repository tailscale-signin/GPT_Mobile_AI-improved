package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A stable, receipt-linked projection retained with every encrypted checklist/verdict checkpoint. */
internal object GitHubAcceptanceLedger {
    enum class State { PASS, FAIL, BLOCKED, NOT_TESTED }

    fun state(status: String): State = when (status) {
        "PASS", "PASSED", "RECOVERED" -> State.PASS
        "FAIL", "FAILED" -> State.FAIL
        "BLOCKED", "PREFLIGHT_REJECTED" -> State.BLOCKED
        else -> State.NOT_TESTED
    }

    fun snapshot(
        requirements: JsonArray = JsonArray(emptyList()),
        capabilities: JsonArray = JsonArray(emptyList()),
        requestDigest: String? = null
    ): JsonObject {
        fun entries(rows: JsonArray, kind: String) = rows.filterIsInstance<JsonObject>().map { row ->
            val excluded = row["required"] == JsonPrimitive(false) || row.text("status") in setOf("OUT_OF_SCOPE", "NOT_REQUESTED")
            buildJsonObject {
                put("id", row.text("id"))
                put("kind", kind)
                row["acceptance_role"]?.let { put("acceptance_role", it) }
                put("criterion", row.text(if (kind == "REQUIREMENT") "requirement" else "scope"))
                if (kind == "REQUIREMENT") put("category", GitHubAcceptanceDiagnostics.category(row.text("requirement")))
                put("required", !excluded)
                put("state", state(row.text("acceptance_state").ifBlank { row.text("status") }).name)
                row.text("acceptance_evaluator_branch").takeIf { it in setOf("STATUS_OBSERVATION", "STATUS_UNCERTAINTY_REPORT", "INFORMATIONAL_CLAIMS", "OPERATION_ACCEPTANCE", "REVISION_TEST_ACCEPTANCE", "ACCEPTANCE_MAPPING_REQUIRED") }?.let { put("acceptance_evaluator_branch", it) }
                row.text("acceptance_mismatch").takeIf { it in setOf("MATCHING_OBSERVATION_INCOMPLETE", "UNSUPPORTED_CRITERION", "UNRESOLVED_USER_DECISION", "COMPARISON_REQUIRED", "NO_MATCHING_CLAIMS", "LINKED_RECEIPT_INELIGIBLE", "UNSUPPORTED_CLAIMS", "ACCEPTANCE_EVIDENCE_MISSING", "ACCEPTANCE_MAPPING_REQUIRED", "NO_EXECUTION_PROOF") }?.let { put("acceptance_mismatch", it) }
                for (key in listOf("eligible_receipt_count", "excluded_receipt_count")) {
                    (row[key] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull()?.takeIf { it in 0..16_000_000 }?.let { put(key, it) }
                }
                row.text("receipt_count_scope").takeIf { it == "LINKED_CRITERION_EVIDENCE" }?.let { put("receipt_count_scope", it) }
                (row["receipt_exclusion_reasons"] as? JsonArray)?.takeIf { values -> values.size <= 3 && values.all { it in listOf("MISSING_IDENTITY", "UNVERIFIED", "NOT_SUCCESSFUL").map(::JsonPrimitive) } }?.let { put("receipt_exclusion_reasons", it) }
                GitHubCriterionBlocker.from(row.text("blocker_code"))?.let { put("blocker_code", it.name) }
                (row["attempted"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { put("attempted", it > 0) }
                put("reason", row.text("acceptance").ifBlank { row.text("blocker").ifBlank { row.text("reason").ifBlank { row.text("next_action") } } })
                put(
                    "receipt_ids",
                    JsonArray(
                        listOf("evidence_receipt_ids", "receipt_ids", "implementation_receipts", "test_receipts", "operation_receipts", "verification_receipts")
                            .flatMap { (row[it] as? JsonArray).orEmpty() }.distinct()
                    )
                )
                (row["readiness"] as? JsonObject)?.let { put("readiness", it) }
                if (kind == "CAPABILITY") {
                    listOf("requires_live_transport", "live_verified", "response_validated", "evidence_origins", "acceptance_basis").forEach { key -> row[key]?.let { put(key, it) } }
                }
            }
        }
        val required = entries(requirements, "REQUIREMENT")
        val catalog = entries(capabilities, "CAPABILITY")
        val operations = catalog.filter { it["acceptance_role"] != JsonPrimitive("DIAGNOSTIC_ONLY") }
        val inventory = catalog.filter { it["acceptance_role"] == JsonPrimitive("DIAGNOSTIC_ONLY") }
        val requiredCriteria = required.filter { it["required"] == JsonPrimitive(true) }
        return buildJsonObject {
            put("schema", "github.acceptance-ledger.v1")
            requestDigest?.let { put("request_digest", it) }
            put("requirements", JsonArray(required))
            put("capabilities", JsonArray(operations))
            put("capability_inventory", JsonArray(inventory))
            put("capability_inventory_scope", "Diagnostic observations; these are not task acceptance criteria or prerequisites.")
            // Capability success proves only that API action. It cannot satisfy a product requirement.
            put("completion_verified", requiredCriteria.isNotEmpty() && requiredCriteria.all { it["state"] == JsonPrimitive(State.PASS.name) })
            put("state_counts", buildJsonObject { State.entries.forEach { state -> put(state.name, (required + operations).count { it["state"] == JsonPrimitive(state.name) }) } })
            put("state_counts_scope", "All retained requirement and capability rows, including excluded rows; not an acceptance denominator.")
            put("requirement_counts", counts(required))
            put("capability_counts", counts(operations))
            put("capability_inventory_counts", counts(inventory))
        }
    }

    fun counts(rows: List<JsonObject>): JsonObject = buildJsonObject {
        val required = rows.filter { it["required"] == JsonPrimitive(true) }
        put("total", rows.size)
        put("required", required.size)
        put("excluded", rows.size - required.size)
        put("required_passed", required.count { it["state"] == JsonPrimitive(State.PASS.name) })
        put("required_state_counts", buildJsonObject { State.entries.forEach { state -> put(state.name, required.count { it["state"] == JsonPrimitive(state.name) }) } })
    }

    fun attach(value: JsonObject): JsonObject = attach(value, emptyMap())

    fun attach(value: JsonObject, receipts: Map<String, JsonObject>): JsonObject {
        val requirements = value["requirements"] as? JsonArray ?: JsonArray(emptyList())
        val capabilities = value["capabilities"] as? JsonArray ?: (value["test_checklist"] as? JsonObject)?.get("capabilities") as? JsonArray ?: JsonArray(emptyList())
        val ledger = snapshot(requirements, capabilities, value.text("request_digest").takeIf(String::isNotBlank))
        val pages = GitHubAcceptanceDiagnostics.pages(ledger, receipts)
        val diagnostics = pages.firstOrNull() ?: GitHubAcceptanceDiagnostics.snapshot(ledger, receipts)
        return JsonObject(value + ("acceptance_ledger" to JsonObject(ledger + mapOf("diagnostic_projection" to diagnostics, "diagnostic_pages" to JsonArray(pages)))))
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
