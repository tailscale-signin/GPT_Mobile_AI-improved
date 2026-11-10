package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionException
import dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionReason
import dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayPreflightFailureTest {
    private val message = MessageV2(content = "", platformType = "LLAMA")
    private val failure = GatewayAdmissionException(GatewayAdmissionReason.ENGINEERING_GITHUB_REQUIRED, 200, null, GatewayAdmissionSurface.CAPABILITIES)

    @Test fun freshRunRetainsTypedPrerequisiteAndKnownUnsentEvidenceInItsTerminalNotice() {
        val typed = requireNotNull(coordinatorFailureRecord(message, failure, gatewayPreflightKnownUnsent = true))
        assertEquals("GATEWAY_ENGINEERING_GITHUB_REQUIRED", typed.category)
        assertEquals(false, typed.dispatched)
        assertEquals(RemoteRequestAcceptance.NO, typed.remoteAcceptance)
        assertTrue(typed.isUnsentSetup)
        assertFalse(typed.retryAllowed)
        val terminal = terminalAgentMessage(message, failure.message, 10, typed)
        assertEquals(typed, terminal.timeline.single().profileFailure)
        assertEquals(failure.message, terminal.timeline.single().runError)
    }

    @Test fun aPrecheckWithUnverifiedPriorExecutionCannotClaimTheWholeRunUnsent() {
        val noBoundaryProof = requireNotNull(coordinatorFailureRecord(message, failure, gatewayPreflightKnownUnsent = false))
        assertNull(noBoundaryProof.dispatched)
        assertEquals(RemoteRequestAcceptance.UNKNOWN, noBoundaryProof.remoteAcceptance)
        assertFalse(noBoundaryProof.isUnsentSetup)
        val partial = requireNotNull(coordinatorFailureRecord(message.copy(content = "Saved answer"), failure, gatewayPreflightKnownUnsent = true))
        assertNull(partial.dispatched)
        assertEquals(RemoteRequestAcceptance.UNKNOWN, partial.remoteAcceptance)
    }

    @Test fun laterEngineeringPreflightPreservesAnEarlierUnknownRemoteOutcome() {
        val prior = ProfileFailureRecord.classify("GATEWAY_OUTCOME_UNKNOWN: original command must be reconciled")
        val retained = message.copy(timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.NOTICE, profileFailure = prior)))
        val typed = requireNotNull(coordinatorFailureRecord(retained, failure, gatewayPreflightKnownUnsent = true))
        assertEquals("GATEWAY_OUTCOME_UNKNOWN", typed.category)
        assertTrue(typed.dispatched == true)
        assertEquals(RemoteRequestAcceptance.UNKNOWN, typed.remoteAcceptance)
        assertFalse(typed.retryAllowed)
    }
}
