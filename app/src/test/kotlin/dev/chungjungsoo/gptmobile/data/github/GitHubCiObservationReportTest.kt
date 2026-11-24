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

    @Test fun capturedCheckSnapshotAndPendingJobSurviveIncompleteHistoryAndNoModelClaims() {
        val receipts = evidence(listOf(run(1)), returned = 12)
        val active = run(17, "", "in_progress")
        receipts["attempt"] = JsonObject(
            receipt("attempt", "run", active, head) +
                (
                    "arguments" to buildJsonObject {
                        put("action", "run")
                        put("run_id", 17)
                    }
                    )
        )
        receipts["logs"] = JsonObject(
            receipt(
                "logs",
                "logs",
                buildJsonObject {
                    put("status", "PENDING_LOG_AVAILABILITY")
                    put("job_id", 26)
                    put("run_id", 17)
                    put("head_sha", head)
                    put("job_status", "in_progress")
                    put("logs_available", false)
                },
                head
            ) + (
                "arguments" to buildJsonObject {
                    put("action", "logs")
                    put("job_id", 26)
                    put("run_attempt", 1)
                }
                )
        )
        val rows = (1..43).map { id ->
            run(
                id,
                if (id <= 24) {
                    "success"
                } else if (id <= 42) {
                    "skipped"
                } else {
                    ""
                },
                if (id == 43) "in_progress" else "completed"
            )
        }
        val raw = buildJsonObject {
            put("commit_sha", head)
            put("state", "RUNNING")
            put(
                "check_details",
                buildJsonObject {
                    put("total_count", 43)
                    put("check_runs", JsonArray(rows))
                }
            )
            put(
                "status_details",
                buildJsonObject {
                    put("total_count", 0)
                    put("statuses", JsonArray(emptyList()))
                }
            )
        }
        val args = buildJsonObject {
            put("action", "status")
            put("commit_sha", head)
        }
        val check = receipt("snapshot", "status", buildJsonObject { put("observationTruncated", true) }, head)
        receipts["snapshot"] = JsonObject(check + ("semantic_summary" to GitHubSemanticSummary.from("github_ci", args, raw)))
        val (text, report) = requireNotNull(GitHubCiObservationReport.render(task, receipts, now, 2))
        assertTrue(text.contains("24 succeeded, 18 were skipped and 1 was still running"))
        assertTrue(text.contains("Logs for job `26` are pending"))
        assertEquals(JsonPrimitive(false), report["completion_verified"])
        assertEquals(JsonPrimitive("WAITING_FOR_CI"), report["observation_state"])
        assertEquals(JsonPrimitive(1), report["run_attempt"])
        val ids = report["evidence_receipt_ids"] as JsonArray
        assertTrue(listOf("attempt", "snapshot", "logs").all { JsonPrimitive(it) in ids })
        receipts["logs"] = JsonObject(receipts.getValue("logs") + ("trusted" to JsonPrimitive(false)))
        val withoutLogs = requireNotNull(GitHubCiObservationReport.render(task, receipts, now, 2))
        assertEquals(JsonPrimitive("WAITING_FOR_CI"), withoutLogs.second["observation_state"])
        assertFalse(withoutLogs.first.contains("Logs for job"))
        assertFalse(withoutLogs.second.containsKey("logs_available"))
        val (partialText, partial) = requireNotNull(GitHubCiObservationReport.render(task, receipts, now, 2, checkSnapshotOnly = true))
        assertTrue(partialText.contains("24"))
        assertEquals(JsonPrimitive("CHECK_SNAPSHOT_ONLY"), partial["observation_state"])
        assertEquals(JsonPrimitive(false), partial["completion_verified"])
        assertEquals(JsonArray(listOf(JsonPrimitive("snapshot"))), partial["evidence_receipt_ids"])
    }

    private fun runningSnapshot(operation: String = "github_context_inspect", sha: String = head): JsonObject {
        val args = buildJsonObject {
            put("action", if (operation == "github_ci") "status" else "checks")
            put("commit_sha", sha)
        }
        val raw = buildJsonObject {
            put("commit_sha", sha)
            put("state", "RUNNING")
            put(
                "check_details",
                buildJsonObject {
                    put("total_count", 3)
                    put("check_runs", JsonArray(listOf(run(1, "skipped", sha = sha), run(2, "", "in_progress", sha = sha), run(3, sha = sha))))
                }
            )
            put(
                "status_details",
                buildJsonObject {
                    put("total_count", 0)
                    put("statuses", JsonArray(emptyList()))
                }
            )
        }
        return JsonObject(
            receipt("snapshot", "checks", buildJsonObject { put("observationTruncated", true) }, sha) + mapOf(
                "tool" to JsonPrimitive(operation),
                "arguments" to args,
                "semantic_summary" to GitHubSemanticSummary.from(operation, args, raw)
            )
        )
    }

    @Test fun exactRevisionRunningChecksDoNotRequireAnAbsentLogProbe() {
        val receipts = evidence(listOf(run(17, "skipped"), run(18, "", "queued", attempt = 2), run(19)))
        receipts["snapshot"] = runningSnapshot()
        receipts["failure"] = receipt(
            "failure",
            "failure",
            buildJsonObject {
                put("status", "no_failed_run")
                put("head_sha", head)
                put("commit_checks_state", "RUNNING")
            },
            head
        )
        val (text, report) = requireNotNull(GitHubCiObservationReport.render(task, receipts, now, 2))
        assertEquals(JsonPrimitive("WAITING_FOR_CI"), report["observation_state"])
        assertEquals(JsonPrimitive(18), report["run_id"])
        assertEquals(JsonPrimitive(2), report["run_attempt"])
        assertEquals(JsonPrimitive(false), report["completion_verified"])
        assertEquals(JsonPrimitive(false), report["build_health_verified"])
        assertEquals(JsonPrimitive(false), report["new_test_execution_verified"])
        assertFalse(report.containsKey("pending_job_id"))
        assertFalse(report.containsKey("logs_available"))
        assertTrue(text.contains("workflow outcome remains open"))
        assertFalse(text.contains("Logs for job"))
        assertEquals(3, (report["remaining_requirements"] as JsonArray).size)
    }

    @Test fun runningSnapshotCanRemainPendingWithoutInventingAWorkflowIdentifier() {
        val receipts = evidence(emptyList()).apply { put("snapshot", runningSnapshot()) }
        val report = requireNotNull(GitHubCiObservationReport.render(task, receipts, now, 2)).second
        assertEquals(JsonPrimitive("WAITING_FOR_CI"), report["observation_state"])
        assertFalse(report.containsKey("run_id"))
        assertFalse(report.containsKey("watch_tool"))
        assertTrue((report["run_lookup_tool"] as? JsonPrimitive)?.content?.startsWith("github_ci") == true)
        assertEquals(4, (report["remaining_requirements"] as JsonArray).size)
        val simple = requireNotNull(GitHubCiObservationReport.render("Diagnose CI failures", receipts, now, 2)).second
        assertEquals(1, (simple["remaining_requirements"] as JsonArray).size)
    }

    @Test fun wrongRevisionStaleIncompleteAndFailedObservationsNeverBecomePendingSuccess() {
        val base = evidence(listOf(run(17, "", "in_progress")))
        for (snapshot in listOf(
            runningSnapshot(sha = "b".repeat(40)),
            JsonObject(runningSnapshot() + ("trusted" to JsonPrimitive(false))),
            JsonObject(runningSnapshot() + ("epoch" to JsonPrimitive(1))),
            JsonObject(runningSnapshot() + ("semantic_summary" to JsonObject((runningSnapshot().getValue("semantic_summary") as JsonObject) + ("check_inventory_complete" to JsonPrimitive(false)))))
        )) {
            assertNull(GitHubCiObservationReport.render(task, base + ("snapshot" to snapshot), now, 2))
        }
        assertNull(GitHubCiObservationReport.render(task, evidence(listOf(run(17, "failure"))) + ("snapshot" to runningSnapshot()), now, 2))
    }

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

    @Test fun anExactCompleteEmptyFailureQueryFinishesTheBoundedDiagnosisWithoutInventingPassingTests() {
        val receipts = evidence(emptyList())
        receipts["runs"] = JsonObject(
            receipt(
                "runs",
                "runs",
                buildJsonObject {
                    put("workflow_runs", JsonArray(emptyList()))
                    put("returned", 0)
                    put("total_count", 0)
                    put("page", 1)
                    put("has_more", false)
                    put("traversal_complete", true)
                },
                head
            ) + (
                "arguments" to buildJsonObject {
                    put("action", "runs")
                    put("head_sha", head)
                    put("status", "failure")
                }
                )
        )
        val (text, report) = requireNotNull(GitHubCiObservationReport.render(task, receipts, now, 2))
        assertTrue(text, text.contains("complete failure query returned no failed workflow runs"))
        assertEquals(JsonPrimitive(true), report["completion_verified"])
        assertEquals(JsonPrimitive(true), report["failure_query_complete"])
        assertEquals(JsonPrimitive(false), report["build_health_verified"])
        assertEquals(JsonPrimitive(false), report["new_test_execution_verified"])
        assertFalse(report.containsKey("successful_run_count"))
        assertFalse(report.containsKey("skipped_run_count"))
        assertNull(GitHubCiObservationReport.render(task, receipts, now, 3))
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
