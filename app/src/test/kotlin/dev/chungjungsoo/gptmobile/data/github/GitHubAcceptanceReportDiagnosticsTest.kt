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
        data class Case(val task: String, val scope: String, val pagination: String, val completionVerified: Boolean)
        val cases = listOf(
            Case("Review the selected branch against its base.", "OBSERVED_CAPABILITIES", "BOUNDED_SMOKE_SAMPLE", false),
            Case("Test all authorized GitHub integration tools", "FULL_CATALOG", "BOUNDED_SMOKE_SAMPLE", true),
            Case("Test all authorized GitHub integration tools and complete pending pagination", "FULL_CATALOG", "COMPLETE_INVENTORY_REQUESTED", false)
        )
        for ((task, scope, pagination, completionVerified) in cases) {
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
            // The single authorized action satisfies bounded integration coverage;
            // a review or explicitly exhaustive traversal still needs its own proof.
            assertEquals(task, JsonPrimitive(completionVerified), verdict["completion_verified"])
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

    @Test fun retainedFreshnessPredicatesKeepVerificationTimeAfterRestore() = runTest {
        val saved = linkedMapOf<String, JsonObject>()
        var now = 1_000L
        fun book() = GitHubEvidenceBook(
            "private-binding", "private-owner/private-repo", "main", setOf("github_ci"), { now }, { saved[it] }, { id, value -> saved[id] = value },
            retainedIds = { saved.keys.filter { it.startsWith("ev_") } },
            latestVerdict = { saved.entries.singleOrNull { it.key.startsWith("verification_current_") }?.value }
        )
        val original = book()
        for ((callId, time, count) in listOf(Triple("private-old", 1_000L, 3), Triple("private-future", 201_000L, 4))) {
            now = time
            val call = ProviderEvent.ToolCall(callId, "github_ci", buildJsonObject { put("action", "status") })
            original.observe(call, AgentToolResult(callId, ToolResultContent.Json(buildJsonObject { put("count", count) }), false))
        }
        now = 200_000L
        val (receipts, epoch) = original.snapshot()
        val answer = buildJsonObject {
            put(
                "claims",
                JsonArray(
                    receipts.map { (id, receipt) ->
                        buildJsonObject {
                            put("kind", "fact")
                            put("receipt_id", id)
                            put("pointer", "/count")
                            put("value", (receipt.getValue("result") as JsonObject).getValue("count"))
                        }
                    }
                )
            )
        }
        val report = GitHubEvidencePolicy.render(answer, receipts, now, epoch).second
        val retained = original.retainVerdict(report)
        val expected = JsonArray(listOf(JsonPrimitive("MUTABLE_AGE_EXCEEDED"), JsonPrimitive("OBSERVATION_TIME_FUTURE")))
        assertEquals(expected, GitHubAcceptanceReportDiagnostics.fields(retained, true)["acceptance_claim_freshness_predicates"])
        now = 900_000L
        val restored = book()
        restored.restore()
        val checkpoint = requireNotNull(restored.diagnostic())
        assertEquals(retained["claims"], checkpoint["claims"])
        val fields = GitHubAcceptanceReportDiagnostics.fields(checkpoint, true)
        assertEquals(expected, fields["acceptance_claim_freshness_predicates"])
        assertEquals(JsonPrimitive("RETAINED_VERDICT"), fields["acceptance_evidence_scope"])
        assertFalse(fields.toString().contains("private"))
        assertFalse(fields.toString().contains("observed_at_ms"))
        // A new verification would classify the formerly future receipt as old;
        // reporting the retained verdict must preserve its original observation.
        val (current, currentEpoch) = restored.snapshot()
        val rechecked = GitHubEvidencePolicy.render(answer, current, now, currentEpoch).second
        for (claim in rechecked.getValue("claims") as JsonArray) {
            assertEquals(JsonArray(listOf(JsonPrimitive("MUTABLE_AGE_EXCEEDED"))), (claim as JsonObject)["freshness_predicates"])
        }
    }

    @Test fun oldAndIncompleteFreshnessAnnotationsStayUnavailableWhileObservedEmptyIsPreserved() {
        val fresh = checkedFreshnessClaim(200_000, 7)
        val known = checkedFreshnessClaim(200_001, 7)
        val old = JsonObject(checkedFreshnessClaim(0, 7) - "freshness_predicates")
        assertEquals(JsonArray(emptyList()), GitHubAcceptanceReportDiagnostics.fields(freshnessVerdict(fresh), true)["acceptance_claim_freshness_predicates"])
        for (verdict in listOf(freshnessVerdict(old), freshnessVerdict(known, old), freshnessVerdict(JsonObject(fresh - "freshness_predicates")))) {
            assertNull(GitHubAcceptanceReportDiagnostics.fields(verdict, true)["acceptance_claim_freshness_predicates"])
        }
        val unmeasured = buildJsonObject {
            put("status", "NOT_MEASURED")
            put("reason", "RECEIPT_NOT_FOUND")
            put("observed_at_ms", 0)
        }
        assertNull(GitHubAcceptanceReportDiagnostics.fields(freshnessVerdict(unmeasured), true)["acceptance_claim_freshness_predicates"])
    }

    @Test fun malformedFreshnessAnnotationsAreNotPartiallyReportedOrInferred() {
        val checked = checkedFreshnessClaim(0, 7)
        val invalid = listOf(
            "null", "true", "17", "{}", "\"OBSERVATION_TIME_UNAVAILABLE\"",
            "[true]", "[\"PRIVATE_receipt_value\"]",
            "[\"OBSERVATION_TIME_UNAVAILABLE\",\"PRIVATE_receipt_value\"]",
            "[\"OBSERVATION_TIME_UNAVAILABLE\",\"OBSERVATION_TIME_UNAVAILABLE\"]",
            "[\"OBSERVATION_TIME_UNAVAILABLE\",\"OBSERVATION_TIME_FUTURE\",\"MUTABLE_AGE_EXCEEDED\",\"MUTABLE_EPOCH_MISMATCH\",\"PRIVATE_extra\"]"
        )
        for (raw in invalid) {
            val claim = JsonObject(checked + ("freshness_predicates" to Json.parseToJsonElement(raw)))
            val fields = GitHubAcceptanceReportDiagnostics.fields(freshnessVerdict(claim), true)
            assertNull(raw, fields["acceptance_claim_freshness_predicates"])
            assertFalse(fields.toString().contains("PRIVATE"))
        }
    }

    @Test fun incompleteCheckedClaimsCannotBeHiddenByAnotherObservedEmptyClaim() {
        val fresh = checkedFreshnessClaim(200_000, 7)
        val incomplete = buildJsonObject { put("status", "STALE") }
        assertNull(GitHubAcceptanceReportDiagnostics.fields(freshnessVerdict(fresh, incomplete), true)["acceptance_claim_freshness_predicates"])
        val calculation = buildJsonObject {
            put("status", "VERIFIED")
            put("kind", "calculation")
        }
        assertNull(GitHubAcceptanceReportDiagnostics.fields(freshnessVerdict(fresh, calculation), true)["acceptance_claim_freshness_predicates"])
        val stale = checkedFreshnessClaim(0, 7)
        for (key in listOf("status", "reason")) {
            for (raw in listOf(null, "null", "true", "17", "[]", "{}", "\"PRIVATE_unknown_value\"")) {
                val claim = JsonObject(stale - key + raw?.let { mapOf(key to Json.parseToJsonElement(it)) }.orEmpty())
                val fields = GitHubAcceptanceReportDiagnostics.fields(freshnessVerdict(fresh, claim), true)
                assertNull("$key=$raw", fields["acceptance_claim_freshness_predicates"])
                assertFalse(fields.toString().contains("PRIVATE"))
            }
        }
        val notMeasured = buildJsonObject { put("status", "NOT_MEASURED") }
        val inferred = buildJsonObject { put("status", "INFERRED") }
        val fields = GitHubAcceptanceReportDiagnostics.fields(freshnessVerdict(fresh, notMeasured, inferred), true)
        assertEquals(JsonArray(emptyList()), fields["acceptance_claim_freshness_predicates"])
    }

    @Test fun publicFreshnessDiagnosticsRetainOnlyFourClosedPredicatesWithoutPrivateReceiptData() {
        val verdict = freshnessVerdict(checkedFreshnessClaim(0, 7), checkedFreshnessClaim(200_001, 7), checkedFreshnessClaim(1_000, 6), checkedFreshnessClaim(1_000, 6))
        val fields = GitHubAcceptanceReportDiagnostics.fields(verdict, true)
        val expected = JsonArray(listOf("OBSERVATION_TIME_UNAVAILABLE", "OBSERVATION_TIME_FUTURE", "MUTABLE_AGE_EXCEEDED", "MUTABLE_EPOCH_MISMATCH").map(::JsonPrimitive))
        assertEquals(expected, fields["acceptance_claim_freshness_predicates"])
        val diagnostic = requireNotNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", fields, 1_800_000_000_000))
        assertEquals(expected, diagnostic.values["acceptance_claim_freshness_predicates"])
        assertFalse(diagnostic.toString().contains("private"))
        assertFalse(diagnostic.toString().contains("observed_at_ms"))
        assertFalse(diagnostic.toString().contains("receipt_id"))
        assertFalse(diagnostic.toString().contains("pointer"))
        val empty = GitHubAcceptanceReportDiagnostics.fields(freshnessVerdict(checkedFreshnessClaim(200_000, 7)), true)
        val observedEmpty = requireNotNull(IssueReportDiagnostics.fromFields("CHAT_FAILURE", empty, 1_800_000_000_000))
        assertEquals(JsonArray(emptyList()), observedEmpty.values["acceptance_claim_freshness_predicates"])
    }

    private fun checkedFreshnessClaim(timestamp: Long, epoch: Long): JsonObject {
        val claim = Json.parseToJsonElement("""{"receipt_id":"private-receipt","pointer":"/state","value":"private-observation"}""") as JsonObject
        val receipt = Json.parseToJsonElement("""{"trusted":true,"success":true,"observed_at_ms":$timestamp,"epoch":$epoch,"result":{"state":"private-observation"}}""") as JsonObject
        return GitHubEvidencePolicy.verify(claim, mapOf("private-receipt" to receipt), 200_000, 7)
    }

    private fun freshnessVerdict(vararg claims: JsonObject) = buildJsonObject {
        put("acceptance_ledger", buildJsonObject {})
        put("claims", JsonArray(claims.toList()))
        put("unresolved_claims", JsonArray(claims.filter { it["status"] in setOf(JsonPrimitive("STALE"), JsonPrimitive("CONTRADICTED")) }))
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

    @Test fun recordedOperationFailuresLeadTheBoundedSampleWithoutChangingAcceptanceCounts() {
        val verdict = crowdedAcceptanceVerdict()
        val original = verdict.toString()
        val fields = GitHubAcceptanceReportDiagnostics.fields(verdict, retainedReport = true)
        val failures = listOf("github_ci:jobs", "github_read:files", "github_context:checks")
        assertEquals(JsonArray(failures.map { JsonPrimitive(GitHubEvidenceBook.digest(it)) }), JsonArray((fields["unmet_criterion_id_hashes"] as JsonArray).take(3)))
        assertEquals(JsonArray(List(3) { JsonPrimitive("FAIL") }), JsonArray((fields["unmet_criterion_states"] as JsonArray).take(3)))
        assertEquals(JsonArray(List(3) { JsonPrimitive("OPERATION_FAILED") }), JsonArray((fields["unmet_criterion_blockers"] as JsonArray).take(3)))
        assertEquals(JsonPrimitive(70), fields["required_capability_count"])
        assertEquals(JsonPrimitive(13), fields["verified_capability_count"])
        assertEquals(JsonPrimitive(30), fields["blocked_capability_count"])
        assertEquals(JsonPrimitive(24), fields["untested_capability_count"])
        assertEquals(JsonPrimitive(3), fields["acceptance_failed_operation_count"])
        assertEquals(JsonPrimitive(59), fields["unmet_criterion_count"])
        assertEquals(JsonPrimitive(2), fields["unmet_requirement_count"])
        assertEquals(JsonPrimitive(57), fields["unmet_capability_count"])
        assertEquals(JsonPrimitive(51), fields["unmet_criterion_omitted_count"])
        assertEquals(JsonPrimitive(7), fields["acceptance_approval_blocked_total"])
        assertEquals(JsonPrimitive(10), fields["acceptance_input_blocked_total"])
        assertEquals(JsonPrimitive("RETAINED_VERDICT"), fields["acceptance_evidence_scope"])
        assertEquals(JsonPrimitive("FULL_CATALOG"), fields["acceptance_capability_scope"])
        assertEquals(JsonPrimitive(false), (verdict["acceptance_ledger"] as JsonObject)["completion_verified"])
        assertEquals(original, verdict.toString())
        assertFalse(fields.toString().contains("private"))
    }

    @Test fun recordedFailuresSurviveFinalPublicDiagnosticCompaction() {
        val verdict = crowdedAcceptanceVerdict()
        val details = GitHubTaskLifecycle.acceptanceFailureDetails(verdict, retainedReport = true)
        val captured = JsonObject(
            details + buildJsonObject {
                put("run_id_hash", "a".repeat(64))
                put("incident_phase", "acceptance_verification")
                put("causal_provenance", "UNAVAILABLE")
                put("causal_failure_code", "GITHUB_TASK_INCOMPLETE")
                put("incident_evidence_status", "CAUSAL_EVIDENCE_UNAVAILABLE")
                put("incident_origin", "ACCEPTANCE")
                put("recovery_observation", "UNOBSERVED")
            }
        )
        val context = IssueReportContext.fromFields("CHAT_FAILURE", captured, 1_800_000_000_000)
        val diagnostic = requireNotNull(context.diagnostics)
        val values = diagnostic.values
        val states = values["unmet_criterion_states"] as JsonArray
        val size = states.size
        assertEquals(3, diagnostic.schemaVersion)
        assertTrue("The actual public byte bound must compact this fixture", size < 8)
        assertTrue("All three observed failures must survive prerequisite compaction", size >= 3)
        for (key in listOf("unmet_criterion_id_hashes", "unmet_criterion_reason_hashes", "unmet_criterion_categories", "unmet_criterion_states", "unmet_criterion_blockers")) {
            assertEquals(key, size, (values[key] as JsonArray).size)
        }
        assertEquals(JsonArray(List(3) { JsonPrimitive("FAIL") }), JsonArray(states.take(3)))
        assertEquals(JsonArray(List(3) { JsonPrimitive("OPERATION_FAILED") }), JsonArray((values["unmet_criterion_blockers"] as JsonArray).take(3)))
        assertEquals(JsonArray(listOf("github_ci:jobs", "github_read:files", "github_context:checks").map { JsonPrimitive(GitHubEvidenceBook.digest(it)) }), JsonArray((values["unmet_criterion_id_hashes"] as JsonArray).take(3)))
        assertEquals(JsonPrimitive(3), values["acceptance_failed_operation_count"])
        assertEquals(JsonPrimitive(59), values["unmet_criterion_count"])
        assertEquals(JsonPrimitive(59 - size), values["unmet_criterion_omitted_count"])
        assertEquals(JsonPrimitive(7), values["acceptance_approval_blocked_total"])
        assertEquals(JsonPrimitive(10), values["acceptance_input_blocked_total"])
        assertEquals(JsonPrimitive("RETAINED_VERDICT"), values["acceptance_evidence_scope"])
        assertEquals(JsonPrimitive("FULL_CATALOG"), values["acceptance_capability_scope"])
        assertEquals(JsonPrimitive("CAUSAL_EVIDENCE_UNAVAILABLE"), values["incident_evidence_status"])
        assertEquals("RECORDED_OPERATION_BLOCKERS", context.reasonCode)
        assertEquals("RESUME_UNVERIFIED_REQUIREMENTS", context.recoveryAction)
        assertNull(context.httpStatus)
        assertNull(context.dispatched)
        assertNull(context.remoteAcceptance)
        assertNull(context.operation)
        val encoded = Json.encodeToString(IssueReportDiagnostics.serializer(), diagnostic)
        assertTrue(encoded.encodeToByteArray().size <= IssueReportDiagnostics.MAX_BYTES)
        assertFalse(encoded.contains("private"))
        assertFalse(encoded.contains("github_ci:jobs"))
    }

    @Test fun moreThanEightFailuresKeepRecordedOrderAndDeclareEveryOmission() {
        val failures = (1..10).map { index ->
            capability("github_ci__private-$index:jobs", "FAILED", "OPERATION_FAILED")
        }
        val ledger = GitHubAcceptanceLedger.snapshot(
            requirements = JsonArray(listOf(requirement("private-requirement", "Device execution", "NOT_TESTED", true))),
            capabilities = JsonArray(failures + capability("github_ci__private-excluded:logs", "FAILED", "OPERATION_FAILED", required = false))
        )
        val fields = GitHubAcceptanceReportDiagnostics.fields(buildJsonObject { put("acceptance_ledger", ledger) }, retainedReport = false)
        assertEquals(JsonPrimitive(10), fields["acceptance_failed_operation_count"])
        assertEquals(JsonPrimitive(11), fields["unmet_criterion_count"])
        assertEquals(JsonPrimitive(3), fields["unmet_criterion_omitted_count"])
        assertEquals(JsonArray(List(8) { JsonPrimitive("FAIL") }), fields["unmet_criterion_states"])
        assertEquals(JsonArray((1..8).map { JsonPrimitive(GitHubEvidenceBook.digest("github_ci__private-$it:jobs")) }), fields["unmet_criterion_id_hashes"])
        assertEquals(JsonPrimitive("CURRENT_VERDICT"), fields["acceptance_evidence_scope"])
        assertFalse(fields.toString().contains("private"))
    }

    private fun crowdedAcceptanceVerdict(): JsonObject {
        val requirements = listOf(
            requirement("private-coverage", "Test all GitHub integration tools", "NOT_TESTED", true),
            requirement("private-device", "Device execution", "NOT_TESTED", true)
        ) + (1..3).map { requirement("private-passed-$it", "Verified requirement", "PASS", true) }
        val blocked = List(7) { "FIXTURE_APPROVAL_MODE_REQUIRED" } + List(3) { "REPOSITORY_INPUT_REQUIRED" } +
            List(3) { "ISSUE_ID_REQUIRED" } + List(4) { "WORKFLOW_RUN_OR_JOB_REQUIRED" } + List(13) { "PRECHECK_REJECTED" }
        val capabilities = (1..24).map { capability("github_issue__private-untested-$it:list", "NOT_TESTED", "EXECUTION_NOT_EXERCISED") } +
            blocked.mapIndexed { index, blocker -> capability("github_issue__private-blocked-$index:list", "BLOCKED", blocker) } +
            listOf("github_ci:jobs", "github_read:files", "github_context:checks").map { capability(it, "FAILED", "OPERATION_FAILED") } +
            (1..13).map { capability("github_issue__private-passed-$it:list", "PASSED", "") } +
            capability("github_ci__private-excluded:logs", "FAILED", "OPERATION_FAILED", required = false)
        return buildJsonObject {
            put("acceptance_ledger", GitHubAcceptanceLedger.snapshot(JsonArray(requirements), JsonArray(capabilities)))
            put("task_kind", "GENERAL")
            put("unresolved_claims", JsonArray(emptyList()))
            put(
                "test_checklist",
                buildJsonObject {
                    put("coverage_scope", "FULL_CATALOG")
                    put(
                        "pagination_resume",
                        buildJsonObject {
                            put("mode", "BOUNDED_SMOKE_SAMPLE")
                            put("workflow_dependency_observation", "OBSERVED_EXECUTED_JOB")
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
                        "stage_budget",
                        buildJsonObject {
                            put("effective_call_limit", 256)
                            put("used_calls", 140)
                            put("remaining_calls", 116)
                            put("verification_reserve", 2)
                        }
                    )
                }
            )
            put(
                "execution_ledger",
                buildJsonObject {
                    put("required_criteria", 5)
                    put("verified_criteria", 3)
                    put("remaining_criteria", 2)
                    put("unresolved_remote_outcomes", 0)
                }
            )
            put(
                "stop_reason",
                buildJsonObject {
                    put("code", "RECORDED_OPERATION_BLOCKERS")
                    put("recovery_action", "RESUME_UNVERIFIED_REQUIREMENTS")
                    put("pending_continuations", 4)
                    put("pending_local_fragments", 0)
                    put("pending_remote_continuations", 4)
                }
            )
        }
    }

    private fun capability(id: String, status: String, blocker: String, required: Boolean = true) = buildJsonObject {
        put("id", id)
        put("scope", "private operation scope")
        put("status", status)
        put("required", required)
        put("blocker_code", blocker)
        put("reason", "private recorded reason for $id")
        if (status == "FAILED") put("receipt_ids", JsonArray(listOf(JsonPrimitive("private-receipt-$id"))))
    }

    @Test fun ambiguousProtectionObservationRequestsPermissionCheckWithoutClaimingNoPolicy() {
        val verdict = Json.parseToJsonElement("""{"acceptance_ledger":{},"status_coverage":[{"item":"Required-check policy","branch_protection_observation":"NOT_FOUND_OR_INACCESSIBLE","branch_rules_observation":"OBSERVED","branch_protection_http_status":404,"branch_rules_http_status":200}]}""") as JsonObject
        val fields = GitHubAcceptanceReportDiagnostics.fields(verdict, true)
        assertEquals(JsonPrimitive("CHECK_PROTECTION_READ_PERMISSION"), fields["status_policy_recovery"])
        assertEquals(JsonPrimitive("NOT_FOUND_OR_INACCESSIBLE"), fields["branch_protection_observation"])
        val diagnostic = requireNotNull(IssueReportContext.fromFields("CHAT_FAILURE", fields, 1_800_000_000_000).diagnostics)
        assertEquals(JsonPrimitive(404), diagnostic.values["branch_protection_http_status"])
        assertEquals(JsonPrimitive(200), diagnostic.values["branch_rules_http_status"])
    }

    @Test fun graphPreparationBlockersAndEndpointFailuresSurviveSanitizedPublicReport() {
        val verdict = Json.parseToJsonElement(
            """{"acceptance_ledger":{"requirements":[{"id":"graph-query","required":true,"state":"BLOCKED","blocker_code":"GRAPH_INDEX_REQUIRED"},{"id":"graph-path","required":true,"state":"BLOCKED","blocker_code":"GRAPH_INPUT_REQUIRED"},{"id":"graph-index","required":true,"state":"BLOCKED","blocker_code":"GRAPH_INDEX_UNCONFIRMED"}]},"status_coverage":[{"item":"Required-check policy","branch_protection_observation":"OBSERVED","branch_rules_observation":"ACCESS_DENIED","branch_protection_http_status":200,"branch_rules_http_status":403,"message":"private endpoint detail"}]}"""
        ) as JsonObject
        val fields = GitHubAcceptanceReportDiagnostics.fields(verdict, true)
        val diagnostic = requireNotNull(IssueReportContext.fromFields("CHAT_FAILURE", fields, 1_800_000_000_000).diagnostics)
        assertEquals(JsonPrimitive(2), diagnostic.values["acceptance_input_blocked_total"])
        assertEquals(JsonPrimitive("CHECK_RULES_READ_PERMISSION"), diagnostic.values["status_policy_recovery"])
        assertEquals(JsonPrimitive(403), diagnostic.values["branch_rules_http_status"])
        assertEquals(setOf("GRAPH_INDEX_REQUIRED", "GRAPH_INPUT_REQUIRED", "GRAPH_INDEX_UNCONFIRMED"), (diagnostic.values["unmet_criterion_blockers"] as JsonArray).map { (it as JsonPrimitive).content }.toSet())
        assertFalse(diagnostic.values.toString().contains("private"))
        val invalid = IssueReportContext.fromFields("CHAT_FAILURE", JsonObject(fields + mapOf("branch_rules_http_status" to JsonPrimitive(999), "branch_protection_http_status" to JsonPrimitive("403"))), 1_800_000_000_000).diagnostics
        assertFalse(requireNotNull(invalid).values.containsKey("branch_rules_http_status"))
        assertFalse(invalid.values.containsKey("branch_protection_http_status"))
    }

    @Test fun graphAndWorkflowPrerequisitesRetainTypedCausesWithoutSourceIdentifiers() {
        val verdict = Json.parseToJsonElement("""{"acceptance_ledger":{},"test_checklist":{"capabilities":[{"id":"repository_graph:query","required":true,"blocker_code":"REPOSITORY_INPUT_REQUIRED","repository_graph_observation":"NOT_INDEXED","required_inputs":["private/path"]},{"id":"repository_graph:node","required":true,"blocker_code":"REPOSITORY_INPUT_REQUIRED","repository_graph_observation":"NOT_INDEXED"},{"id":"repository_graph:note","required":false,"blocker_code":"REPOSITORY_INPUT_REQUIRED","repository_graph_observation":"NOT_INDEXED"}],"pagination_resume":{"workflow_dependency_observation":"MISSING_EXECUTED_JOB"}}}""") as JsonObject
        for (code in listOf("REPOSITORY_INPUT_REQUIRED", "GRAPH_INDEX_REQUIRED", "GRAPH_INPUT_REQUIRED")) {
            val fields = GitHubAcceptanceReportDiagnostics.fields(Json.parseToJsonElement(verdict.toString().replace("REPOSITORY_INPUT_REQUIRED", code)) as JsonObject, true)
            assertEquals(JsonPrimitive(2), fields["graph_input_blocked_count"])
            assertEquals(JsonPrimitive("NOT_INDEXED"), fields["repository_graph_observation"])
            assertEquals(JsonPrimitive("MISSING_EXECUTED_JOB"), fields["workflow_dependency_observation"])
            assertFalse(fields.toString().contains("private/path"))
            assertFalse(fields.toString().contains("repository_graph:query"))
        }
        val absent = GitHubAcceptanceReportDiagnostics.fields(buildJsonObject { put("acceptance_ledger", JsonObject(emptyMap())) }, true)
        assertFalse("repository_graph_observation" in absent)
        assertFalse("workflow_dependency_observation" in absent)
        assertFalse("graph_input_blocked_count" in absent)
    }
}
