package dev.chungjungsoo.gptmobile.data.agent.tool

import java.io.EOFException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class McpInterruptedReadTest {
    @Test
    fun recognizesDroppedConnectionsIncludingWrappedTransportFailures() {
        assertTrue(EOFException().isMcpInterruptedRead())
        assertTrue(IllegalStateException("Error while sending message", IOException("unexpected end of stream")).isMcpInterruptedRead())
        assertTrue(IOException("Broken pipe").isMcpInterruptedRead())
        assertTrue(IOException("Connection reset").isMcpInterruptedRead())
        assertTrue(IllegalStateException("wrapped", java.net.SocketException("Software caused connection abort")).isMcpInterruptedRead())
    }

    @Test
    fun recognizesOnlyTheExactSdkDisconnectedLifecycleError() {
        assertTrue(IllegalStateException("Not connected").isMcpInterruptedRead())
        assertTrue(IOException("wrapped", IllegalStateException("Not connected")).isMcpInterruptedRead())
        for (error in listOf(
            IOException("Not connected"),
            IllegalArgumentException("Not connected"),
            IllegalStateException("Not connected to the provider"),
            IllegalStateException("not connected"),
            CancellationException("Not connected"),
            ModernMcpError(401, null, "Not connected"),
            ModernMcpError(400, -32602, "Not connected")
        )) {
            assertFalse(error.toString(), error.isMcpInterruptedRead())
        }
    }

    @Test
    fun authenticationSchemaAndCancellationFailuresNeverReplayReads() = runBlocking {
        for (failure in listOf(
            McpAuthenticationRequiredException(),
            McpProtocolConfigurationException("MCP_INITIALIZATION_SCHEMA_INVALID", "Invalid request"),
            ModernMcpError(401, null, "Not connected"),
            ModernMcpError(400, -32602, "Not connected"),
            CancellationException("Not connected")
        )) {
            var attempts = 0
            val thrown = runCatching {
                withMcpReadRecovery("tools/list", pause = { error("Permanent failures must not enter retry backoff") }) {
                    attempts++
                    throw failure
                }
            }.exceptionOrNull()
            assertSame(failure, thrown)
            assertEquals(1, attempts)
        }
    }

    @Test
    fun authenticationQuotaTimeoutAndApplicationErrorsDoNotTriggerReadRetries() {
        for (message in listOf("HTTP 401", "HTTP 403", "Today's 50 free anonymous calls are used up", "Connect timeout has expired", "Invalid request")) {
            assertFalse(IllegalStateException(message).isMcpInterruptedRead())
        }
    }
}
