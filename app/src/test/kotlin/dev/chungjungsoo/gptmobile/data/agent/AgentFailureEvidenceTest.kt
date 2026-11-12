package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.accounting.ModelInvocation
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.github.GatewayExecutionRejected
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentFailureEvidenceTest {
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
