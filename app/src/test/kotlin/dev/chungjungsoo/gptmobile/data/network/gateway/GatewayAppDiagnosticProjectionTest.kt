package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogEntry
import dev.chungjungsoo.gptmobile.data.diagnostics.structuredDiagnosticEventMessage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayAppDiagnosticProjectionTest {
    private val run = "11111111-1111-1111-1111-111111111111"
    private val invocation = "22222222-2222-2222-2222-222222222222"

    @Test fun typedTerminalRetainsCorrelationAndUnsentEvidenceWithoutForwardingMessages() {
        val entry = AppLogEntry(
            1000,
            "E",
            "AgentRun",
            structuredDiagnosticEventMessage(
                "RUN_TERMINAL",
                mapOf(
                    "runId" to run,
                    "invocationId" to invocation,
                    "causalIncidentId" to null,
                    "status" to "FAILED",
                    "executionState" to "PRECHECK_REJECTED",
                    "dispatched" to false,
                    "errorCode" to "GATEWAY_ENGINEERING_GITHUB_REQUIRED",
                    "failureMessage" to "private-failure-canary",
                    "retryable" to false
                )
            )
        )
        val result = requireNotNull(gatewayAppDiagnosticProjection(entry))
        assertEquals(JsonPrimitive(run), result["run_id"])
        assertEquals(JsonPrimitive(invocation), result["incident_id"])
        assertEquals(JsonPrimitive("terminal_delivery"), result["stage"])
        assertEquals(JsonPrimitive("PRECHECK_REJECTED"), result["error_code"])
        assertEquals(JsonPrimitive("not_dispatched"), result["dispatch_state"])
        assertEquals(JsonPrimitive("manual"), result["retry_class"])
        assertFalse(result.toString().contains("private-failure-canary"))
        assertFalse(result.containsKey("failureMessage"))
    }

    @Test fun earlierUnknownOutcomeRequiresReconciliationEvenWithLaterUnsentAttempt() {
        val result = requireNotNull(
            gatewayAppDiagnosticProjection(
                AppLogEntry(
                    1000,
                    "E",
                    "AgentRun",
                    structuredDiagnosticEventMessage("RUN_TERMINAL", mapOf("runId" to run, "status" to "FAILED", "executionState" to "UNKNOWN_OUTCOME", "dispatched" to false))
                )
            )
        )
        assertEquals(JsonPrimitive("DISPATCH_UNCERTAIN"), result["error_code"])
        assertEquals(JsonPrimitive("unknown"), result["dispatch_state"])
        assertEquals(JsonPrimitive("reconcile"), result["retry_class"])
    }

    @Test fun legacyEventsStillProjectButExchangeAndOpaqueIdentifiersStayLocal() {
        val result = requireNotNull(gatewayAppDiagnosticProjection(AppLogEntry(1000, "E", "Tool", "FAILED · runId=$run · operationId=private-label-canary · errorCode=NETWORK_DNS")))
        assertEquals(JsonPrimitive(run), result["run_id"])
        assertFalse(result.toString().contains("private-label-canary"))
        assertNull(gatewayAppDiagnosticProjection(AppLogEntry(1000, "W", "Gateway", "CLIENT_EXCHANGE · status=503")))
        assertNull(gatewayAppDiagnosticProjection(AppLogEntry(1000, "W", "Gateway", structuredDiagnosticEventMessage("CLIENT_EXCHANGE", mapOf("status" to 503)))))
    }
}
