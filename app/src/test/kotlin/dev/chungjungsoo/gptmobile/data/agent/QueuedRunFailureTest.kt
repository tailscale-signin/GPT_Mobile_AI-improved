package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.ReasoningAdmissionException
import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionSurface
import dev.chungjungsoo.gptmobile.data.github.gatewayAdmissionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class QueuedRunFailureTest {
    @Test fun identityAdmissionDenialRetainsSpecificRepairWithoutServerBody() {
        val error = gatewayAdmissionFailure(403, GatewayAdmissionSurface.IDENTITY, "remote_surface_not_admitted", "private body must not be retained")
        val retained = QueuedRunFailure.retained(error)
        val evidence = QueuedRunFailure.evidence(retained)
        assertEquals("GATEWAY_REMOTE_SURFACE_NOT_ADMITTED", evidence.reason)
        assertEquals("UPDATE_GATEWAY", evidence.repair)
        assertEquals("NOT_ACCEPTED", evidence.remoteAcceptance)
        assertEquals(false, evidence.dispatched)
        assertFalse(retained.contains("private body"))
    }

    @Test fun lostCommandReceiptKeepsAcceptanceUnconfirmed() {
        val error = gatewayAdmissionFailure(503, GatewayAdmissionSurface.COMMAND, null)
        val evidence = QueuedRunFailure.evidence(QueuedRunFailure.retained(error))
        assertEquals("UNCONFIRMED", evidence.remoteAcceptance)
        assertEquals(null, evidence.dispatched)
        assertEquals("gateway_recovery", evidence.phase)
        assertEquals("RECONCILE_ORIGINAL_COMMAND", evidence.repair)
    }

    @Test fun typedReasoningFailureRetainsUnsentProofButGenericExceptionDoesNot() {
        val rejected = QueuedRunFailure.evidence(QueuedRunFailure.retained(ReasoningAdmissionException(ReasoningMode.OFF)))
        assertEquals("REASONING_OFF_UNSUPPORTED", rejected.reason)
        assertEquals(false, rejected.dispatched)
        val unknown = QueuedRunFailure.evidence(QueuedRunFailure.retained(IllegalStateException("private exception text")))
        assertEquals(null, unknown.dispatched)
        assertEquals("UNCONFIRMED", unknown.remoteAcceptance)
    }
}
