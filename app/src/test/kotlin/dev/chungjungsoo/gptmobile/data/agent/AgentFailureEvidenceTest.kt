package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.accounting.ModelInvocation
import dev.chungjungsoo.gptmobile.data.context.GitHubProfileLockedException
import dev.chungjungsoo.gptmobile.data.context.GitHubProfileSelectionRejected
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.github.GatewayExecutionRejected
import dev.chungjungsoo.gptmobile.data.github.GitHubCooldownActive
import dev.chungjungsoo.gptmobile.data.github.GitHubRateLimitAdmissionException
import dev.chungjungsoo.gptmobile.data.github.GitHubRateLimitManager
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentFailureEvidenceTest {
    @Test fun typedGitHubGuardsRetainTheirEvidenceWithoutWholeRunDispatchAliases() {
        for (error in listOf(cooldown(), profileLock())) {
            for (partial in listOf(false, true)) {
                val event = requireNotNull(AgentFailureEvidence.exception(error, partial))
                val fields = requireNotNull(event.failureDetails)
                assertEquals(if (error is GitHubCooldownActive) "GITHUB_COOLDOWN_ACTIVE" else "GITHUB_PROFILE_LOCKED", event.errorCode)
                assertEquals(JsonPrimitive(false), fields["request_replay_allowed"])
                assertEquals(JsonPrimitive(false), fields["retryable"])
                if (error is GitHubCooldownActive) {
                    assertEquals(JsonPrimitive(130_000L), fields["github_cooldown_deadline_ms"])
                } else {
                    assertEquals(JsonPrimitive(false), fields["github_selection_profile_match"])
                }
                for (key in listOf("dispatched", "dispatch_state", "attempt_dispatch_state", "remote_acceptance", "partial_output_preserved", "http_status")) {
                    assertFalse("Unexpected inferred $key", fields.containsKey(key))
                }
            }
        }
    }

    @Test fun aPriorToolTimelineWithNoTextCannotBecomeAnUnsentRun() {
        val message = MessageV2(
            content = "",
            thoughts = "",
            platformType = "profile",
            timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.TOOL, toolSequence = 0))
        )
        for (error in listOf(cooldown(), profileLock())) {
            for (gatewayPreflight in listOf(false, true)) {
                val failure = requireNotNull(coordinatorFailureRecord(message, error, gatewayPreflight))
                assertNull(failure.dispatched)
                assertEquals(RemoteRequestAcceptance.UNKNOWN, failure.remoteAcceptance)
                assertEquals(false, failure.requestReplayAllowed)
                assertFalse(failure.retryAllowed)
            }
        }
    }

    @Test fun aLaterGitHubGuardCannotErasePriorUnknownOrDispatchedOutcomes() {
        val prior = ProfileFailureRecord("TOOL_OUTCOME_UNKNOWN", false, dispatched = true, remoteAcceptance = RemoteRequestAcceptance.UNKNOWN, phase = "tool_execution")
        val message = MessageV2(content = "", platformType = "profile", timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, profileFailure = prior)))
        for (error in listOf(cooldown(), profileLock())) {
            val unknown = requireNotNull(coordinatorFailureRecord(message, error, false))
            assertEquals("TOOL_OUTCOME_UNKNOWN", unknown.category)
            assertEquals(true, unknown.dispatched)
            assertEquals(RemoteRequestAcceptance.UNKNOWN, unknown.remoteAcceptance)
            assertFalse(unknown.retryAllowed)
            val rejected = prior.copy(category = "RATE_LIMIT", remoteAcceptance = RemoteRequestAcceptance.NO, requestReplayAllowed = false)
            val retained = message.copy(timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, profileFailure = rejected)))
            val result = requireNotNull(coordinatorFailureRecord(retained, error, false))
            assertEquals(true, result.dispatched)
            assertEquals(RemoteRequestAcceptance.NO, result.remoteAcceptance)
            assertEquals(false, result.requestReplayAllowed)
        }
    }

    private fun cooldown(): GitHubCooldownActive {
        val limits = GitHubRateLimitManager()
        limits.record(headersOf("X-RateLimit-Remaining" to listOf("0"), "X-RateLimit-Reset" to listOf("130")), nowMillis = 100_000)
        return assertThrows(GitHubCooldownActive::class.java) { limits.requireAvailable("core", 110) }
    }

    private fun profileLock() = GitHubProfileSelectionRejected(false, true, true, "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64))

    @Test fun localGitHubCooldownPreservesTheDelayWithoutClaimingAnHttpResponse() {
        val error = GitHubRateLimitAdmissionException("GITHUB_PRIMARY_RATE_LIMITED", 12_000)
        val event = requireNotNull(AgentFailureEvidence.exception(error))
        val details = requireNotNull(event.failureDetails)
        assertEquals("GITHUB_PRIMARY_RATE_LIMITED", event.errorCode)
        assertEquals("BLOCKED", event.taskOutcome)
        assertEquals(JsonPrimitive(12_000L), details["retry_after_ms"])
        assertEquals(JsonPrimitive("tool_precheck"), details["failed_stage"])
        assertEquals(JsonPrimitive(false), details["dispatched"])
        assertEquals(JsonPrimitive(false), details["request_replay_allowed"])
        assertFalse(details.containsKey("http_status"))
        val partial = requireNotNull(AgentFailureEvidence.exception(error, partialOutput = true))
        assertEquals("PARTIAL", partial.taskOutcome)
        assertFalse(requireNotNull(partial.failureDetails).containsKey("dispatched"))
    }

    @Test fun profileLockReportsPolicyAdmissionWithoutUsingAnotherProfile() {
        val event = requireNotNull(AgentFailureEvidence.exception(GitHubProfileLockedException()))
        val details = requireNotNull(event.failureDetails)
        assertEquals("POLICY_STALE", event.errorCode)
        assertEquals("BLOCKED", event.taskOutcome)
        assertEquals(JsonPrimitive("provider_admission"), details["failed_stage"])
        assertEquals(JsonPrimitive("NO"), details["remote_acceptance"])
        assertEquals(JsonPrimitive(false), details["request_replay_allowed"])
    }

    @Test fun localRecoveryFailureKeepsTheEarlierInterruptionSeparateFromTheUnsentWake() {
        val original = json("""{"code":"429","details":{"http_status":429,"dispatched":true,"invocation_id":"earlier-invocation","retry_after_ms":30156}}""")
        val expected = dev.chungjungsoo.gptmobile.data.network.GitHubProviderCheckpoint("a".repeat(64), "b".repeat(64))
        val error = ProviderRecoveryFailure("PROVIDER_WAIT_EVIDENCE_UNAVAILABLE", original, ProviderRecoveryFailure.correlation(expected, "claim", 3, "current-run"))
        val event = requireNotNull(AgentFailureEvidence.exception(error))
        val record = ProfileFailureRecord.fromProvider(event.message, event.errorCode, event.failureDetails)
        val restored = Json.decodeFromString(ProfileFailureRecord.serializer(), Json.encodeToString(ProfileFailureRecord.serializer(), record))
        assertEquals("PROVIDER_WAIT_EVIDENCE_UNAVAILABLE", restored.category)
        assertEquals("lifecycle", restored.phase)
        assertEquals(false, restored.dispatched)
        assertEquals(RemoteRequestAcceptance.NO, restored.remoteAcceptance)
        assertEquals(null, restored.invocationId)
        assertFalse(restored.retryAllowed)
        assertEquals(false, restored.requestReplayAllowed)
        assertEquals(original, restored.recoveryDetails?.get("original_interruption"))
        assertEquals(JsonPrimitive("claim"), restored.recoveryDetails?.get("resume_claim_id"))
        assertEquals(JsonPrimitive(3), restored.recoveryDetails?.get("ownership_fence"))
    }

    @Test fun coordinatorDoesNotApplyAnEarlierProviderDispatchToTheCurrentRecoveryRefusal() {
        val prior = ProfileFailureRecord("RATE_LIMIT", true, dispatched = true, remoteAcceptance = RemoteRequestAcceptance.NO, phase = "model_invocation", invocationId = "earlier")
        val message = dev.chungjungsoo.gptmobile.data.database.entity.MessageV2(chatId = 1, content = "", platformType = "profile", timeline = listOf(dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem(dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType.NOTICE, profileFailure = prior)))
        val failure = requireNotNull(coordinatorFailureRecord(message, ProviderRecoveryFailure("PROVIDER_WAIT_CHECKPOINT_CHANGED"), false))
        assertEquals("PROVIDER_WAIT_CHECKPOINT_CHANGED", failure.category)
        assertEquals(false, failure.dispatched)
        assertEquals("lifecycle", failure.phase)
        assertEquals(null, failure.invocationId)
        val unknown = message.copy(timeline = listOf(dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem(dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType.NOTICE, profileFailure = prior.copy(category = "TOOL_OUTCOME_UNKNOWN", remoteAcceptance = RemoteRequestAcceptance.UNKNOWN))))
        val guarded = requireNotNull(coordinatorFailureRecord(unknown, ProviderRecoveryFailure("PROVIDER_WAIT_OUTCOME_UNKNOWN"), false))
        assertEquals("TOOL_OUTCOME_UNKNOWN", guarded.category)
        assertFalse(guarded.retryAllowed)
        assertEquals(JsonPrimitive("PROVIDER_WAIT_OUTCOME_UNKNOWN"), guarded.recoveryDetails?.get("recovery_code"))
    }

    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
    private val failure = ProfileFailureRecord("COMPLETION_REPAIR_BUDGET_EXHAUSTED", false, phase = "provider_generation", invocationId = "probe-17")

    private fun invocation(evidence: JsonObject = json("""{"invocation_id":"probe-17","response_parsing":{"measurement_scope":"android_http_attempt","http_status":200,"remote_acceptance":"YES","server_request_id":"req-17"}}""")) = ModelInvocation(
        id = "probe-17",
        parentRunId = "run-1",
        turnKey = "turn-1",
        provider = "provider",
        model = "model",
        kind = "github-review-preflight",
        inputTokens = 0,
        outputTokens = 0,
        status = "FAILED",
        usageEvidenceJson = evidence.toString()
    )

    @Test fun terminalFailureRetainsTheMatchingSavedProviderDispatchAndAcceptance() {
        val reconciled = requireNotNull(AgentFailureEvidence.reconcile(failure, "run-1", invocation()))
        assertEquals(true, reconciled.dispatched)
        assertEquals(RemoteRequestAcceptance.YES, reconciled.remoteAcceptance)
        assertEquals("req-17", reconciled.serverRequestId)
        assertFalse(reconciled.retryAllowed)
        val terminal = AgentRunTerminalDiagnostics.from(AgentRunStatus.FAILED, reconciled)
        assertEquals("COMPLETION_REPAIR_BUDGET_EXHAUSTED", terminal.reason)
        assertEquals("provider_generation", terminal.stage)
        assertEquals("FAILED", terminal.executionState)
        assertEquals("NOT_MEASURED", terminal.taskVerification)
        assertEquals(RemoteRequestAcceptance.YES, terminal.remoteAcceptance)
    }

    @Test fun unrelatedOrUncorrelatedAccountingCannotSupplyTerminalFacts() {
        val mismatches = listOf(
            invocation().copy(id = "another-invocation"),
            invocation().copy(parentRunId = "another-run"),
            invocation(json("""{"invocation_id":"another-invocation","response_parsing":{"measurement_scope":"android_http_attempt","http_status":200,"remote_acceptance":"YES"}}"""))
        )
        mismatches.forEach { assertEquals(failure, AgentFailureEvidence.reconcile(failure, "run-1", it)) }
        val uncorrelated = failure.copy(invocationId = null)
        assertEquals(uncorrelated, AgentFailureEvidence.reconcile(uncorrelated, "run-1", invocation()))
        assertEquals(failure, AgentFailureEvidence.reconcile(failure, "run-1", invocation().copy(usageEvidenceJson = "malformed")))
    }

    @Test fun explicitAdmissionAndUncertainMutationFactsRemainIndependent() {
        val precheck = failure.copy(dispatched = false, remoteAcceptance = RemoteRequestAcceptance.NO, phase = "provider_admission")
        val retained = requireNotNull(AgentFailureEvidence.reconcile(precheck, "run-1", invocation()))
        assertEquals(false, retained.dispatched)
        assertEquals(RemoteRequestAcceptance.NO, retained.remoteAcceptance)
        assertEquals("provider_admission", retained.phase)
        val uncertain = failure.copy(category = "TOOL_OUTCOME_UNKNOWN", dispatched = false, remoteAcceptance = RemoteRequestAcceptance.UNKNOWN)
        val terminal = AgentRunTerminalDiagnostics.from(AgentRunStatus.PARTIAL, AgentFailureEvidence.reconcile(uncertain, "run-1", invocation()))
        assertEquals("UNKNOWN_OUTCOME", terminal.executionState)
        assertEquals(null, terminal.dispatched)
        assertEquals(RemoteRequestAcceptance.UNKNOWN, terminal.remoteAcceptance)
    }

    @Test fun savedFailureAddsItsPhaseAndReplayRestrictionWithoutPromotingUnknownTransport() {
        val stored = failure.copy(dispatched = true, remoteAcceptance = RemoteRequestAcceptance.YES, requestReplayAllowed = false)
        val record = invocation(
            buildJsonObject {
                put("invocation_id", "probe-17")
                put("failure", Json.encodeToJsonElement(ProfileFailureRecord.serializer(), stored))
            }
        )
        val reconciled = requireNotNull(AgentFailureEvidence.reconcile(failure.copy(phase = "stage_not_recorded", requestReplayAllowed = true), "run-1", record))
        assertEquals("provider_generation", reconciled.phase)
        assertEquals(true, reconciled.dispatched)
        assertEquals(false, reconciled.requestReplayAllowed)
        assertEquals(null, reconciled.generationCompleted)
    }

    @Test fun typedBindingRejectionRetainsTheServerStageAndExplicitInferenceFact() {
        val event = requireNotNull(AgentFailureEvidence.exception(GatewayExecutionRejected("EXECUTION_MODEL_MISMATCH", 409, "binding_admission", inferenceAttempted = false)))
        val classified = ProfileFailureRecord.fromProvider(event.message, event.errorCode, event.failureDetails)
        assertEquals("EXECUTION_MODEL_MISMATCH", classified.category)
        assertEquals("binding_admission", classified.phase)
        assertEquals(false, classified.dispatched)
        assertEquals(RemoteRequestAcceptance.NO, classified.remoteAcceptance)
        assertEquals(JsonPrimitive(409), event.failureDetails?.get("http_status"))
        assertEquals(JsonPrimitive(false), event.failureDetails?.get("inference_attempted"))
        assertFalse(classified.retryAllowed)
        val unknown = requireNotNull(AgentFailureEvidence.exception(GatewayExecutionRejected("EXECUTION_MODEL_MISMATCH", 409, "binding_admission")))
        assertEquals(null, unknown.failureDetails?.get("dispatched"))
        assertEquals(null, unknown.failureDetails?.get("remote_acceptance"))
    }

    @Test fun rejectedBindingCannotEraseAnEarlierUncertainCommandOrObservedOutput() {
        val rejected = GatewayExecutionRejected("EXECUTION_BINDING_CHANGED", 409, "binding_admission", inferenceAttempted = false, commandOutcomeUnknown = true)
        val event = requireNotNull(AgentFailureEvidence.exception(rejected))
        assertEquals(GatewayOutcomeRecovery.ERROR_CODE, event.errorCode)
        assertEquals(JsonPrimitive(true), event.failureDetails?.get("requires_reconciliation"))
        assertEquals(JsonPrimitive(false), event.failureDetails?.get("request_replay_allowed"))
        assertEquals(null, event.failureDetails?.get("dispatched"))
        assertFalse(event.transportComplete)
        val partial = requireNotNull(AgentFailureEvidence.exception(GatewayExecutionRejected("EXECUTION_MODEL_MISMATCH", 409, "binding_admission", inferenceAttempted = false), partialOutput = true))
        assertEquals(null, partial.failureDetails?.get("dispatched"))
        assertEquals(null, partial.failureDetails?.get("remote_acceptance"))
    }
}
