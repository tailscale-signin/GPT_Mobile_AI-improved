package dev.chungjungsoo.gptmobile.data.network

import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkFailureKindTest {
    @Test fun wrappersRetainTheActualFailureCategory() {
        assertEquals(NetworkFailureKind.DNS, networkFailureKind(IllegalStateException("provider failed", UnknownHostException("private host"))))
        assertEquals(NetworkFailureKind.DNS, networkFailureKind(UnresolvedAddressException()))
        assertEquals(NetworkFailureKind.ABORTED, networkFailureKind(IOException("read failed", SocketException("Software caused connection abort"))))
        assertEquals(NetworkFailureKind.ABORTED, networkFailureKind(EOFException("response lost")))
        assertEquals(NetworkFailureKind.TIMEOUT, networkFailureKind(IOException("request failed", SocketTimeoutException())))
    }

    @Test fun cancellationAndTlsNeverBecomeTransientSocketRecovery() {
        assertEquals(NetworkFailureKind.CANCELLED, networkFailureKind(IOException("connection reset", CancellationException("cancel"))))
        assertEquals(NetworkFailureKind.TLS, networkFailureKind(IOException("timed out", SSLHandshakeException("certificate invalid"))))
        assertFalse(NetworkFailureKind.CANCELLED.retryable)
        assertFalse(NetworkFailureKind.TLS.retryable)
    }

    @Test fun cyclicCausesAreBoundedAndPrivateMessagesAreNotProjected() {
        val first = IOException("private credential: sk-secret")
        val second = IOException("private endpoint", first)
        first.initCause(second)
        assertEquals(NetworkFailureKind.UNKNOWN, networkFailureKind(first))
        val message = NetworkFailureKind.ABORTED.userMessage(partialOutput = true)
        assertTrue(message.contains("partial response has been retained"))
        assertFalse(message.contains("sk-secret"))
        assertFalse(message.contains("private endpoint"))
    }
}
