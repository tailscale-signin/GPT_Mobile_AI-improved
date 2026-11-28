package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReport
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportDiagnostics
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubAcceptanceCompactionTest {
    @Test fun deadlineReportKeepsObservedBlockerTotals() {
        val codes = listOf("FIXTURE_APPROVAL_MODE_REQUIRED", "REPOSITORY_INPUT_REQUIRED", "LIVE_EVIDENCE_REQUIRED", "CONTINUATION_REQUIRED")
        val rows = List(70) { index ->
            buildJsonObject {
                put("id", "private-requirement-$index")
                put("required", true)
                put("state", "NOT_TESTED")
                put("kind", "REQUIREMENT")
                put("category", "FIXTURE_LIFECYCLE")
                put("reason", "private-reason-$index")
                put("blocker_code", codes[index % 4])
                put("receipt_ids", JsonArray(listOf(JsonPrimitive("private-receipt-$index"))))
            }
        }
        val verdict = buildJsonObject {
            put(
                "acceptance_ledger",
                buildJsonObject {
                    put("requirements", JsonArray(rows))
                    put("capabilities", JsonArray(emptyList()))
                }
            )
            put(
                "claims",
                JsonArray(
                    List(7) {
                        buildJsonObject {
                            put("status", if (it < 6) "VERIFIED" else "NOT_MEASURED")
                            put("reason", "EXACT_RECEIPT_MATCH")
                            put("freshness_predicates", JsonArray(emptyList()))
                        }
                    }
                )
            )
            put(
                "original_failure",
                buildJsonObject {
                    put("code", "GITHUB_TASK_INCOMPLETE")
                    put("stop_reason", "ACCEPTED_TASK_DEADLINE_REACHED")
                    put("task_deadline_check", "BEFORE_MODEL_LOOP")
                    put("task_deadline_observation", "WINDOW_EXPIRED")
                    put("task_deadline_clock", "WALL_CLOCK")
                    put("task_deadline_source", "EXPLICIT_USER_REQUEST")
                    put("task_deadline_renewed", false)
                    put("task_deadline_window_seconds", 3600)
                    put("task_deadline_overdue_seconds", 46)
                    put("task_deadline_elapsed_seconds", 3646)
                    put("task_deadline_remaining_seconds", 0)
                }
            )
            put("publication_observation", GitHubAcceptancePublicationEvidence.record(GitHubAcceptancePublicationEvidence.Source.COVERAGE, 151, null, GitHubAcceptancePublicationEvidence.Extraction.NOT_NEEDED, null, entry = GitHubAcceptancePublicationEvidence.Entry.TASK_DEADLINE))
            put(
                "test_checklist",
                buildJsonObject {
                    put("coverage_scope", "FULL_CATALOG")
                    put(
                        "pagination_resume",
                        buildJsonObject {
                            put("mode", "BOUNDED_SMOKE_SAMPLE")
                            put("workflow_dependency_observation", "UNOBSERVED")
                        }
                    )
                    put(
                        "fixture_approval_preflight",
                        buildJsonObject {
                            put("state", "CONDITIONAL")
                            put("decision_mode", "ASK")
                        }
                    )
                    put(
                        "preparation",
                        buildJsonObject {
                            put("schema", "github.integration-preparation.v1")
                            put("status", "PREREQUISITES_REQUIRED")
                            put("ready_read_count", 10)
                            put("additional_ready_read_count", 0)
                            put("owned_fixture_creation_admissible", false)
                            put(
                                "budget",
                                buildJsonObject {
                                    put("remaining_known", true)
                                    put("acquisition_allowed", true)
                                    put("cleanup_reserve", 0)
                                    put("fixture_start_minimum", 13)
                                }
                            )
                        }
                    )
                }
            )
        }
        val fields = GitHubAcceptanceReportDiagnostics.fields(verdict, false)
        val source = JsonObject(
            fields + buildJsonObject {
                put("run_id_hash", "a".repeat(64))
                put("incident_phase", "task_budget")
                put("causal_provenance", "UNAVAILABLE")
                put("causal_failure_code", "GITHUB_TASK_INCOMPLETE")
                put("incident_evidence_status", "CAUSAL_EVIDENCE_UNAVAILABLE")
                put("incident_origin", "APPLICATION")
                put("recovery_observation", "UNOBSERVED")
                put("incident_capture_site", "AGENT_FAILED_EVENT")
                put("incident_phase_provenance", "CAPTURE_BOUNDARY_ONLY")
                put("graph_input_blocked_count", 16)
                put("repository_graph_observation", "UNOBSERVED")
                put("failed_event_provider_text_seen", true)
                put("failed_event_answer_emitted", true)
                put("failed_event_code_present", true)
                put("failed_event_invocation_present", false)
                put("failed_event_details_present", true)
            }
        )
        val captured = requireNotNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", source, 1_800_000_000_000))
        val encoded = IssueReport.json.encodeToString(IssueReportDiagnostics.serializer(), captured)
        assertEquals(3, captured.schemaVersion)
        assertEquals(JsonPrimitive(3600), captured.values["task_deadline_window_seconds"])
        assertEquals(JsonPrimitive(3646), captured.values["task_deadline_elapsed_seconds"])
        assertEquals(JsonPrimitive(0), captured.values["task_deadline_remaining_seconds"])
        assertEquals(JsonPrimitive(false), captured.values["task_deadline_renewed"])
        assertEquals(JsonPrimitive("EXPLICIT_USER_REQUEST"), captured.values["task_deadline_source"])
        val retained = (captured.values.getValue("unmet_criterion_states") as JsonArray).size
        assertTrue(retained in 1..7)
        for (key in listOf("unmet_criterion_id_hashes", "unmet_criterion_reason_hashes", "unmet_criterion_categories", "unmet_criterion_states", "unmet_criterion_blockers")) {
            assertEquals(key, retained, (captured.values.getValue(key) as JsonArray).size)
        }
        assertEquals(JsonPrimitive(70 - retained), captured.values["unmet_criterion_omitted_count"])
        assertEquals(captured, IssueReportDiagnostics.fromJson(IssueReport.json.parseToJsonElement(encoded)))
        assertTrue(encoded.toByteArray().size <= 3072)
        assertEquals(JsonPrimitive(70), captured.values["unmet_criterion_count"])
        assertFalse(encoded.contains("private-"))
        val totals = listOf("acceptance_approval_blocked_total", "acceptance_input_blocked_total", "acceptance_live_blocked_total", "acceptance_continuation_blocked_total")
        for ((key, expected) in totals.zip(listOf(18, 18, 17, 17))) {
            assertEquals(JsonPrimitive(expected), fields[key])
            assertEquals(key, fields[key], captured.values[key])
            assertFalse(key in captured.omittedFields)
        }
        val missing = requireNotNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", source.filterKeys { it !in totals }, 1_800_000_000_000))
        assertTrue(totals.none { it in missing.values || it in missing.omittedFields })
    }
}
