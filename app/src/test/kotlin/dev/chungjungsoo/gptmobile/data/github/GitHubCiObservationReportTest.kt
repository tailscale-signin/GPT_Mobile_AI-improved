package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCiObservationReportTest {
    private val head = "a".repeat(40)
    private val task = GitHubWorkflowPreset.selected("diagnose_ci").prompt()
    private val now = 10_000L

    private fun run(id: Int, conclusion: String = "success", status: String = "completed", attempt: Int = 1, sha: String = head) = buildJsonObject {
        put("id", id)
        put("run_attempt", attempt)
        put("head_sha", sha)
        put("status", status)
        put("conclusion", conclusion)
    }

    private fun receipt(id: String, action: String, data: JsonObject, sourceHead: String? = null, headObservation: Boolean = false) = buildJsonObject {
        put("receipt_id", id)
        put("tool", if (headObservation) "github_context" else "github_ci")
        put("arguments", buildJsonObject { put("action", action) })
        put("trusted", true)
        put("success", true)
        put("verification_eligible", true)
        put("observed_at_ms", now - 10)
        put("epoch", 2L)
        put("source_head_sha", sourceHead?.let(::JsonPrimitive) ?: JsonNull)
        put("head_observation", headObservation)
        put("result", buildJsonObject { put("data", data) })
    }

    private fun evidence(runs: List<JsonObject>, returned: Int = runs.size, more: Boolean = false) = linkedMapOf(
        "head" to receipt("head", "status", buildJsonObject { put("commit_sha", head) }, head, true),
        "runs" to receipt(
            "runs",
            "runs",
            buildJsonObject {
                put("workflow_runs", JsonArray(runs))
                put("returned", returned)
                put("has_more", more)
            }
        )
    )

    @Test fun sevenSuccessfulAndElevenSkippedRunsFinishOnlyTheBoundedDiagnosis() {
        val receipts = evidence((1..18).map { run(it, if (it <= 7) "success" else "skipped") })
        val (text, report) = requireNotNull(GitHubCiObservationReport.render(task, receipts, now, 2))
        assertTrue(text.contains("No failed runs were observed in the returned set"))
        assertEquals(JsonPrimitive(18), report["returned_run_count"])
        assertEquals(JsonPrimitive(7), report["successful_run_count"])
        assertEquals(JsonPrimitive(11), report["skipped_run_count"])
        assertEquals(JsonPrimitive(true), report["completion_verified"])
        assertEquals(JsonPrimitive(false), report["new_test_execution_verified"])
        assertEquals(JsonPrimitive(false), report["build_health_verified"])
        assertEquals(JsonPrimitive(false), report["full_history_verified"])
        val requirements = report["requirements"] as JsonArray
        assertEquals(4, requirements.size)
        assertEquals(3, requirements.filterIsInstance<JsonObject>().count { it["status"] == JsonPrimitive("NOT_APPLICABLE") && it["required"] == JsonPrimitive(false) })
        val ledger = GitHubAcceptanceLedger.snapshot(requirements)
        assertEquals(JsonPrimitive(true), ledger["completion_verified"])
    }

    @Test fun pendingFailedAndUnsuccessfulRunsRequireInvestigation() {
        for (outcome in listOf("failure", "cancelled", "timed_out", "action_required", "unknown")) {
            assertNull(GitHubCiObservationReport.render(task, evidence(listOf(run(1, outcome))), now, 2))
        }
        assertNull(GitHubCiObservationReport.render(task, evidence(listOf(run(1, "success", "in_progress"))), now, 2))
    }

    @Test fun emptyTruncatedUntrustedAndWrongRevisionResultsCannotProveAbsence() {
        assertNull(GitHubCiObservationReport.render(task, evidence(emptyList()), now, 2))
        assertNull(GitHubCiObservationReport.render(task, evidence(listOf(run(1)), returned = 18), now, 2))
        assertNull(GitHubCiObservationReport.render(task, evidence(listOf(run(1, sha = "b".repeat(40)))), now, 2))
        val untrusted = evidence(listOf(run(1))).apply { this["runs"] = JsonObject(getValue("runs") + ("verification_eligible" to JsonPrimitive(false))) }
        assertNull(GitHubCiObservationReport.render(task, untrusted, now, 2))
        assertNull(GitHubCiObservationReport.render(task, evidence(listOf(run(1))), now, 3))
    }

    @Test fun aReturnedPageCanBeReportedWithoutClaimingUnreturnedHistory() {
        val (text, report) = requireNotNull(GitHubCiObservationReport.render(task, evidence(listOf(run(1)), more = true), now, 2))
        assertEquals(JsonPrimitive(false), report["full_history_verified"])
        assertTrue(text.contains("older or unreturned attempts"))
    }

    @Test fun aNewerAttemptOrSeparateFailedCheckPreventsTheNoFailureTerminal() {
        val newer = evidence(listOf(run(1), run(1, "failure", attempt = 2)))
        assertNull(GitHubCiObservationReport.render(task, newer, now, 2))
        val checks = evidence(listOf(run(1))).apply {
            put("checks", receipt("checks", "status", buildJsonObject { put("check_runs", JsonArray(listOf(run(2, "failure")))) }, head))
        }
        assertNull(GitHubCiObservationReport.render(task, checks, now, 2))
    }

    @Test fun aDiagnosisCannotSilentlySatisfyExtraChangesOrAnExhaustiveHistoryRequest() {
        val receipts = evidence(listOf(run(1)))
        assertNotNull(GitHubCiObservationReport.render("Diagnose CI failures", receipts, now, 2))
        assertFalse(GitHubCiObservationReport.matches(task + "\nFix the build and run new tests."))
        assertFalse(GitHubCiObservationReport.matches("Inspect all CI history for failures"))
        assertFalse(GitHubCiObservationReport.matches("Diagnose CI failures for run 20 attempt 2"))
    }

    @Test fun pendingLegacyStatusOrUnknownAggregatePreventsCompletionEvenWithNoCheckRuns() {
        for ((aggregate, legacyState) in listOf("RUNNING" to "pending", "PASSED" to "pending", "FAILED" to "error", "UNKNOWN" to "success")) {
            val receipts = evidence(listOf(run(1))).apply {
                put(
                    "status",
                    receipt(
                        "status",
                        "status",
                        buildJsonObject {
                            put("state", aggregate)
                            put(
                                "check_details",
                                buildJsonObject {
                                    put("check_runs", JsonArray(emptyList()))
                                    put("total_count", 0)
                                }
                            )
                            put(
                                "status_details",
                                buildJsonObject {
                                    put("state", legacyState)
                                    put("total_count", 1)
                                }
                            )
                        },
                        head
                    )
                )
            }
            assertNull("$aggregate / $legacyState", GitHubCiObservationReport.render(task, receipts, now, 2))
        }
    }
}
