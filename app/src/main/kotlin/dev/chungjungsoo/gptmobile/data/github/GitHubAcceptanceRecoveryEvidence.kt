package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Small closed projection of the actual retained preparation; it cannot dispatch or approve work. */
internal object GitHubAcceptanceRecoveryEvidence {
    val preparationStates = setOf("CLEANUP_RESERVE_ACTIVE", "OWNED_FIXTURE_STEP_PREPARED", "BOUNDED_READS_PREPARED", "NO_PENDING_CAPABILITY_PREREQUISITES", "PREREQUISITES_REQUIRED")
    val operationFamilies = setOf("github_context", "github_read", "github_search", "github_pr", "github_issue", "github_ci", "github_watch_checks", "github_stage_patch", "github_commit", "github_write", "github_release", "github_decide", "github_review", "github_engineering", "github_repository", "repository_graph")
    val policyRecoveryHints = setOf("CHECK_AUTHENTICATION", "CHECK_PROTECTION_READ_PERMISSION", "CHECK_RULES_READ_PERMISSION", "CHECK_POLICY_READ_PERMISSIONS", "WAIT_RATE_LIMIT", "REFRESH_POLICY_OBSERVATIONS")
    val fields = setOf(
        "acceptance_preparation_state", "acceptance_ready_read_count", "acceptance_additional_ready_read_count", "acceptance_fixture_creation_admissible", "acceptance_blocked_operation_families", "acceptance_additional_blocked_family_count",
        "status_policy_protection_http_status", "status_policy_rules_http_status", "status_branch_protected", "status_policy_complete", "status_policy_recovery_hint"
    )

    fun fields(verdict: JsonObject): JsonObject = buildJsonObject {
        val checklist = verdict["test_checklist"] as? JsonObject
        val preparation = checklist?.get("preparation") as? JsonObject
        if (preparation?.text("schema") == "github.integration-preparation.v1") {
            preparation.text("status").takeIf { it in preparationStates }?.let { put("acceptance_preparation_state", it) }
            for ((source, destination) in listOf("ready_read_count" to "acceptance_ready_read_count", "additional_ready_read_count" to "acceptance_additional_ready_read_count")) {
                preparation.number(source)?.takeIf { it in 0..16_000_000 }?.let { put(destination, it) }
            }
            (preparation["owned_fixture_creation_admissible"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull?.let { put("acceptance_fixture_creation_admissible", it) }
        }
        val ledger = verdict["acceptance_ledger"] as? JsonObject
        val families = (ledger?.get("capabilities") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .filter { it["required"] == JsonPrimitive(true) && it.text("state") in setOf("BLOCKED", "NOT_TESTED", "FAIL") }
            .map { it.text("id").substringBefore(':').substringBefore("__") }.filter { it in operationFamilies }.distinct()
        if (families.isNotEmpty()) {
            put("acceptance_blocked_operation_families", JsonArray(families.take(8).map(::JsonPrimitive)))
            put("acceptance_additional_blocked_family_count", (families.size - 8).coerceAtLeast(0))
        }
        (verdict["status_coverage"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .firstOrNull { it.text("item") == "Required-check policy" }?.let { policy ->
                for (key in listOf("status_policy_protection_http_status", "status_policy_rules_http_status")) {
                    policy.number(key)?.takeIf { it in 100..599 }?.let { put(key, it) }
                }
                for (key in listOf("status_branch_protected", "status_policy_complete")) {
                    (policy[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull?.let { put(key, it) }
                }
                policy.text("status_policy_recovery_hint").takeIf { it in policyRecoveryHints }?.let { put("status_policy_recovery_hint", it) }
            }
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
}
