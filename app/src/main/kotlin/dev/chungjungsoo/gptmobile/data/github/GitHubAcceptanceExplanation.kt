package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Zero required criteria is an observation to explain, never evidence that an objective passed. */
internal object GitHubAcceptanceExplanation {
    const val FEATURE = "ACCEPTANCE_EMPTY_EXPLANATION_V1"
    const val KEY = "acceptance_explanation"
    private const val SCHEMA = "github.acceptance-explanation.v1"
    private val enums = mapOf(
        "acceptance_applicability" to setOf("REQUIRED", "NOT_APPLICABLE", "UNDETERMINED"),
        "acceptance_contract_state" to setOf("RETAINED", "NOT_RECORDED"),
        "acceptance_plan_state" to setOf("DEFINED", "EMPTY", "NOT_RECORDED"),
        "acceptance_extraction_state" to (GitHubAcceptancePublicationEvidence.Extraction.entries.map { it.name }.toSet() + "NOT_RECORDED"),
        "acceptance_coverage_state" to setOf("RECEIPTS_RETAINED", "NO_RECEIPTS", "NOT_RECORDED"),
        "acceptance_empty_reason" to setOf("CRITERIA_PRESENT", "PRIMARY_FAILURE", "EXTRACTION_FAILED", "EXTRACTION_NOT_RUN", "NOT_APPLICABLE", "MISSING_CONTRACT", "MISSING_PLAN", "NO_REQUIRED_CRITERIA")
    )
    val fields = enums.keys + "acceptance_original_failure_present"

    fun record(report: JsonObject, contract: JsonObject? = null): JsonObject {
        val ledger = report["acceptance_ledger"] as? JsonObject
        val all = (ledger?.get("requirements") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val required = all.count { it["required"] != JsonPrimitive(false) }
        val excluded = all.isNotEmpty() && all.all { it["required"] == JsonPrimitive(false) }
        val accepted = contract?.takeIf { it["schema"] == JsonPrimitive("github.task-contract.v1") && it["binding"] == report["binding"] }
        val plan = accepted?.get("acceptance_criteria") as? JsonArray
        val publication = GitHubAcceptancePublicationEvidence.fields(report)
        val extraction = publication.text("acceptance_extraction_observation").ifBlank { "NOT_RECORDED" }
        val receiptCount = (publication["acceptance_receipt_snapshot_count"] as? JsonPrimitive)?.intOrNull
            ?: ((report["execution_ledger"] as? JsonObject)?.get("receipt_count") as? JsonPrimitive)?.intOrNull
        val primaryFailure = (report["original_failure"] as? JsonObject)?.text("code")?.isNotBlank() == true && GitHubExecutionLedger.hasPrimaryFailure(report)
        return buildJsonObject {
            put("schema", SCHEMA)
            put(
                "acceptance_applicability",
                if (required > 0) {
                    "REQUIRED"
                } else if (excluded) {
                    "NOT_APPLICABLE"
                } else {
                    "UNDETERMINED"
                }
            )
            put("acceptance_contract_state", if (accepted == null) "NOT_RECORDED" else "RETAINED")
            put(
                "acceptance_plan_state",
                if (plan == null) {
                    "NOT_RECORDED"
                } else if (plan.isEmpty()) {
                    "EMPTY"
                } else {
                    "DEFINED"
                }
            )
            put("acceptance_extraction_state", extraction)
            put(
                "acceptance_coverage_state",
                if (receiptCount == null || receiptCount < 0) {
                    "NOT_RECORDED"
                } else if (receiptCount == 0) {
                    "NO_RECEIPTS"
                } else {
                    "RECEIPTS_RETAINED"
                }
            )
            put("acceptance_original_failure_present", primaryFailure)
            put(
                "acceptance_empty_reason",
                when {
                    required > 0 -> "CRITERIA_PRESENT"
                    primaryFailure -> "PRIMARY_FAILURE"
                    extraction == "FAILED" -> "EXTRACTION_FAILED"
                    extraction in setOf("NO_DRAFT", "PLAN_BATCH_LIMIT", "PLAN_INPUT_LIMIT", "PLAN_LIMIT_CHANGED") -> "EXTRACTION_NOT_RUN"
                    excluded -> "NOT_APPLICABLE"
                    accepted == null -> "MISSING_CONTRACT"
                    plan == null || plan.isEmpty() -> "MISSING_PLAN"
                    else -> "NO_REQUIRED_CRITERIA"
                }
            )
        }
    }

    fun retained(value: JsonObject?): JsonObject? {
        if (value?.get("schema") != JsonPrimitive(SCHEMA)) return null
        if (enums.any { (key, allowed) -> value.text(key) !in allowed }) return null
        if (value["acceptance_original_failure_present"] !in setOf(JsonPrimitive(true), JsonPrimitive(false))) return null
        return JsonObject(value.filterKeys { it == "schema" || it in fields })
    }

    fun from(report: JsonObject): JsonObject = retained(report[KEY] as? JsonObject) ?: record(report)

    fun fields(report: JsonObject): JsonObject = JsonObject(from(report).filterKeys { it in fields })

    fun validValue(key: String, value: JsonElement): JsonElement? {
        if (key == "acceptance_original_failure_present") return value.takeIf { it in setOf(JsonPrimitive(true), JsonPrimitive(false)) }
        val allowed = enums[key] ?: return null
        return (value as? JsonPrimitive)?.takeIf { it.isString && it.content in allowed }
    }

    fun terminalReason(explanation: JsonObject?): String? = when (retained(explanation)?.text("acceptance_empty_reason")) {
        "MISSING_CONTRACT" -> "GITHUB_ACCEPTANCE_CONTRACT_MISSING"
        "MISSING_PLAN" -> "GITHUB_ACCEPTANCE_PLAN_MISSING"
        "NO_REQUIRED_CRITERIA" -> "GITHUB_ACCEPTANCE_CRITERIA_MISSING"
        "EXTRACTION_FAILED" -> "GITHUB_EXTRACTION_FAILED"
        "EXTRACTION_NOT_RUN" -> "GITHUB_EXTRACTION_UNAVAILABLE"
        "NOT_APPLICABLE" -> "GITHUB_ACCEPTANCE_NOT_APPLICABLE"
        else -> null
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
}
