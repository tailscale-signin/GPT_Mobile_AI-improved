package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogEntry
import dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy
import dev.chungjungsoo.gptmobile.data.diagnostics.structuredDiagnosticEventMessage
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewaySessionDiagnosticsTest {
    private fun project(event: String, tag: String, fields: Map<String, Any?>, level: String = "I") = requireNotNull(gatewayAppDiagnosticProjection(AppLogEntry(1000, level, tag, structuredDiagnosticEventMessage(event, fields))))
    private fun JsonObject.attributes() = this["bounded_attributes"] as JsonObject

    @Test fun terminalOutcomesAndAcceptanceStayIndependentOfSeverity() {
        for (status in listOf("COMPLETED", "FAILED", "BLOCKED", "PARTIAL", "EVIDENCE_MISSING", "CANCELLED")) {
            val event = project("RUN_TERMINAL", "Agent", mapOf("run_id" to "run:example", "status" to status, "errorCode" to "GATEWAY_ENGINEERING_GITHUB_REQUIRED", "failedStage" to "verification", "requiredCriteria" to 5, "verifiedCriteria" to 2, "completionDisposition" to "PARTIAL", "failureMessage" to "private-canary"))
            val roundTrip = requireNotNull(GatewayDiagnosticEvent.create("a".repeat(32), "b".repeat(32), 1, 1000, event))
            val imported = requireNotNull(GatewayDiagnosticEvent.parse(roundTrip.toString()))
            assertEquals(status, imported.attributes().diagnosticText("terminal_status"))
            assertEquals("GATEWAY_ENGINEERING_GITHUB_REQUIRED", imported.attributes().diagnosticText("reason_code"))
            assertEquals("verification", imported.attributes().diagnosticText("failed_stage"))
            assertEquals(3L, imported.attributes().diagnosticLong("remaining_criteria"))
            assertEquals(JsonPrimitive(false), imported.attributes()["acceptance_verified"])
            assertNotNull(imported["run_id"])
            assertFalse(imported.toString().contains("private-canary"))
        }
    }

    @Test fun realRecorderFieldsKeepMeasurementsAndSeparateSampleLossFromJank() {
        fun frame(max: Double, missed: Int, lost: Int) = project("FRAME_TIMING", "Rendering", mapOf("frames" to 100, "jankFrames" to missed, "droppedSamples" to lost, "maxMs" to max, "p95UpperMs" to 16.0, "frameBudgetMs" to 8.333, "windowMs" to 5000, "screen" to "private-screen"))
        val loss = frame(10.0, 0, 4)
        assertEquals("RENDERER_MEASUREMENT_LOSS", loss.diagnosticText("error_code"))
        assertEquals(100L, loss.attributes().diagnosticLong("frame_count"))
        assertEquals(4L, loss.attributes().diagnosticLong("dropped_samples"))
        assertEquals(10_000_000L, loss.attributes().diagnosticLong("maximum_frame_ns"))
        assertEquals(8_333_000L, loss.attributes().diagnosticLong("frame_budget_ns"))
        assertNull(loss.attributes()["dropped_frames"])
        assertFalse(loss.toString().contains("private-screen"))
        assertEquals("RENDERER_JANK", frame(20.0, 5, 0).diagnosticText("error_code"))
        assertEquals("RENDERER_STALL", frame(120.0, 5, 0).diagnosticText("error_code"))
        assertEquals("NONE", frame(8.0, 0, 0).diagnosticText("error_code"))
    }

    @Test fun mcpDiscoveryFailureRetainsServerAndPhaseWithoutImplyingExecution() {
        val event = project("Health", "MCP", mapOf("connection" to "private-server", "failedStage" to "discovery", "errorCode" to "MCP_DISCOVERY_FAILED", "operation_id" to "mcp:operation:one"), "W")
        assertEquals("mcp_discovery", event.diagnosticText("stage"))
        assertEquals("MCP_DISCOVERY_FAILED", event.diagnosticText("error_code"))
        assertEquals("discovery", event.attributes().diagnosticText("mcp_phase"))
        assertNotNull(event.attributes()["server_digest"])
        assertNotNull(event["operation_id"])
        assertFalse(event.toString().contains("private-server"))
    }

    @Test fun unpreparedGatewayRequestCorrelatesWithoutMetadataUploadOrEchoHeaders() {
        val old = LocalDiagnosticsPolicy.enabled
        LocalDiagnosticsPolicy.setEnabled(true)
        try {
            val config = ProviderRequestConfig("https://private-gateway.example/v1", null)
            val headers = GatewayClientDiagnostics.requestHeaders(config, "capabilities", knownGateway = true)
            assertFalse(headers.containsKey(GatewayClientDiagnostics.CLIENT_HEADER))
            assertFalse(headers.containsKey("X-Gateway-Diagnostic-Session"))
            val event = GatewayClientDiagnostics.responseFields(config, 503, headers["traceparent"]) { null }
            assertEquals("capabilities", event.attributes().diagnosticText("route"))
            assertEquals("matched", event.attributes().diagnosticText("correlation_status"))
            assertEquals("NOT_REQUESTED", event.attributes().diagnosticText("client_metadata_acknowledgment"))
            assertNotNull(event["local_duration_ms"])
            assertNotNull(event["request_id"])
            assertFalse(event.toString().contains("private-gateway"))
            GatewayClientDiagnostics.finished(headers)
            assertEquals("response_only", GatewayClientDiagnostics.responseFields(config, 503) { null }.attributes().diagnosticText("correlation_status"))
        } finally {
            LocalDiagnosticsPolicy.setEnabled(old)
        }
    }

    @Test fun deadlineAndObserverCancellationHaveDistinctCausesAndNoWriteReplay() = runBlocking {
        val old = LocalDiagnosticsPolicy.enabled
        LocalDiagnosticsPolicy.setEnabled(true)
        try {
            val config = ProviderRequestConfig("https://gateway.example/v1", null)
            val deadline = try {
                withTimeout(1) { delay(1000) }
                error("deadline expected")
            } catch (failure: CancellationException) {
                failure
            }
            val timeout = requireNotNull(GatewayClientDiagnostics.failureFields(config, GatewayClientDiagnostics.requestHeaders(config, "job", "POST", knownGateway = true), deadline))
            assertEquals("TIMEOUT", timeout.diagnosticText("error_code"))
            assertEquals("deadline", timeout.attributes().diagnosticText("cancellation_origin"))
            assertEquals("reconcile", timeout.diagnosticText("retry_class"))
            val detached = requireNotNull(GatewayClientDiagnostics.failureFields(config, GatewayClientDiagnostics.requestHeaders(config, "job", knownGateway = true), GatewayObserverCancellation("observer_detached")))
            assertEquals("CANCELLED", detached.diagnosticText("error_code"))
            assertEquals("observer_detached", detached.attributes().diagnosticText("cancellation_origin"))
            assertEquals("none", detached.diagnosticText("retry_class"))
            assertTrue(detached.attributes().containsKey("endpoint_digest"))
        } finally {
            LocalDiagnosticsPolicy.setEnabled(old)
        }
    }
}
