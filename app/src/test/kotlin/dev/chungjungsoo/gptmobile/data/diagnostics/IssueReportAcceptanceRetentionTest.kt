package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueReportAcceptanceRetentionTest {

    @Test fun saturatedRecoveryAndPublicationMetadataRetainsTheLastCriterionAndHonestCounts() {
        for (captured in listOf(1, 8)) {
            val fields = saturatedFields(captured)
            val snapshot = requireNotNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", fields, 1_000L))
            assertEquals(3, snapshot.schemaVersion)
            assertTrue(snapshot.isValid())
            val retained = snapshot.values["unmet_criterion_id_hashes"] as? JsonArray
            assertNotNull("A bounded report must retain its useful criterion sample", retained)
            assertEquals(1, requireNotNull(retained).size)
            for (key in criterionFields) {
                assertEquals("The same first row must survive in every column: $key", JsonArray(listOf(JsonPrimitive((fields.getValue(key) as List<*>).first().toString()))), snapshot.values[key])
                assertFalse(key in snapshot.omittedFields)
                assertFalse(key in snapshot.unavailableFields)
            }
            assertEquals(JsonPrimitive(11), snapshot.values["unmet_criterion_count"])
            assertEquals(JsonPrimitive(10), snapshot.values["unmet_criterion_omitted_count"])
            assertTrue(snapshot.omittedFields.isNotEmpty())
            assertTrue(snapshot.omittedFields.none { it in snapshot.values })
            assertTrue(snapshot.values.keys.none { it == "raw_failure" })
            val encoded = IssueReport.json.encodeToJsonElement(IssueReportDiagnostics.serializer(), snapshot)
            assertTrue(encoded.toString().encodeToByteArray().size <= IssueReportDiagnostics.MAX_BYTES)
            assertEquals(snapshot, IssueReportDiagnostics.fromJson(encoded))
        }
    }

    @Test fun malformedCriterionAlignmentIsRejectedBeforeRetentionPriorityApplies() {
        val fields = saturatedFields(2) + ("unmet_criterion_states" to listOf("NOT_TESTED"))
        val snapshot = requireNotNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", fields, 1_000L))
        assertTrue(snapshot.isValid())
        assertTrue(criterionFields.none { it in snapshot.values })
        assertTrue(snapshot.omittedFields.containsAll(criterionFields))
        assertTrue(IssueReport.json.encodeToString(IssueReportDiagnostics.serializer(), snapshot).encodeToByteArray().size <= IssueReportDiagnostics.MAX_BYTES)
    }

    private fun saturatedFields(captured: Int): Map<String, Any?> = mapOf(
        "diagnostic_area" to "ACCEPTANCE",
        "incident_phase" to "acceptance_verification",
        "causal_provenance" to "UNAVAILABLE",
        "incident_evidence_status" to "CAUSAL_EVIDENCE_UNAVAILABLE",
        "unmet_criterion_id_hashes" to List(captured) { (it + 1).toString().repeat(64) },
        "unmet_criterion_reason_hashes" to List(captured) { (it + 1).toString().repeat(64) },
        "unmet_criterion_categories" to List(captured) { "BUILD_REVISION_BINDING" },
        "unmet_criterion_states" to List(captured) { "NOT_TESTED" },
        "unmet_criterion_blockers" to List(captured) { "WORKFLOW_RUN_OR_JOB_REQUIRED" },
        "unmet_criterion_count" to 11,
        "unmet_criterion_omitted_count" to 11 - captured,
        "evidence_ref_hashes" to List(8) { "a".repeat(64) },
        "checkpoint_hash" to "b".repeat(64),
        "effective_call_limit" to 256,
        "used_calls" to 82,
        "remaining_calls" to 174,
        "verification_reserve" to 8,
        "pending_continuations" to 4,
        "unresolved_outcomes" to 1,
        "required_capability_count" to 53,
        "verified_capability_count" to 44,
        "blocked_capability_count" to 2,
        "untested_capability_count" to 7,
        "acceptance_capability_scope" to "FULL_CATALOG",
        "acceptance_pagination_mode" to "COMPLETE_INVENTORY_REQUESTED",
        "acceptance_evidence_scope" to "RETAINED_VERDICT",
        "acceptance_preparation_state" to "PREREQUISITES_REQUIRED",
        "acceptance_ready_read_count" to 0,
        "acceptance_additional_ready_read_count" to 0,
        "acceptance_fixture_creation_admissible" to false,
        "acceptance_acquisition_allowed" to true,
        "acceptance_cleanup_reserve" to 8,
        "acceptance_fixture_start_minimum" to 12,
        "acceptance_prerequisite_blockers" to listOf("WORKFLOW_RUN_OR_JOB_REQUIRED", "OWNED_FIXTURE_REQUIRED"),
        "acceptance_blocked_operation_families" to listOf("github_ci", "github_read"),
        "acceptance_additional_blocked_family_count" to 0,
        "status_missing_observations" to listOf("COMMIT_CHECKS", "LATEST_WORKFLOW", "REQUIRED_POLICY"),
        "status_policy_protection_http_status" to 200,
        "status_policy_rules_http_status" to 200,
        "status_branch_protected" to true,
        "status_policy_complete" to true,
        "status_policy_recovery_hint" to "REFRESH_POLICY_OBSERVATIONS",
        "task_deadline_observation" to "WINDOW_OPEN",
        "task_deadline_window_seconds" to 600,
        "task_deadline_elapsed_seconds" to 30,
        "task_deadline_remaining_seconds" to 570,
        "acceptance_publication_source" to "STATUS",
        "acceptance_publication_entry" to "STATUS_SNAPSHOT",
        "acceptance_extraction_observation" to "NOT_NEEDED",
        "acceptance_draft_state" to "ABSENT",
        "acceptance_extraction_planned_batches" to 0,
        "acceptance_receipt_snapshot_count" to 12,
        "acceptance_claim_count" to 0,
        "acceptance_advertised_tool_count" to 53,
        "acceptance_applicability" to "REQUIRED",
        "acceptance_contract_state" to "RETAINED",
        "acceptance_plan_state" to "DEFINED",
        "acceptance_extraction_state" to "NOT_NEEDED",
        "acceptance_coverage_state" to "RECEIPTS_RETAINED",
        "acceptance_original_failure_present" to false,
        "acceptance_empty_reason" to "CRITERIA_PRESENT",
        "run_id_hash" to "c".repeat(64),
        "invocation_id_hash" to "d".repeat(64),
        "call_id_hash" to "e".repeat(64),
        "job_id_hash" to "f".repeat(64),
        "profile_hash" to "a".repeat(64),
        "model_hash" to "b".repeat(64),
        "causal_id_hash" to "c".repeat(64),
        "fixture_approval_state" to "CONDITIONAL",
        "fixture_decision_mode" to "ASK",
        "raw_failure" to "fictional-private-detail-never-exported"
    )

    private val criterionFields = setOf(
        "unmet_criterion_id_hashes",
        "unmet_criterion_reason_hashes",
        "unmet_criterion_categories",
        "unmet_criterion_states",
        "unmet_criterion_blockers"
    )
}
