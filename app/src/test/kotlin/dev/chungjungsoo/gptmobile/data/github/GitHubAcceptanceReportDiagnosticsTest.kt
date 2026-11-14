package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportDiagnostics
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubAcceptanceReportDiagnosticsTest {
    @Test fun retainedCheckpointsPreserveRecordedCoverageAndPaginationWithoutChangingAcceptance() = runTest {
        val cases = listOf(
            Triple("Review the selected branch against its base.", "OBSERVED_CAPABILITIES", "BOUNDED_SMOKE_SAMPLE"),
            Triple("Test all authorized GitHub integration tools", "FULL_CATALOG", "BOUNDED_SMOKE_SAMPLE"),
            Triple("Test all authorized GitHub integration tools and complete pending pagination", "FULL_CATALOG", "COMPLETE_INVENTORY_REQUESTED")
        )
        for ((task, scope, pagination) in cases) {
            val saved = linkedMapOf<String, JsonObject>()
            val schema = Json.parseToJsonElement("""{"properties":{"action":{"const":"branches"},"page":{"type":"integer"}}}""") as JsonObject
            val tools = listOf(AgentToolDefinition("github_context", "Branches", schema))
            val book = GitHubEvidenceBook("binding", "private-owner/private-repo", "main", setOf("github_context"), load = { saved[it] }, save = { id, value -> saved[id] = value })
            val args = Json.parseToJsonElement("""{"action":"branches","page":1}""") as JsonObject
            val result = Json.parseToJsonElement("""{"local_page_complete":true,"continuation_call":{"tool":"github_context","arguments":{"action":"branches","page":2}}}""") as JsonObject
            val call = ProviderEvent.ToolCall("source", "github_context", args)
            book.observe(call, AgentToolResult(call.callId, ToolResultContent.Json(result), false))
            val (receipts, epoch) = book.snapshot()
            val checkpoint = GitHubTestChecklist.checkpoint(task, receipts, tools, remainingToolCalls = 50)
            val ledger = checkpoint.getValue("acceptance_ledger") as JsonObject
            val verdict = buildJsonObject {
                put("acceptance_ledger", ledger)
                put("completion_verified", ledger.getValue("completion_verified"))
                put("test_checklist", checkpoint)
                put("stop_reason", GitHubIntegrationReport.stopReason(task, receipts, tools, checklist = checkpoint))
            }
            assertEquals(JsonPrimitive(false), verdict["completion_verified"])
            assertTrue(book.retainAnswerIfCurrent("Saved private task report", verdict, epoch, receipts.keys))
            val restored = GitHubEvidenceBook("binding", "private-owner/private-repo", "main", setOf("github_context"), load = { saved[it] }, retainedIds = { saved.keys.filter { it.startsWith("ev_") } })
            restored.restore()
            val retained = requireNotNull(restored.retainedAnswer()).second
            assertEquals(verdict, retained)
            val details = GitHubTaskLifecycle.acceptanceFailureDetails(retained, retainedReport = true)
            val diagnostic = requireNotNull(IssueReportContext.fromFields("CHAT_FAILURE", details, 1_800_000_000_000).diagnostics)
            assertEquals(JsonPrimitive(scope), diagnostic.values["acceptance_capability_scope"])
            assertEquals(JsonPrimitive(pagination), diagnostic.values["acceptance_pagination_mode"])
            assertEquals(JsonPrimitive("RETAINED_VERDICT"), diagnostic.values["acceptance_evidence_scope"])
            assertEquals(JsonPrimitive(1), diagnostic.values["pending_remote_continuations"])
            assertEquals(JsonPrimitive(1), diagnostic.values["acceptance_pending_remote_continuations"])
            assertEquals(verdict, retained)
            assertFalse(diagnostic.toString().contains("private"))
        }
    }

    @Test fun missingChecklistScopeIsNotInferredFromTaskKindOrTopLevelMetadata() {
        for (taskKind in listOf("GENERAL", "INTEGRATION_TEST")) {
            val fields = GitHubAcceptanceReportDiagnostics.fields(
                buildJsonObject {
                    put("acceptance_ledger", buildJsonObject {})
                    put("task_kind", taskKind)
                    put("coverage_scope", "FULL_CATALOG")
                    put("pagination_resume", buildJsonObject { put("mode", "COMPLETE_INVENTORY_REQUESTED") })
                    put("test_checklist", buildJsonObject {})
                },
                retainedReport = true
            )
            assertEquals(JsonPrimitive(taskKind), fields["task_kind"])
            assertNull(fields["acceptance_capability_scope"])
            assertNull(fields["acceptance_pagination_mode"])
        }
    }

    @Test fun malformedAndUnknownChecklistScopesStayAbsent() {
        for (value in listOf("null", "true", "17", "[]", "{}", "\"private-scope\"")) {
            val invalid = Json.parseToJsonElement(value)
            val fields = GitHubAcceptanceReportDiagnostics.fields(
                buildJsonObject {
                    put("acceptance_ledger", buildJsonObject {})
                    put(
                        "test_checklist",
                        buildJsonObject {
                            put("coverage_scope", invalid)
                            put("pagination_resume", buildJsonObject { put("mode", invalid) })
                        }
                    )
                },
                retainedReport = true
            )
            assertNull(fields["acceptance_capability_scope"])
            assertNull(fields["acceptance_pagination_mode"])
            assertFalse(fields.toString().contains("private-scope"))
        }
    }

    @Test fun unsupportedClaimReportExplainsMissingEvidenceWithoutExportingClaimText() {
        val fields = GitHubAcceptanceReportDiagnostics.fields(
            buildJsonObject {
                put("acceptance_ledger", buildJsonObject {})
                put(
                    "unresolved_claims",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("reason", "POINTER_NOT_IN_EVIDENCE")
                                put("value", "private-claim")
                            }
                        )
                    )
                )
            },
            false
        )
        val diagnostic = requireNotNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", fields, 1_800_000_000_000))
        assertEquals(JsonPrimitive(1), diagnostic.values["acceptance_unresolved_claim_count"])
        assertEquals(JsonArray(listOf(JsonPrimitive("POINTER_NOT_IN_EVIDENCE"))), diagnostic.values["acceptance_claim_failure_reasons"])
        assertFalse(diagnostic.toString().contains("private-claim"))
    }

    @Test fun deliveredVerdictPublishesExactUnmetCriterionAndRetainedBudgetWithoutClaimingDispatch() {
        val ledger = GitHubAcceptanceLedger.snapshot(
            JsonArray(
                listOf(
                    requirement("private-device-criterion", "Device execution on private handset", "NOT_TESTED", true),
                    requirement("excluded", "Other requirement", "NOT_TESTED", false),
                    requirement("passed", "Already verified", "PASS", true)
                )
            )
        )
        val verdict = buildJsonObject {
            put("acceptance_ledger", ledger)
            put(
                "original_failure",
                buildJsonObject {
                    put("failed_stage", "answer_review")
                    put("dispatched", true)
                }
            )
            put(
                "test_checklist",
                buildJsonObject {
                    put(
                        "stage_budget",
                        buildJsonObject {
                            put("effective_call_limit", 100)
                            put("used_calls", 40)
                            put("remaining_calls", 60)
                            put("verification_reserve", 6)
                        }
                    )
                }
            )
            put("execution_ledger", buildJsonObject { put("unresolved_remote_outcomes", 1) })
            put("stop_reason", buildJsonObject { put("pending_continuations", 2) })
        }
        val details = GitHubTaskLifecycle.acceptanceFailureDetails(verdict, retainedReport = true)
        val diagnostic = requireNotNull(IssueReportContext.fromFields("CHAT_FAILURE", details, 1_000_000).diagnostics)
        val values = diagnostic.values
        assertEquals("ACCEPTANCE", diagnostic.area)
        assertEquals(JsonPrimitive("RETAINED_VERDICT"), values["acceptance_evidence_scope"])
        assertEquals(JsonPrimitive(1), values["unmet_criterion_count"])
        assertEquals(JsonArray(listOf(JsonPrimitive("DEVICE_EXECUTION"))), values["unmet_criterion_categories"])
        assertEquals(JsonArray(listOf(JsonPrimitive("NOT_TESTED"))), values["unmet_criterion_states"])
        assertEquals(JsonPrimitive(100), values["effective_call_limit"])
        assertEquals(JsonPrimitive(40), values["used_calls"])
        assertEquals(JsonPrimitive(60), values["remaining_calls"])
        assertEquals(JsonPrimitive(6), values["verification_reserve"])
        assertEquals(JsonPrimitive(2), values["pending_continuations"])
        assertEquals(JsonPrimitive(1), values["unresolved_outcomes"])
        assertTrue(values["checkpoint_hash"] is JsonPrimitive)
        assertFalse("dispatched" in details)
        assertEquals(JsonPrimitive("RECONCILE_REMOTE_OUTCOME"), details["recovery_action"])
        assertFalse(Json.encodeToString(IssueReportDiagnostics.serializer(), diagnostic).contains("private"))
    }

    @Test fun boundedSelectionDeclaresOmittedCriteriaAndNeverInventsMissingBudget() {
        val ledger = GitHubAcceptanceLedger.snapshot(JsonArray((1..12).map { requirement("criterion-$it", "Device test $it", "NOT_TESTED", true) }))
        val details = GitHubTaskLifecycle.acceptanceFailureDetails(buildJsonObject { put("acceptance_ledger", ledger) })
        assertEquals(JsonPrimitive(12), details["unmet_criterion_count"])
        assertEquals(JsonPrimitive(4), details["unmet_criterion_omitted_count"])
        assertEquals(8, (details["unmet_criterion_id_hashes"] as JsonArray).size)
        assertNull(details["effective_call_limit"])
        assertNull(details["remaining_calls"])
        assertNull(details["unresolved_outcomes"])
        val missing = GitHubTaskLifecycle.acceptanceFailureDetails(JsonObject(emptyMap()))
        assertNull(missing["diagnostic_area"])
        assertNull(missing["unmet_criterion_count"])
    }

    @Test fun differentUnmetCriteriaKeepTheirDistinctReceiptExclusions() {
        fun row(reason: String) = buildJsonObject {
            put("required", true)
            put("state", "BLOCKED")
            put("receipt_exclusion_reasons", JsonArray(listOf(JsonPrimitive(reason))))
        }
        val verdict = buildJsonObject {
            put(
                "acceptance_ledger",
                buildJsonObject {
                    put("requirements", JsonArray(listOf(row("WRONG_REVISION"), row("PRECHECK_ONLY"), row("PRECHECK_ONLY"), row("private-reason"))))
                }
            )
        }
        val fields = GitHubAcceptanceReportDiagnostics.fields(verdict, retainedReport = true)
        assertEquals(JsonArray(listOf(JsonPrimitive("WRONG_REVISION"), JsonPrimitive("PRECHECK_ONLY"))), fields["receipt_exclusion_reasons"])
        assertFalse(fields.toString().contains("private-reason"))
    }

    private fun requirement(id: String, text: String, state: String, required: Boolean) = buildJsonObject {
        put("id", id)
        put("requirement", text)
        put("acceptance_state", state)
        put("required", required)
        put("acceptance", "private missing evidence for $id")
    }

    @Test fun blockerTotalsIncludeCriteriaOutsideTheCompactSample() {
        val rows = (1..12).map { index ->
            buildJsonObject {
                put("id", "criterion-$index")
                put("required", true)
                put("state", "BLOCKED")
                put("blocker_code", if (index <= 9) "FIXTURE_APPROVAL_MODE_REQUIRED" else "REPOSITORY_INPUT_REQUIRED")
            }
        }
        val fields = GitHubAcceptanceReportDiagnostics.fields(
            buildJsonObject {
                put("acceptance_ledger", buildJsonObject { put("requirements", JsonArray(rows)) })
                put("stop_reason", buildJsonObject { put("pending_remote_continuations", 14) })
            },
            retainedReport = true
        )
        assertEquals(JsonPrimitive(9), fields["acceptance_approval_blocked_total"])
        assertEquals(JsonPrimitive(3), fields["acceptance_input_blocked_total"])
        assertEquals(JsonPrimitive(4), fields["unmet_criterion_omitted_count"])
        assertEquals(JsonPrimitive(14), fields["acceptance_pending_remote_continuations"])
        val blockers = fields["unmet_criterion_blockers"] as JsonArray
        assertTrue(JsonPrimitive("REPOSITORY_INPUT_REQUIRED") in blockers)
    }

    @Test fun ambiguousProtectionObservationRequestsPermissionCheckWithoutClaimingNoPolicy() {
        val verdict = Json.parseToJsonElement("""{"acceptance_ledger":{},"status_coverage":[{"item":"Required-check policy","branch_protection_observation":"NOT_FOUND_OR_INACCESSIBLE","branch_rules_observation":"OBSERVED"}]}""") as JsonObject
        val fields = GitHubAcceptanceReportDiagnostics.fields(verdict, true)
        assertEquals(JsonPrimitive("CHECK_PROTECTION_READ_PERMISSION"), fields["status_policy_recovery"])
        assertEquals(JsonPrimitive("NOT_FOUND_OR_INACCESSIBLE"), fields["branch_protection_observation"])
    }
}
