package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRunTerminalDiagnosticsTest {
    @Test fun completedResponseDoesNotClaimVerifiedObjectiveCompletion() {
        val result = AgentRunTerminalDiagnostics.from(AgentRunStatus.COMPLETED, null)
        assertEquals("RESPONSE_FLOW_COMPLETED", result.reason)
        assertEquals("COMPLETED", result.executionState)
        assertEquals("NOT_MEASURED", result.taskVerification)
        assertEquals("none", result.stage)
    }

    @Test fun rejectedDispatchIsDistinguishedFromFailedDispatch() {
        val rejected = AgentRunTerminalDiagnostics.from(AgentRunStatus.FAILED, ProfileFailureRecord("DNS_RESOLUTION_FAILED", true, dispatched = false, phase = "dns"))
        val failed = AgentRunTerminalDiagnostics.from(AgentRunStatus.FAILED, ProfileFailureRecord("CONNECTION", true, dispatched = true, phase = "response_stream"))
        assertEquals("PRECHECK_REJECTED", rejected.executionState)
        assertEquals("dns", rejected.stage)
        assertEquals("FAILED", failed.executionState)
        assertEquals("response_stream", failed.stage)
    }

    @Test fun uncertainPreviousWriteStaysUnknownEvenWhenRecoveryDidNotRedispatch() {
        val result = AgentRunTerminalDiagnostics.from(AgentRunStatus.PARTIAL, ProfileFailureRecord("TOOL_OUTCOME_UNKNOWN", false, dispatched = false, phase = "tool_dispatch"))
        assertEquals("UNKNOWN_OUTCOME", result.executionState)
        assertEquals("tool_dispatch", result.stage)
        assertEquals("NOT_MEASURED", result.taskVerification)
        assertEquals(null, result.dispatched)
        assertEquals(RemoteRequestAcceptance.UNKNOWN, result.remoteAcceptance)
    }

    @Test fun rejectedAttemptKeepsItsPhysicalIdentityAndRemoteAcceptanceEvidence() {
        val failure = ProfileFailureRecord("REASONING_OFF_UNSUPPORTED", false, dispatched = false, remoteAcceptance = RemoteRequestAcceptance.NO, phase = "provider_admission", invocationId = "invocation-one")
        val result = AgentRunTerminalDiagnostics.from(AgentRunStatus.FAILED, failure)
        assertEquals(false, result.dispatched)
        assertEquals(RemoteRequestAcceptance.NO, result.remoteAcceptance)
        assertEquals("invocation-one", result.invocationId)
        val completed = AgentRunTerminalDiagnostics.from(AgentRunStatus.COMPLETED, failure)
        assertEquals(null, completed.dispatched)
        assertEquals(null, completed.invocationId)
    }

    @Test fun absentStageEvidenceIsNeverPresentedAsAnObservedProviderPhase() {
        val unclassified = ProfileFailureRecord.fromProvider("The task stopped without a stage receipt")
        assertEquals("stage_not_recorded", unclassified.phase)
        assertEquals("stage_not_recorded", AgentRunTerminalDiagnostics.from(AgentRunStatus.FAILED, null).stage)
    }

    @Test fun retainedMissingAcceptanceIdentifiesItsOwnStageWithoutInventingDispatch() {
        val acceptance = dev.chungjungsoo.gptmobile.data.github.GitHubRunAcceptance.Assessment("PARTIAL", "receipt", 3, 0, 0)
        val result = AgentRunTerminalDiagnostics.from(AgentRunStatus.PARTIAL, ProfileFailureRecord("INCOMPLETE", true), acceptance)
        assertEquals("GITHUB_EVIDENCE_MISSING", result.reason)
        assertEquals("task_acceptance", result.stage)
        assertEquals("PARTIAL", result.taskVerification)
        assertEquals(null, result.dispatched)
        assertEquals(RemoteRequestAcceptance.UNKNOWN, result.remoteAcceptance)
    }

    @Test fun extractionFailuresKeepStageAndPointToSmallerSavedBatch() {
        val failure = ProfileFailureRecord("GITHUB_EXTRACTION_OUTPUT_LIMIT", false, dispatched = true, phase = "claim_extraction", invocationId = "extract-4")
        val result = AgentRunTerminalDiagnostics.from(AgentRunStatus.PARTIAL, failure)
        assertEquals("GITHUB_EXTRACTION_OUTPUT_LIMIT", result.reason)
        assertEquals("claim_extraction", result.stage)
        assertEquals("extract-4", result.invocationId)
        assertEquals("RESUME_SAVED_BATCH_WITH_SMALLER_CLAIM_LIMIT", result.recoveryAction)
    }

    @Test fun waitingApprovalRetainsIndependentApprovalStateAndRecoveryAction() {
        val acceptance = dev.chungjungsoo.gptmobile.data.github.GitHubRunAcceptance.Assessment(
            verification = "WAITING_FOR_APPROVAL",
            evidenceId = "receipt",
            requiredCriteria = 2,
            verifiedCriteria = 1,
            unresolvedOutcomes = 0,
            taskState = "WAITING_FOR_APPROVAL",
            stopCode = "NATIVE_APPROVAL_REQUIRED",
            approvalState = "PENDING",
            taskOutcome = "WAITING_FOR_APPROVAL"
        )
        val result = AgentRunTerminalDiagnostics.from(
            AgentRunStatus.WAITING_FOR_APPROVAL,
            ProfileFailureRecord("WAITING_FOR_APPROVAL", false, phase = "tool_continuation"),
            acceptance
        )
        assertEquals("WAITING_FOR_APPROVAL", result.taskState)
        assertEquals("WAITING_FOR_APPROVAL", result.executionState)
        assertEquals("NATIVE_APPROVAL_REQUIRED", result.stopCode)
        assertEquals("PENDING", result.approvalState)
        assertEquals("WAITING_FOR_APPROVAL", result.taskOutcome)
        assertEquals("REVIEW_EXISTING_APPROVAL_CARD", result.recoveryAction)
    }

    @Test fun exactUserRejectionHasItsOwnTerminalStateAndCleanupAction() {
        val acceptance = dev.chungjungsoo.gptmobile.data.github.GitHubRunAcceptance.Assessment(
            verification = "REJECTED",
            evidenceId = "receipt",
            requiredCriteria = 2,
            verifiedCriteria = 1,
            unresolvedOutcomes = 0,
            taskState = "REJECTED",
            stopCode = "APPROVAL_REJECTED",
            approvalState = "REJECTED",
            taskOutcome = "REJECTED"
        )
        val result = AgentRunTerminalDiagnostics.from(AgentRunStatus.PARTIAL, null, acceptance)
        assertEquals("REJECTED", result.executionState)
        assertEquals("REJECTED", result.taskVerification)
        assertEquals("APPROVAL_REJECTED", result.reason)
        assertEquals("RECONCILE_FIXTURE_AFTER_APPROVAL_REJECTION", result.recoveryAction)
        assertEquals("REJECTED", result.taskState)
        assertEquals("REJECTED", result.approvalState)
    }
}
