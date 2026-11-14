package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidentCapsule
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Exact verdict/checkpoint only. Missing counters stay absent, and no retained operation is replayed. */
internal object GitHubAcceptanceReportDiagnostics {
    fun fields(verdict: JsonObject, retainedReport: Boolean): JsonObject = buildJsonObject {
        val ledger = verdict["acceptance_ledger"] as? JsonObject ?: return@buildJsonObject
        put("diagnostic_area", "ACCEPTANCE")
        put("diagnostic_capture_mode", "INCIDENT")
        put("acceptance_evidence_scope", if (retainedReport) "RETAINED_VERDICT" else "CURRENT_VERDICT")
        (verdict["unresolved_claims"] as? JsonArray)?.let { claims ->
            put("acceptance_unresolved_claim_count", claims.size)
            val reasons = claims.filterIsInstance<JsonObject>().map { it.text("reason") }
                .filter { it in setOf("RECEIPT_NOT_FOUND", "UNTRUSTED_RECEIPT", "AUTHORIZATION_REVALIDATION_REQUIRED", "OPERATION_FAILED", "CLAIM_VALUE_MISSING", "POINTER_NOT_IN_EVIDENCE", "FIELD_SEMANTICS_MISMATCH", "OBSERVATION_TIME_UNKNOWN", "HISTORICAL_OBSERVATION", "MUTABLE_EVIDENCE_EXPIRED_OR_REVISION_CHANGED", "VALUE_MISMATCH", "EXACT_RECEIPT_MATCH") }.distinct().take(8)
            if (reasons.isNotEmpty()) put("acceptance_claim_failure_reasons", JsonArray(reasons.map(::JsonPrimitive)))
        }
        val requirements = (ledger["requirements"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val unmetRequirements = requirements.filter { it["required"] == JsonPrimitive(true) && it["state"] != JsonPrimitive("PASS") }
        val capabilities = (ledger["capabilities"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter { it["required"] == JsonPrimitive(true) }
        if (ledger["capabilities"] is JsonArray) {
            put("required_capability_count", capabilities.size)
            put("verified_capability_count", capabilities.count { it["state"] == JsonPrimitive("PASS") })
            put("blocked_capability_count", capabilities.count { it["state"] == JsonPrimitive("BLOCKED") })
            put("untested_capability_count", capabilities.count { it["state"] == JsonPrimitive("NOT_TESTED") })
            put("acceptance_failed_operation_count", capabilities.count { it["state"] == JsonPrimitive("FAIL") })
        }
        for (key in listOf("task_kind", "acceptance_evaluator_branch", "acceptance_mismatch", "expected_operation", "expected_action", "eligible_receipt_count", "excluded_receipt_count", "receipt_count_scope")) {
            val observed = unmetRequirements.map { it[key] }
            val value = verdict[key] ?: observed.takeIf { rows -> rows.isNotEmpty() && rows.all { it != null } }?.distinct()?.singleOrNull()
            value?.let { IssueReportIncidentCapsule.validValue(key, it) }?.let { put(key, it) }
        }
        // A report with several blockers should retain known reasons even when
        // different criteria excluded receipts for different reasons.
        val exclusionReasons = (listOfNotNull(verdict["receipt_exclusion_reasons"]) + unmetRequirements.mapNotNull { it["receipt_exclusion_reasons"] })
            .mapNotNull { IssueReportIncidentCapsule.validValue("receipt_exclusion_reasons", it) as? JsonArray }
            .flatten().distinct().take(8)
        if (exclusionReasons.isNotEmpty()) put("receipt_exclusion_reasons", JsonArray(exclusionReasons))
        val evidence = (unmetRequirements + capabilities.filter { it["state"] != JsonPrimitive("PASS") }).flatMap { row ->
            (row["receipt_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.contentOrNull }
        }.distinct().take(8).mapNotNull { IssueReportContext.identifierHash("acceptance-receipt", it) }
        if (evidence.isNotEmpty()) put("evidence_ref_hashes", JsonArray(evidence.map(::JsonPrimitive)))
        (verdict["stop_reason"] as? JsonObject)?.number("pending_remote_continuations")?.let {
            put("acceptance_pending_remote_continuations", it)
        }
        val unmet = listOf("requirements", "capabilities").asSequence()
            .flatMap { (ledger[it] as? JsonArray).orEmpty().asSequence() }.filterIsInstance<JsonObject>()
            .filter { it["required"] == JsonPrimitive(true) && it["state"] != JsonPrimitive("PASS") }
            .toList()
        put("unmet_criterion_count", unmet.size)
        for ((section, target) in listOf("requirements" to "unmet_requirement_count", "capabilities" to "unmet_capability_count")) {
            val rows = ledger[section] as? JsonArray ?: continue
            put(target, rows.filterIsInstance<JsonObject>().count { it["required"] == JsonPrimitive(true) && it["state"] != JsonPrimitive("PASS") })
        }
        // Counts describe recorded blocker codes, including rows omitted from the compact sample.
        val blockers = unmet.map { it.text("blocker_code") }
        put("acceptance_approval_blocked_total", blockers.count { it in setOf("FIXTURE_APPROVAL_MODE_REQUIRED", "FIXTURE_APPROVAL_STATE_UNKNOWN", "APPROVAL_REQUIRED", "AUTHORIZATION_REQUIRED") })
        put("acceptance_input_blocked_total", blockers.count { it in setOf("REPOSITORY_INPUT_REQUIRED", "WORKFLOW_RUN_OR_JOB_REQUIRED", "ISSUE_ID_REQUIRED", "PULL_REQUEST_ID_REQUIRED", "RELEASE_OR_TAG_REQUIRED", "OWNED_FIXTURE_REQUIRED") })
        put("acceptance_live_blocked_total", blockers.count { it == "LIVE_EVIDENCE_REQUIRED" })
        put("acceptance_continuation_blocked_total", blockers.count { it == "CONTINUATION_REQUIRED" })
        // Sample a representative of every observed blocker before taking
        // duplicates, so a long prerequisite list does not hide other causes.
        val firstByBlocker = unmet.distinctBy { it.text("blocker_code").ifBlank { it.text("state") } }.take(8)
        val selected = (firstByBlocker + unmet.filter { it !in firstByBlocker }).take(8)
        put("unmet_criterion_omitted_count", unmet.size - selected.size)
        if (selected.isNotEmpty()) {
            val projection = GitHubAcceptanceDiagnostics.snapshot(buildJsonObject { put("requirements", JsonArray(selected)) }, limit = 8)
            val rows = (projection["criteria"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            // An absent source ID or reason is unknown; do not invent a hashed placeholder.
            if (selected.all { it.text("id").isNotBlank() }) {
                put("unmet_criterion_id_hashes", JsonArray(rows.map { requireNotNull(it["id_sha256"]) }))
            }
            if (selected.all { it.text("reason").isNotBlank() }) {
                put("unmet_criterion_reason_hashes", JsonArray(selected.map { JsonPrimitive(GitHubEvidenceBook.digest("gptmobile.acceptance-reason.v1:${it.text("reason")}")) }))
            }
            for ((source, target) in listOf("category" to "unmet_criterion_categories", "state" to "unmet_criterion_states", "blocker" to "unmet_criterion_blockers")) {
                put(target, JsonArray(rows.map { requireNotNull(it[source]) }))
            }
        }
        (verdict["status_coverage"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .firstOrNull { it.text("item") == "Required-check policy" }?.let { coverage ->
                if (coverage.text("branch_protection_observation") == "NOT_FOUND_OR_INACCESSIBLE") {
                    put("status_policy_recovery", "CHECK_PROTECTION_READ_PERMISSION")
                }
                for (key in listOf("branch_protection_observation", "branch_rules_observation")) {
                    coverage[key]?.let { put(key, it) }
                }
            }
        val checklist = verdict["test_checklist"] as? JsonObject
        val approval = checklist?.get("fixture_approval_preflight") as? JsonObject
        approval?.get("state")?.let { put("fixture_approval_state", it) }
        approval?.get("decision_mode")?.let { IssueReportIncidentCapsule.validValue("fixture_decision_mode", it) }?.let {
            put("fixture_decision_mode", it)
        }
        val budget = checklist?.get("stage_budget") as? JsonObject ?: verdict["stage_budget"] as? JsonObject
        for (key in listOf("effective_call_limit", "used_calls", "remaining_calls", "verification_reserve")) {
            budget?.number(key)?.let { put(key, it) }
        }
        (verdict["stop_reason"] as? JsonObject)?.let { stop ->
            for (key in listOf("pending_continuations", "pending_local_fragments", "pending_remote_continuations")) {
                stop.number(key)?.let { put(key, it) }
            }
            (stop["required_capability_blocker_counts"] as? JsonObject)?.let { counts ->
                counts.number("FIXTURE_APPROVAL_MODE_REQUIRED")?.let { put("fixture_approval_blocked_count", it) }
                counts.number("OWNED_FIXTURE_REQUIRED")?.let { put("owned_fixture_blocked_count", it) }
                counts.number("REPOSITORY_INPUT_REQUIRED")?.let { put("repository_input_blocked_count", it) }
                counts.number("WORKFLOW_RUN_OR_JOB_REQUIRED")?.let { put("workflow_input_blocked_count", it) }
                counts.number("LIVE_EVIDENCE_REQUIRED")?.let { put("live_evidence_blocked_count", it) }
            }
        }
        (verdict["execution_ledger"] as? JsonObject)?.number("unresolved_remote_outcomes")?.let { put("unresolved_outcomes", it) }
        val checkpoint = buildJsonObject {
            put("acceptance_ledger", ledger)
            budget?.let { put("stage_budget", it) }
        }
        put("checkpoint_hash", GitHubEvidenceBook.digest(checkpoint.toString()))
    }

    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String): Int? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 0..16_000_000 }
}
