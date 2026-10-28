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
    @Before fun reset() = LocalServiceHealth.networkChanged()

    @After fun clear() = LocalServiceHealth.networkChanged()

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
}
