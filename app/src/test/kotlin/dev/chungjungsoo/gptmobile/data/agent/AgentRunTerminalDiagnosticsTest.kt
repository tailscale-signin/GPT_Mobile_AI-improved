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
    }

    @Test fun absentStageEvidenceIsNeverPresentedAsAnObservedProviderPhase() {
        val unclassified = ProfileFailureRecord.fromProvider("The task stopped without a stage receipt")
        assertEquals("stage_not_recorded", unclassified.phase)
        assertEquals("stage_not_recorded", AgentRunTerminalDiagnostics.from(AgentRunStatus.FAILED, null).stage)
    }
}
