package dev.chungjungsoo.gptmobile.data.network

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResilientStreamingClientTest {
    @Test fun provider429HonorsRetryAfterAndStopsAfterThreeInteractiveAttempts() = kotlinx.coroutines.test.runTest {
        var attempts = 0
        val waits = mutableListOf<Long>()
        val failure = runCatching {
            ResilientStreamingClient.executeWithRetry(
                ResilientStreamingClient.RetryConfig(maxAttempts = 3, initialDelayMs = 250, maxDelayMs = 1000, jitterRatio = 0.0),
                onRetry = { _, wait, _ -> waits += wait }
            ) {
                attempts++
                throw ProviderHttpFailure(429, 2_000, knownNotAccepted = true)
            }
        }.exceptionOrNull()
        org.junit.Assert.assertEquals(3, attempts)
        org.junit.Assert.assertEquals(listOf(5_000L, 10_000L), waits)
        assertTrue(failure is ProviderHttpFailure)
    }

    @Test fun durable429ReturnsControlBeforeAnyTransportRetry() = kotlinx.coroutines.test.runTest {
        var attempts = 0
        val rejection = ProviderHttpFailure(429, 30_000, knownNotAccepted = true)
        val failure = runCatching {
            kotlinx.coroutines.withContext(ProviderWaitContext(durableRateLimits = true) {}) {
                ResilientStreamingClient.executeWithRetry {
                    attempts++
                    throw rejection
                }
            }
        }.exceptionOrNull()
        org.junit.Assert.assertSame(rejection, failure)
        org.junit.Assert.assertEquals(1, attempts)
    }

    @Test fun deterministicTemplateFailuresNeverDispatchASecondAttempt() = kotlinx.coroutines.test.runTest {
        for (message in listOf("HTTP 500: Jinja Exception: System message must be at the beginning", "HTTP 502: backend_template_invalid", "HTTP 409: client_tool_results_required", "HTTP 404: gateway_job_unavailable", "HTTP 422: unsupported_instruction_content", "HTTP 422: invalid_tool_history", "HTTP 409: client_tool_result_identity_mismatch", "HTTP 502: completion_repair_budget_unavailable", "HTTP 502: completion_repair_usage_unavailable")) {
            var attempts = 0
            val failure = runCatching {
                ResilientStreamingClient.executeWithRetry(ResilientStreamingClient.RetryConfig(maxAttempts = 3)) {
                    attempts++
                    throw IOException(message)
                }
            }.exceptionOrNull()
            org.junit.Assert.assertEquals(1, attempts)
            assertTrue(failure is IOException)
        }
    }

    @Test fun wrappedSerializationFailuresNeverEnterTransportRetry() {
        assertFalse(ResilientStreamingClient.isRetryable(IOException("wrapped", kotlinx.serialization.SerializationException("Serializer for subclass TreeMap is not found"))))
    }

    @Test
    fun `gateway contract repair exhaustion never starts a transport retry`() {
        for (code in listOf("missing_required_tool_call", "completion_repair_budget_exhausted", "terminal_job_not_resumable", "invalid_v14_request", "Backend omitted the required tool call.")) {
            assertFalse(ResilientStreamingClient.isRetryable(IOException("HTTP 502: $code")))
        }
        assertTrue(ResilientStreamingClient.isRetryable(IOException("HTTP 502: backend_stream_interrupted")))
        assertFalse(ResilientStreamingClient.providerAllowsRetry("""{"error":{"code":"new_contract_error","retryable":false}}"""))
        assertFalse(ResilientStreamingClient.providerAllowsRetry("""{"error":{"message":"Backend omitted the required tool call."}}"""))
        assertTrue(ResilientStreamingClient.providerAllowsRetry("""{"error":{"code":"backend_timeout","retryable":true}}"""))
    }

    @Test fun `definitive uncommon HTTP failures never fall through to generic IO retries`() {
        assertFalse(ResilientStreamingClient.isRetryable(IOException("HTTP 505 version unsupported")))
        assertFalse(ResilientStreamingClient.isRetryable(IOException("HTTP 451 unavailable")))
        assertTrue(ResilientStreamingClient.isRetryable(IOException("HTTP 504 timeout")))
    }

    @Test
    fun `TLS record errors permit bounded retry but trust failures never do`() {
        assertTrue(ResilientStreamingClient.isRetryable(javax.net.ssl.SSLProtocolException("SSLV3_ALERT_BAD_RECORD_MAC")))
        assertFalse(ResilientStreamingClient.isRetryable(IOException("wrapped", javax.net.ssl.SSLPeerUnverifiedException("hostname mismatch"))))
        assertFalse(ResilientStreamingClient.isRetryable(IOException("wrapped", java.security.cert.CertificateException("expired"))))
    }

    @Test
    fun `premature Ktor HTTP close is classified through wrapped cause`() {
        val error = IllegalStateException(
            "stream failed",
            IOException("Failed to parse HTTP response: the server prematurely closed the connection")
        )

        assertTrue(ResilientStreamingClient.isPrematureConnectionClose(error))
        assertTrue(ResilientStreamingClient.isRetryable(error))
    }

    @Test
    fun `partial payload never proves the stream completed`() {
        val error = IOException("unexpected end of stream")

        assertFalse(
            ResilientStreamingClient.shouldTreatPrematureCloseAsStreamEnd(
                receivedPayload = false,
                throwable = error
            )
        )
        assertFalse(
            ResilientStreamingClient.shouldTreatPrematureCloseAsStreamEnd(
                receivedPayload = true,
                throwable = error
            )
        )
        assertTrue(ResilientStreamingClient.shouldTreatPrematureCloseAsStreamEnd(true, error, completed = true))
    }

    @Test
    fun `deterministic invalid requests are not retried even when wrapped as IO failures`() {
        assertFalse(ResilientStreamingClient.isRetryable(IOException("REQUEST_INVALID: unsupported parameter")))
        assertFalse(ResilientStreamingClient.isRetryable(IOException("HTTP 400 invalid request")))
        assertFalse(ResilientStreamingClient.isRetryable(IllegalArgumentException("HTTP 422 invalid request")))
        assertTrue(ResilientStreamingClient.isRetryable(IOException("HTTP 500 internal server error")))
        assertTrue(ResilientStreamingClient.isRetryable(IOException("HTTP 502 bad gateway")))
        assertFalse(ResilientStreamingClient.isRetryable(IOException("HTTP 501 not implemented")))
        assertTrue(ResilientStreamingClient.isRetryable(IOException("HTTP 429 rate limit")))
    }

    @Test
    fun `ordinary protocol errors are not mistaken for premature close`() {
        val error = IllegalArgumentException("HTTP 400 invalid request")

        assertFalse(ResilientStreamingClient.isPrematureConnectionClose(error))
        assertFalse(
            ResilientStreamingClient.shouldTreatPrematureCloseAsStreamEnd(
                receivedPayload = true,
                throwable = error
            )
        )
    }
}
