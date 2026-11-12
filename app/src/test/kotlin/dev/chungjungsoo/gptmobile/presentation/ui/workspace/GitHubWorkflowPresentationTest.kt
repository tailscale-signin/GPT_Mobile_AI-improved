package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import dev.chungjungsoo.gptmobile.data.agent.TaskRecoveryAction
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubWorkflowPresentationTest {
    private fun run(status: String = AgentRunStatus.COMPLETED) = AgentRun("run", 1, 2, 3, "profile", "provider", "model", status)
    private fun event(id: String, arguments: String = "{}", status: String = ToolEventStatus.COMPLETED, name: String = "github_read", error: String? = null, runId: String = "run") =
        ToolEvent(id, runId, id.hashCode(), id, null, null, name, name, arguments, null, null, status, status == ToolEventStatus.FAILED, error = error)

    @Test fun reconciliationAndApprovalBlockersExplainTheirActualPrerequisites() {
        val handoff = GitHubWorkflowPresentation.snapshot(run(AgentRunStatus.BLOCKED).copy(terminalError = "OUTPUT_CONTINUATION_PENDING_TOOLS"), emptyList(), true)
        assertEquals("Receipt reconciliation required", handoff.phase)
        assertTrue(handoff.recoveryExplanation.contains("completed tool receipts are retained"))
        assertFalse(handoff.recoveryExplanation.contains("Update the blocked configuration"))
        assertTrue(handoff.reconciliationRequired)
        assertEquals(listOf(TaskRecoveryAction.CONTINUE_RUN), handoff.recovery)
        assertFalse(TaskRecoveryAction.RETRY_FAILED_STAGE in handoff.recovery)
        val approval = GitHubWorkflowPresentation.snapshot(run(AgentRunStatus.BLOCKED).copy(terminalError = "WAITING_FOR_APPROVAL"), emptyList(), true)
        assertEquals("Approval required", approval.phase)
        assertTrue(approval.recoveryExplanation.contains("existing proposed changes card"))
        assertFalse(approval.reconciliationRequired)
        assertTrue(approval.recovery.isEmpty())
    }

    @Test fun successfulApiCallsDoNotVerifyAcceptance() {
        val view = GitHubWorkflowPresentation.snapshot(run(), listOf(event("one")), true)
        assertEquals("Answer ready", view.phase)
        assertEquals(GitHubAcceptanceStatus.NOT_TESTED, view.acceptance)
        assertEquals(1, view.operations.single().completed)
        assertFalse(view.verificationAvailable)
    }

    @Test fun savedVerifierRequiresCompleteEvidenceForPass() {
        val verdict = Json.parseToJsonElement("""{"completion_verified":true,"evidence_retention_complete":true,"requirements":[{"requirement":"Fix and test","status":"PASSED"}]}""") as kotlinx.serialization.json.JsonObject
        val verified = GitHubWorkflowPresentation.snapshot(run(), emptyList(), true, verdict)
        assertEquals(GitHubAcceptanceStatus.PASS, verified.acceptance)
        assertTrue(verified.recovery.isEmpty())
        val missing = GitHubWorkflowPresentation.snapshot(run(), emptyList(), true, verdict, evidenceReadFailed = true)
        assertEquals(GitHubAcceptanceStatus.BLOCKED, missing.acceptance)
        assertEquals(GitHubAcceptanceStatus.BLOCKED, missing.criteria.single().status)
    }

    @Test fun oldOrMissingRetentionFieldDoesNotBecomeVerified() {
        val verdict = Json.parseToJsonElement("""{"completion_verified":true,"requirements":[{"status":"PASSED"}]}""") as kotlinx.serialization.json.JsonObject
        val view = GitHubWorkflowPresentation.snapshot(run(), emptyList(), true, verdict)
        assertEquals(GitHubAcceptanceStatus.BLOCKED, view.acceptance)
        assertEquals(null, view.evidenceComplete)
    }

    @Test fun failuresAndUntestedCriteriaRemainDistinct() {
        val verdict = Json.parseToJsonElement("""{"evidence_retention_complete":true,"requirements":[{"requirement":"Tests","status":"FAILED"},{"requirement":"Device","status":"UNTESTED"}]}""") as kotlinx.serialization.json.JsonObject
        val view = GitHubWorkflowPresentation.snapshot(run(), emptyList(), true, verdict)
        assertEquals(GitHubAcceptanceStatus.FAIL, view.acceptance)
        assertEquals(listOf(GitHubAcceptanceStatus.FAIL, GitHubAcceptanceStatus.NOT_TESTED), view.criteria.map { it.status })
    }

    @Test fun legacyAcceptanceStateOverridesOperationStatus() {
        val verdict = Json.parseToJsonElement("""{"evidence_retention_complete":true,"requirements":[{"requirement":"Verify cleanup","status":"PASSED","acceptance_state":"BLOCKED"}]}""") as kotlinx.serialization.json.JsonObject
        val view = GitHubWorkflowPresentation.snapshot(run(), emptyList(), true, verdict)
        assertEquals(GitHubAcceptanceStatus.BLOCKED, view.criteria.single().status)
        assertEquals(GitHubAcceptanceStatus.BLOCKED, view.acceptance)
    }

    @Test fun savedPendingApprovalSuppressesRecoveryWithoutTerminalError() {
        val verdict = Json.parseToJsonElement("""{"execution_ledger":{"pending_approvals":[{"engineering_task_id":"task"}]}}""") as kotlinx.serialization.json.JsonObject
        val view = GitHubWorkflowPresentation.snapshot(run(AgentRunStatus.PARTIAL), emptyList(), true, verdict)
        assertEquals("Approval required", view.phase)
        assertTrue(view.recovery.isEmpty())
        assertTrue(view.recoveryExplanation.contains("existing proposed changes card"))
    }

    @Test fun unresolvedRemoteOutcomeTakesPrecedenceOverSavedApproval() {
        val verdict = Json.parseToJsonElement("""{"execution_ledger":{"recovery":"RECONCILE_REMOTE_OUTCOME","pending_approvals":[{"engineering_task_id":"task"}]}}""") as kotlinx.serialization.json.JsonObject
        val view = GitHubWorkflowPresentation.snapshot(run(AgentRunStatus.PARTIAL), emptyList(), true, verdict)
        assertEquals("Remote outcome needs reconciliation", view.phase)
        assertTrue(view.reconciliationRequired)
        assertTrue(view.recovery.isEmpty())
        assertTrue(view.recoveryExplanation.contains("before approving or repeating"))
    }

    @Test fun paginationGroupsPreserveResourceAndQueryBoundaries() {
        val events = listOf(
            event("1", """{"action":"issues","repo":"a","page":1}"""),
            event("2", """{"action":"issues","repo":"a","page":2}"""),
            event("3", """{"action":"issues","repo":"b","page":1}"""),
            event("4", """{"action":"issues","repo":"a","query":"security","page":1}""")
        )
        val view = GitHubWorkflowPresentation.snapshot(run(), events, true)
        assertEquals(listOf(2, 1, 1), view.operations.map { it.observed })
    }

    @Test fun unknownWriteOutcomeAllowsReconciliationButNeverDirectRetry() {
        val write = event("one", """{"action":"commit"}""", ToolEventStatus.FAILED, "github_write", "MUTATION_OUTCOME_UNKNOWN")
        val view = GitHubWorkflowPresentation.snapshot(run(AgentRunStatus.FAILED), listOf(write), true)
        assertEquals(listOf(TaskRecoveryAction.CONTINUE_RUN), view.recovery)
        assertEquals("MUTATION_OUTCOME_UNKNOWN", view.failureCategory)
    }

    @Test fun unknownGatewayOutcomeWithoutSavedJobNeverOffersResumeOrRetry() {
        val failed = run(AgentRunStatus.FAILED)
        val gateway = event("gateway", status = ToolEventStatus.FAILED, name = "gateway", error = "GATEWAY_OUTCOME_UNKNOWN")
        val fromRun = GitHubWorkflowPresentation.snapshot(failed.copy(terminalError = "GATEWAY_OUTCOME_UNKNOWN"), emptyList(), true)
        val fromEvent = GitHubWorkflowPresentation.snapshot(failed, listOf(gateway), true)
        listOf(fromRun, fromEvent).forEach { view ->
            assertTrue(view.recovery.isEmpty())
            assertTrue(view.recoveryExplanation.contains("no saved job can be reconciled"))
        }
    }

    @Test fun incompleteActivityHistoryPreventsRetry() {
        val view = GitHubWorkflowPresentation.snapshot(run(AgentRunStatus.FAILED), emptyList(), false)
        assertEquals(listOf(TaskRecoveryAction.CONTINUE_RUN), view.recovery)
        assertTrue(view.recoveryExplanation.contains("incomplete activity history"))
    }

    @Test fun timedOutReadCanRetryAfterRunStops() {
        val read = event("one", status = ToolEventStatus.FAILED, error = "NETWORK_TIMEOUT")
        val failed = GitHubWorkflowPresentation.snapshot(run(AgentRunStatus.FAILED), listOf(read), true)
        assertTrue(TaskRecoveryAction.RETRY_FAILED_STAGE in failed.recovery)
        val active = GitHubWorkflowPresentation.snapshot(run(AgentRunStatus.WAITING_REMOTE_JOB), listOf(read), true)
        assertTrue(active.recovery.isEmpty())
    }

    @Test fun savedViewFailuresDoNotOfferGenericRetry() {
        for (code in listOf("SAVED_VIEW_UNAVAILABLE", "SAVED_VIEW_CURSOR_ALTERED", "SAVED_VIEW_WRONG_OPERATION")) {
            val read = event("one", status = ToolEventStatus.FAILED, error = code)
            val snapshot = GitHubWorkflowPresentation.snapshot(run(AgentRunStatus.FAILED), listOf(read), true)
            assertEquals(code, snapshot.failureCategory)
            assertTrue(snapshot.recovery.isEmpty())
        }
    }

    @Test fun unavailableSavedViewResumesOnlyThroughItsVerifiedSourceRead() {
        val read = event("one", """{"view_cursor":"saved:page:2"}""", ToolEventStatus.FAILED, error = "SAVED_VIEW_UNAVAILABLE")
        val snapshot = GitHubWorkflowPresentation.snapshot(run(AgentRunStatus.FAILED), listOf(read), true, savedReadProof())

        assertEquals(listOf(TaskRecoveryAction.CONTINUE_RUN), snapshot.recovery)
        assertTrue(snapshot.sourceReadRecoveryAvailable)
        assertEquals("Saved read can be restored", snapshot.phase)
        assertTrue(snapshot.recoveryExplanation.contains("original read"))
        assertFalse(TaskRecoveryAction.RETRY_FAILED_STAGE in snapshot.recovery)
    }

    @Test fun savedSourceProofCannotAuthorizeAnotherCallCursorMutationOrRun() {
        val read = event("one", """{"view_cursor":"saved:page:2"}""", ToolEventStatus.FAILED, error = "SAVED_VIEW_UNAVAILABLE")
        val proof = savedReadProof()
        val original = proof.getValue("source_read_recovery") as JsonObject
        val invalid = listOf(
            JsonObject(original + ("failed_call_id" to JsonPrimitive("other"))),
            JsonObject(original + ("source_bound" to JsonPrimitive(false))),
            JsonObject(original + ("source_receipt_id" to JsonPrimitive(""))),
            JsonObject(original + ("binding" to JsonPrimitive(""))),
            JsonObject(original + ("arguments" to Json.parseToJsonElement("""{"view_cursor":"saved:page:2"}"""))),
            JsonObject(original + mapOf("tool_name" to JsonPrimitive("github_write"), "arguments" to Json.parseToJsonElement("""{"action":"commit"}""")))
        )
        invalid.forEach { changed ->
            val snapshot = GitHubWorkflowPresentation.snapshot(run(AgentRunStatus.FAILED), listOf(read), true, JsonObject(proof + ("source_read_recovery" to changed)))
            assertTrue(snapshot.recovery.isEmpty())
        }
        val unrelated = GitHubWorkflowPresentation.snapshot(run(AgentRunStatus.FAILED).copy(terminalError = "SAVED_VIEW_UNAVAILABLE"), listOf(read.copy(runId = "other")), true, proof)
        assertTrue(unrelated.recovery.isEmpty())
    }

    @Test fun savedSourceProofDoesNotOverrideMissingEvidenceApprovalOrUnknownMutation() {
        val read = event("one", """{"view_cursor":"saved:page:2"}""", ToolEventStatus.FAILED, error = "SAVED_VIEW_UNAVAILABLE")
        val proof = savedReadProof()
        val failed = run(AgentRunStatus.FAILED).copy(terminalError = "SAVED_VIEW_UNAVAILABLE")
        val variants = listOf(
            GitHubWorkflowPresentation.snapshot(failed, listOf(read), false, proof),
            GitHubWorkflowPresentation.snapshot(failed, listOf(read), true, proof, evidenceReadFailed = true),
            GitHubWorkflowPresentation.snapshot(failed, listOf(read), true, JsonObject(proof + ("evidence_retention_complete" to JsonPrimitive(false)))),
            GitHubWorkflowPresentation.snapshot(failed, listOf(read), true, JsonObject(proof + ("pending_approvals" to Json.parseToJsonElement("""[{"engineering_task_id":"task"}]""")))),
            GitHubWorkflowPresentation.snapshot(failed, listOf(read, event("write", """{"action":"commit"}""", ToolEventStatus.FAILED, "github_write", "MUTATION_OUTCOME_UNKNOWN")), true, proof)
        )
        variants.forEach { snapshot ->
            assertTrue(snapshot.recovery.isEmpty())
            assertFalse(snapshot.sourceReadRecoveryAvailable)
        }
        for (code in listOf("SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_CURSOR_ALTERED", "AUTH_REQUIRED", "POLICY_STALE")) {
            val snapshot = GitHubWorkflowPresentation.snapshot(failed.copy(terminalError = code), listOf(read.copy(error = code)), true, proof)
            assertTrue("Unexpected recovery for $code", snapshot.recovery.isEmpty())
        }
    }

    private fun savedReadProof() = Json.parseToJsonElement(
        """{"evidence_retention_complete":true,"source_read_recovery":{"schema":"github.saved-read-recovery.v1","available":true,"read_only":true,"source_bound":true,"failed_call_id":"one","tool_name":"github_read","arguments":{"path":"A.kt","commit_sha":"${"a".repeat(40)}"},"source_receipt_id":"source-receipt","binding":"original-task-binding"}}"""
    ) as JsonObject

    @Test fun distinctRunAndCallIdentityExcludeReplayedOrUnrelatedActivity() {
        val one = event("one")
        val view = GitHubWorkflowPresentation.snapshot(run(), listOf(one, one.copy(eventId = "replay"), event("other", runId = "other")), true)
        assertEquals(1, view.observedOperations)
        assertEquals(1, view.operations.single().observed)
    }

    @Test fun unchangedAuthorizationPrivacyAndReasoningBlocksDoNotOfferReplay() {
        listOf("AUTH_REQUIRED", "PRECHECK_FAILED", "PERSONAL_DATA_LOCAL_ONLY", "REASONING_OFF_UNSUPPORTED", "GITHUB_VALIDATION_FAILED", "GITHUB_REVIEW_BLOCKED").forEach { failure ->
            val view = GitHubWorkflowPresentation.snapshot(run(AgentRunStatus.FAILED).copy(terminalError = failure), emptyList(), true)
            assertTrue("Unexpected recovery for $failure", view.recovery.isEmpty())
            assertTrue(view.recoveryExplanation.contains("Update the blocked"))
        }
    }

    @Test fun retryWaitsUntilKnownCooldownExpires() {
        val failed = run(AgentRunStatus.FAILED).copy(terminalError = "GITHUB_PRIMARY_RATE_LIMITED; next eligible retry: 2026-11-04T12:00:00Z")
        val retryAt = java.time.Instant.parse("2026-11-04T12:00:00Z").toEpochMilli()
        assertTrue(GitHubWorkflowPresentation.snapshot(failed, emptyList(), true, nowMillis = retryAt - 1).recovery.isEmpty())
        assertTrue(TaskRecoveryAction.RETRY_FAILED_STAGE in GitHubWorkflowPresentation.snapshot(failed, emptyList(), true, nowMillis = retryAt).recovery)
    }

    @Test fun toolReadinessDoesNotClaimAcceptanceOrTestingFromDiscovery() {
        val verdict = Json.parseToJsonElement("""{"evidence_retention_complete":true,"acceptance_ledger":{"capabilities":[{"criterion":"Read source","readiness":{"discovered":true,"authorization":"AUTHORIZED","availability":"UNKNOWN","tested":false,"successfully_tested":false}}]}}""") as kotlinx.serialization.json.JsonObject
        val view = GitHubWorkflowPresentation.snapshot(run(), emptyList(), true, verdict)
        assertEquals(GitHubAcceptanceStatus.NOT_TESTED, view.acceptance)
        assertEquals(true, view.capabilities.single().discovered)
        assertEquals(false, view.capabilities.single().successfullyTested)
    }

    @Test fun canonicalAcceptanceLedgerOverridesLegacyRowsAndCapabilitySuccess() {
        val verdict = Json.parseToJsonElement("""{"completion_verified":true,"evidence_retention_complete":true,"requirements":[{"requirement":"Old row","status":"PASSED"}],"acceptance_ledger":{"completion_verified":false,"requirements":[{"criterion":"Current criterion","state":"NOT_TESTED","reason":"No tests retained"}],"capabilities":[{"criterion":"API read","state":"PASS"}]}}""") as kotlinx.serialization.json.JsonObject
        val view = GitHubWorkflowPresentation.snapshot(run(), emptyList(), true, verdict)
        assertEquals(GitHubAcceptanceStatus.NOT_TESTED, view.acceptance)
        assertEquals("Current criterion", view.criteria.single().requirement)
        assertEquals("No tests retained", view.criteria.single().detail)
    }
}
