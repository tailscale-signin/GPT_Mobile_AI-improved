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
    val deadlineObservations = setOf("CONTRACT_UNAVAILABLE", "NO_ACCEPTED_DEADLINE", "TIMING_UNAVAILABLE", "WINDOW_OPEN", "WINDOW_EXPIRED")
    val preparationStates = setOf("TIME_WINDOW_EXPIRED", "CLEANUP_RESERVE_ACTIVE", "OWNED_FIXTURE_STEP_PREPARED", "BOUNDED_READS_PREPARED", "NO_PENDING_CAPABILITY_PREREQUISITES", "PREREQUISITES_REQUIRED")
    val operationFamilies = setOf("github_context", "github_read", "github_search", "github_pr", "github_issue", "github_ci", "github_watch_checks", "github_stage_patch", "github_commit", "github_write", "github_release", "github_decide", "github_review", "github_engineering", "github_repository", "repository_graph")
    val policyRecoveryHints = setOf("CHECK_AUTHENTICATION", "CHECK_PROTECTION_READ_PERMISSION", "CHECK_RULES_READ_PERMISSION", "CHECK_POLICY_READ_PERMISSIONS", "WAIT_RATE_LIMIT", "REFRESH_POLICY_OBSERVATIONS")
    private val statusObservations = mapOf(
        "Selected branch and current commit" to "BRANCH_HEAD",
        "Recent commits" to "COMMITS",
        "Open pull requests (draft/ready)" to "OPEN_PULLS",
        "Checks and statuses for the current commit" to "COMMIT_CHECKS",
        "Latest workflow" to "LATEST_WORKFLOW",
        "Required-check policy" to "REQUIRED_POLICY",
        "Recent failures" to "RECENT_FAILURES",
        "Outstanding review decisions" to "REVIEW_DECISIONS"
    )
    val statusObservationCodes = statusObservations.values.toSet()
    val fields = setOf(
        "acceptance_prerequisite_blockers", "acceptance_acquisition_allowed", "acceptance_cleanup_reserve", "acceptance_fixture_start_minimum",
        "task_deadline_check", "task_deadline_observation", "task_deadline_clock", "task_deadline_renewed", "task_deadline_window_seconds", "task_deadline_overdue_seconds", "task_deadline_elapsed_seconds", "task_deadline_remaining_seconds",
        "acceptance_preparation_state", "acceptance_ready_read_count", "acceptance_additional_ready_read_count", "acceptance_fixture_creation_admissible", "acceptance_blocked_operation_families", "acceptance_additional_blocked_family_count",
        "status_missing_observations", "status_policy_protection_http_status", "status_policy_rules_http_status", "status_branch_protected", "status_policy_complete", "status_policy_recovery_hint"
    )

    fun fields(verdict: JsonObject): JsonObject = buildJsonObject {
        val failure = verdict["original_failure"] as? JsonObject
        if (failure?.text("stop_reason") == "ACCEPTED_TASK_DEADLINE_REACHED") {
            failure.text("task_deadline_check").takeIf { check -> GitHubExecutionLimits.DeadlineCheck.entries.any { it.name == check } }?.let { put("task_deadline_check", it) }
            failure.text("task_deadline_observation").takeIf { it in deadlineObservations }?.let { put("task_deadline_observation", it) }
            if (failure.text("task_deadline_clock") == "WALL_CLOCK") put("task_deadline_clock", "WALL_CLOCK")
            (failure["task_deadline_renewed"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull?.let { put("task_deadline_renewed", it) }
            for (field in listOf("task_deadline_window_seconds", "task_deadline_overdue_seconds", "task_deadline_elapsed_seconds", "task_deadline_remaining_seconds")) {
                failure.number(field)?.takeIf { it in 0..16_000_000 }?.let { put(field, it) }
            }
        }
        val checklist = verdict["test_checklist"] as? JsonObject
        val preparation = checklist?.get("preparation") as? JsonObject
        if (preparation?.text("schema") == "github.integration-preparation.v1") {
            preparation.text("status").takeIf { it in preparationStates }?.let { put("acceptance_preparation_state", it) }
            for ((source, destination) in listOf("ready_read_count" to "acceptance_ready_read_count", "additional_ready_read_count" to "acceptance_additional_ready_read_count")) {
                preparation.number(source)?.takeIf { it in 0..16_000_000 }?.let { put(destination, it) }
            }
            val budget = preparation["budget"] as? JsonObject
            if (budget != null && (budget["remaining_known"] == JsonPrimitive(true) || budget["stop_reason"] == JsonPrimitive("ACCEPTED_TASK_DEADLINE_REACHED"))) {
                (budget["acquisition_allowed"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull?.let { put("acceptance_acquisition_allowed", it) }
            }
            for ((source, destination) in listOf("cleanup_reserve" to "acceptance_cleanup_reserve", "fixture_start_minimum" to "acceptance_fixture_start_minimum")) {
                budget?.number(source)?.takeIf { it in 0..16_000_000 }?.let { put(destination, it) }
            }
            val blockers = (preparation["blockers"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                .filter { (it["required_capability_ids"] as? JsonArray)?.isNotEmpty() == true }
                .mapNotNull { GitHubCriterionBlocker.from(it.text("code"))?.name }.distinct().take(8)
            if (blockers.isNotEmpty()) put("acceptance_prerequisite_blockers", JsonArray(blockers.map(::JsonPrimitive)))
            (preparation["owned_fixture_creation_admissible"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull?.let { put("acceptance_fixture_creation_admissible", it) }
        }
        val ledger = verdict["acceptance_ledger"] as? JsonObject
        val families = (ledger?.get("capabilities") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .filter { it["required"] == JsonPrimitive(true) && it.text("state") in setOf("BLOCKED", "NOT_TESTED", "FAIL") }
            .map { it.text("id").substringBefore(':').githubOperationFamily() }.filter { it in operationFamilies }.distinct()
        if (families.isNotEmpty()) {
            put("acceptance_blocked_operation_families", JsonArray(families.take(8).map(::JsonPrimitive)))
            put("acceptance_additional_blocked_family_count", (families.size - 8).coerceAtLeast(0))
        }
        val coverage = (verdict["status_coverage"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val missing = coverage.filter { it["observed"] == JsonPrimitive(false) }.mapNotNull { statusObservations[it.text("item")] }.distinct()
        if (missing.isNotEmpty()) put("status_missing_observations", JsonArray(missing.map(::JsonPrimitive)))
        coverage.firstOrNull { it.text("item") == "Required-check policy" }?.let { policy ->
            for (key in listOf("status_policy_protection_http_status", "status_policy_rules_http_status")) {
                policy.number(key)?.takeIf { it in 100..599 }?.let { put(key, it) }
            }
            for (key in listOf("status_branch_protected", "status_policy_complete")) {
                (policy[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull?.let { put(key, it) }
            }
            policy.text("status_policy_recovery_hint").takeIf { it in policyRecoveryHints }?.let { put("status_policy_recovery_hint", it) }
            GitHubPolicyResponseObservation.coverageFields(policy).forEach { (key, value) -> put(key, value) }
        }
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
}
