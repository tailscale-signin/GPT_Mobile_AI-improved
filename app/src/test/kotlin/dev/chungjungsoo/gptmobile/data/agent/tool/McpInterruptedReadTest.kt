package dev.chungjungsoo.gptmobile.data.agent.tool

import java.io.EOFException
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpInterruptedReadTest {
    @Test
    fun recognizesDroppedConnectionsIncludingWrappedTransportFailures() {
        assertTrue(EOFException().isMcpInterruptedRead())
        assertTrue(IllegalStateException("Error while sending message", IOException("unexpected end of stream")).isMcpInterruptedRead())
        assertTrue(IOException("Broken pipe").isMcpInterruptedRead())
        assertTrue(IOException("Connection reset").isMcpInterruptedRead())
    }

    @Test
    fun authenticationQuotaTimeoutAndApplicationErrorsDoNotTriggerReadRetries() {
        for (message in listOf("HTTP 401", "HTTP 403", "Today's 50 free anonymous calls are used up", "Connect timeout has expired", "Invalid request")) {
            assertFalse(IllegalStateException(message).isMcpInterruptedRead())
        }
    }
}
