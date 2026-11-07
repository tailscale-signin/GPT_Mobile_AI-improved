package dev.chungjungsoo.gptmobile.data.network

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalServiceHealthTest {
    @org.junit.Test fun phaseTimeoutsAreDistinguishedFromSocketConnectionTimeouts() {
        assertEquals(ConnectionHealth.MCP_HANDSHAKE_FAILED, LocalServiceHealth.classify(IllegalStateException("MCP_INITIALIZATION_TIMEOUT: initialization timed out")))
        assertEquals(ConnectionHealth.STREAM_IDLE_TIMEOUT, LocalServiceHealth.classify(SocketTimeoutException("Read timed out")))
        assertEquals(ConnectionHealth.CONNECT_TIMEOUT, LocalServiceHealth.classify(SocketTimeoutException("Connect timed out")))
        assertEquals(ConnectionHealth.REQUEST_TIMEOUT, LocalServiceHealth.classify(dev.chungjungsoo.gptmobile.data.agent.tool.McpRequestTimeoutException("tools/list", 100)))
    }

    @Test fun wrappedTlsFailureCannotBeMisclassifiedAsAnMcpHandshakeOrTimeout() {
        val failure = java.io.IOException("MCP initialization timed out", javax.net.ssl.SSLHandshakeException("Certificate verification failed"))
        assertEquals(ConnectionHealth.TLS_FAILED, LocalServiceHealth.classify(failure))
        assertEquals(ConnectionHealth.TLS_FAILED, LocalServiceHealth.classify(java.security.cert.CertificateException("Certificate expired")))
    }

    @Test fun plainEofRemainsAnInterruptedStreamWhileAnSslWrappedEofKeepsTheTlsPolicy() {
        val eof = java.io.EOFException("unexpected end of stream")
        assertEquals(ConnectionHealth.STREAM_INTERRUPTED, LocalServiceHealth.classify(eof))
        val tls = javax.net.ssl.SSLException("Secure stream ended", eof)
        assertEquals(ConnectionHealth.TLS_FAILED, LocalServiceHealth.classify(java.io.IOException("SDK wrapper", tls)))
    }

    @Test fun recoveryCoordinationAndIntentionalCancellationDoNotCreateEndpointFailures() {
        val url = "https://recovery.example/mcp"
        LocalServiceHealth.recordFailure(url, IllegalStateException("DNS_RECOVERY: probe already owned"))
        LocalServiceHealth.recordFailure(url, kotlinx.coroutines.CancellationException("navigation"))
        assertEquals(0L, LocalServiceHealth.retryAfterMillis(url))
        assertEquals(ConnectionHealth.UNKNOWN, LocalServiceHealth.health(url))
        assertEquals(ConnectionHealth.STREAM_IDLE_TIMEOUT, LocalServiceHealth.classify(dev.chungjungsoo.gptmobile.data.agent.tool.McpStreamIdleTimeoutException(100)))
    }

    @Before fun reset() = LocalServiceHealth.networkChanged()

    @After fun clear() = LocalServiceHealth.networkChanged()

    @Test fun rejectedCooldownDoesNotExtendTheConnectionFailureWindow() {
        val url = "http://100.64.0.2:8080/v1"
        LocalServiceHealth.recordLocalFailure(url, SocketTimeoutException())
        val before = LocalServiceHealth.retryAfterMillis(url)
        val failure = assertThrows(java.io.IOException::class.java) { LocalServiceHealth.requireAvailable(url) }
        LocalServiceHealth.recordLocalFailure(url, failure)
        val after = LocalServiceHealth.retryAfterMillis(url)
        assertTrue(before > 0)
        assertTrue(after <= before)
        assertTrue(failure.message.orEmpty().contains("retryAfterMs="))
        LocalServiceHealth.checkAgain(url)
        assertEquals(0L, LocalServiceHealth.retryAfterMillis(url))
    }

    @Test fun wrappedCooldownRejectionDoesNotCreateAnotherTransportAttempt() {
        val url = "http://100.64.0.2:8080/v1"
        LocalServiceHealth.recordLocalFailure(url, java.net.ConnectException("Connection refused"))
        val originalWindow = LocalServiceHealth.retryAfterMillis(url)
        val rejection = assertThrows(java.io.IOException::class.java) { LocalServiceHealth.requireAvailable(url) }
        val wrapped = IllegalStateException("Gateway discovery failed", java.io.IOException("client wrapper", rejection))

        repeat(4) { LocalServiceHealth.recordLocalFailure(url, wrapped) }

        assertTrue(LocalServiceHealth.retryAfterMillis(url) <= originalWindow)
        assertEquals(ConnectionHealth.CONNECTION_REFUSED, LocalServiceHealth.health(url))
        LocalServiceHealth.checkAgain(url)
        LocalServiceHealth.requireAvailable(url)
    }

    @Test fun timeoutDoesNotBlockAnotherPortAndHandshakeIsRequiredForHealthy() {
        LocalServiceHealth.recordFailure("http://100.64.0.2:8102/mcp", SocketTimeoutException())
        LocalServiceHealth.requireAvailable("http://100.64.0.2:8101/mcp")
        assertEquals(ConnectionHealth.UNKNOWN, LocalServiceHealth.health("http://100.64.0.2:8101/mcp"))
        assertEquals(ConnectionHealth.CONNECT_TIMEOUT, LocalServiceHealth.health("http://100.64.0.2:8102/mcp"))
        LocalServiceHealth.recordHandshakeSuccess("http://100.64.0.2:8101/mcp")
        assertEquals(ConnectionHealth.HEALTHY, LocalServiceHealth.health("http://100.64.0.2:8101/mcp"))
    }

    @Test fun dnsIsHostScopedAndNetworkChangeInvalidatesCaches() {
        LocalServiceHealth.recordFailure("https://mcp.example.org:8101/mcp", UnknownHostException())
        assertThrows(java.io.IOException::class.java) { LocalServiceHealth.requireAvailable("https://mcp.example.org:8102/mcp") }
        val old = LocalServiceHealth.networkEpoch
        LocalServiceHealth.networkChanged()
        assertTrue(LocalServiceHealth.networkEpoch > old)
        LocalServiceHealth.requireAvailable("https://mcp.example.org:8102/mcp")
    }

    @Test fun localChatFailurePausesOnlyItsEndpointAndExplicitRetryReopensIt() {
        val local = "http://100.64.0.2:8080/v1"
        LocalServiceHealth.recordLocalFailure(local, java.net.ConnectException("Connection refused"))
        assertThrows(java.io.IOException::class.java) { LocalServiceHealth.requireLocalAvailable(local) }
        LocalServiceHealth.requireLocalAvailable("http://100.64.0.2:8101/mcp")
        LocalServiceHealth.checkAgain(local)
        LocalServiceHealth.requireLocalAvailable(local)
        LocalServiceHealth.recordLocalHandshakeSuccess(local)
        assertEquals(ConnectionHealth.HEALTHY, LocalServiceHealth.health(local))
    }

    @Test fun chatCooldownDoesNotTurnAuthenticationCancellationOrPublicFailuresIntoLocalOutages() {
        val local = "http://127.0.0.1:8080/v1"
        LocalServiceHealth.recordLocalFailure(local, IllegalStateException("HTTP 401 unauthorized"))
        LocalServiceHealth.recordLocalFailure(local, kotlinx.coroutines.CancellationException("stop"))
        LocalServiceHealth.requireLocalAvailable(local)
        assertEquals(ConnectionHealth.UNKNOWN, LocalServiceHealth.health(local))
        LocalServiceHealth.recordLocalFailure("https://public.example/v1", java.net.ConnectException("Connection refused"))
        assertEquals(ConnectionHealth.UNKNOWN, LocalServiceHealth.health("https://public.example/v1"))
    }

    @Test fun wrappedSocketAbortRetainsItsTransportCategoryAndWrappedCancellationDoesNotOpenACircuit() {
        assertEquals(ConnectionHealth.STREAM_INTERRUPTED, LocalServiceHealth.classify(java.io.IOException("HTTP read failed", java.net.SocketException("Software caused connection abort"))))
        assertEquals(ConnectionHealth.DNS_FAILED, LocalServiceHealth.classify(java.nio.channels.UnresolvedAddressException()))
        val cancelled = java.io.IOException("connection reset", kotlinx.coroutines.CancellationException("stop"))
        val local = "http://127.0.0.1:8102/mcp"
        LocalServiceHealth.recordFailure(local, cancelled)
        assertEquals(0L, LocalServiceHealth.retryAfterMillis(local))
        assertEquals(ConnectionHealth.UNKNOWN, LocalServiceHealth.classify(cancelled))
    }

    @Test fun closedBeforeResponseIsATransportFailureWithoutOverridingAuthenticationOrCancellation() {
        val interrupted = IllegalStateException("Connection closed before response arrived")
        assertEquals(ConnectionHealth.STREAM_INTERRUPTED, LocalServiceHealth.classify(interrupted))
        assertEquals(ConnectionHealth.STREAM_INTERRUPTED, LocalServiceHealth.classify(IllegalStateException("gateway", interrupted)))
        assertEquals(ConnectionHealth.AUTHENTICATION_FAILED, LocalServiceHealth.classify(IllegalStateException("HTTP 403 forbidden: connection closed before response arrived")))
        assertEquals(ConnectionHealth.UNKNOWN, LocalServiceHealth.classify(kotlinx.coroutines.CancellationException("Connection closed before response arrived")))
    }
}
