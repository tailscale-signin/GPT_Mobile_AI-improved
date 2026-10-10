package dev.chungjungsoo.gptmobile.data.network

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResilientStreamingClientTest {
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
