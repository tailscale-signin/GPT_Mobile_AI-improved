package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayTransportDiagnosticsTest {
    private val trace = "1".repeat(32)
    private val config = ProviderRequestConfig(
        "https://private.example/v1",
        "private credential",
        extraHeaders = mapOf(
            GatewayClientDiagnostics.CLIENT_HEADER to "{\"schemaVersion\":1}",
            "traceparent" to "00-$trace-${"2".repeat(16)}-01",
            "X-Request-ID" to trace,
            "X-Api-Key" to "private key"
        )
    )

    private fun capture(block: () -> Unit) {
        val original = LocalDiagnosticsPolicy.enabled
        try {
            LocalDiagnosticsPolicy.setEnabled(true)
            block()
        } finally {
            GatewayClientDiagnostics.pause()
            LocalDiagnosticsPolicy.setEnabled(original)
        }
    }

    @Test fun failureBeforeHeadersKeepsRequestTraceDurationAndBoundedRouteExactlyOnce() = capture {
        val headers = GatewayClientDiagnostics.requestHeaders(config, "dashboard")
        val event = requireNotNull(GatewayClientDiagnostics.failureFields(config, headers, SocketException("Broken pipe private credential"), safeRead = true))
        assertEquals(trace, event.diagnosticText("trace_id"))
        assertEquals(headers.getValue("traceparent").split('-')[2], event.diagnosticText("parent_span_id"))
        assertEquals(researchHash(trace), event.diagnosticText("request_id"))
        assertEquals("TRANSPORT_FAILURE", event.diagnosticText("error_code"))
        assertEquals("unknown", event.diagnosticText("dispatch_state"))
        assertEquals("safe_read", event.diagnosticText("retry_class"))
        assertNotNull(event.diagnosticLong("local_duration_ms"))
        val attributes = event["bounded_attributes"] as JsonObject
        assertEquals("dashboard", attributes.diagnosticText("route"))
        assertEquals("GET", attributes.diagnosticText("method"))
        assertEquals("false", attributes["response_headers_received"].toString())
        assertFalse(event.toString().contains("private"))
        assertNull(GatewayClientDiagnostics.failureFields(config, headers, EOFException(), safeRead = true))
    }

    @Test fun unknownWriteTimeoutRequiresReconciliationAndCancellationNeverRequestsRetry() = capture {
        val headers = GatewayClientDiagnostics.requestHeaders(config, "job", "POST")
        val timeout = requireNotNull(GatewayClientDiagnostics.failureFields(config, headers, SocketTimeoutException()))
        assertEquals("TIMEOUT", timeout.diagnosticText("error_code"))
        assertEquals("reconcile", timeout.diagnosticText("retry_class"))
        val cancelled = requireNotNull(GatewayClientDiagnostics.failureFields(config, GatewayClientDiagnostics.requestHeaders(config, "job"), CancellationException("private cancellation"), safeRead = true))
        assertEquals("cancellation", cancelled.diagnosticText("stage"))
        assertEquals("none", cancelled.diagnosticText("retry_class"))
        assertFalse(cancelled.toString().contains("private"))
    }

    @Test fun responseHeadersDoNotHideLaterBodyEof() = capture {
        val headers = GatewayClientDiagnostics.requestHeaders(config, "engineering")
        val response = mapOf(
            "X-Gateway-Debug-Protocol" to "2",
            "X-Gateway-Debug-Enabled" to "true",
            "X-Gateway-Request-ID" to trace,
            "X-Gateway-Client-Span-ID" to headers.getValue("traceparent").split('-')[2],
            "traceparent" to "00-$trace-${"3".repeat(16)}-01"
        )
        GatewayClientDiagnostics.observe(config, 200) { response[it] }
        val event = requireNotNull(GatewayClientDiagnostics.failureFields(config, headers, EOFException(), safeRead = true))
        assertEquals("true", (event["bounded_attributes"] as JsonObject)["response_headers_received"].toString())
        assertEquals("TRANSPORT_FAILURE", event.diagnosticText("error_code"))
    }

    @Test fun privacyRevocationRemovesOwnedTraceAndRequestIdentityAndSuppressesFailureRecord() = capture {
        val dispatched = GatewayClientDiagnostics.requestHeaders(config, "dashboard")
        LocalDiagnosticsPolicy.setEnabled(false)
        assertEquals(mapOf("X-Api-Key" to "private key"), GatewayClientDiagnostics.requestHeaders(config))
        assertNull(GatewayClientDiagnostics.failureFields(config, dispatched, SocketException(), safeRead = true))
        val custom = ProviderRequestConfig("https://example.com", null, extraHeaders = mapOf("X-Request-ID" to "user-supplied"))
        assertEquals(custom.extraHeaders, GatewayClientDiagnostics.requestHeaders(custom))
    }

    @Test fun completedExchangeAndValidationErrorsCannotBecomeSocketFailures() = capture {
        val headers = GatewayClientDiagnostics.requestHeaders(config, "capabilities")
        GatewayClientDiagnostics.finished(headers)
        assertNull(GatewayClientDiagnostics.failureFields(config, headers, EOFException(), safeRead = true))
        assertNull(GatewayClientDiagnostics.failureFields(config, GatewayClientDiagnostics.requestHeaders(config, "capabilities"), IllegalArgumentException("Invalid schema"), safeRead = true))
        assertNull(GatewayClientDiagnostics.failureFields(config, GatewayClientDiagnostics.requestHeaders(config, "engineering"), dev.chungjungsoo.gptmobile.data.network.ProviderHttpFailure(422, null), safeRead = true))
        val tls = requireNotNull(GatewayClientDiagnostics.failureFields(config, GatewayClientDiagnostics.requestHeaders(config, "capabilities"), javax.net.ssl.SSLHandshakeException("private certificate"), safeRead = true))
        assertEquals("none", tls.diagnosticText("retry_class"))
    }

    @Test fun arbitraryRouteAndMethodNeverSurviveProjection() = capture {
        val fields = requireNotNull(GatewayClientDiagnostics.failureFields(config, GatewayClientDiagnostics.requestHeaders(config, "/private/repository", "private method"), EOFException(), safeRead = true))
        assertFalse(fields.toString().contains("private"))
        val attributes = fields["bounded_attributes"] as JsonObject
        assertEquals("other", attributes.diagnosticText("route"))
        assertTrue(GatewayDiagnosticEvent.methods.contains(attributes.diagnosticText("method")))
    }
}
