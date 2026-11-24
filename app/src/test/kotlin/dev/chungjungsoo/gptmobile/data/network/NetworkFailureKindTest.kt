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
    @Test fun unresolvedChainsCannotBorrowATransientWrapperClassification() {
        val hiddenTerminals = listOf(
            javax.net.ssl.SSLProtocolException("private TLS detail"),
            CancellationException("private cancellation detail")
        ).map { terminal ->
            var failure: Throwable = terminal
            repeat(16) { failure = IOException("HTTP 502: network is unreachable", failure) }
            failure
        }
        val first = IOException("HTTP 502: network is unreachable")
        val second = IOException("DNS_RECOVERY", first)
        first.initCause(second)
        val kinds = (hiddenTerminals + first).map(::networkFailureKind)
        assertEquals(listOf(NetworkFailureKind.UNKNOWN, NetworkFailureKind.UNKNOWN, NetworkFailureKind.UNKNOWN), kinds)
        assertTrue(kinds.none { it.retryable })
    }

    @Test fun completeSixteenNodeChainsRetainDnsAndCancellationPriority() {
        var dns: Throwable = UnknownHostException("private host")
        repeat(15) { dns = IOException("HTTP 502: network is unreachable", dns) }
        assertEquals(NetworkFailureKind.DNS, networkFailureKind(dns))

        var cancelled: Throwable = javax.net.ssl.SSLProtocolException("TLS wrapper").apply { initCause(CancellationException("cancelled")) }
        repeat(14) { cancelled = IOException("HTTP 502: network is unreachable", cancelled) }
        assertEquals(NetworkFailureKind.CANCELLED, networkFailureKind(cancelled))

        val cancellation = CancellationException("cancelled")
        val tls = javax.net.ssl.SSLProtocolException("TLS wrapper").apply { initCause(cancellation) }
        cancellation.initCause(tls)
        assertEquals(NetworkFailureKind.CANCELLED, networkFailureKind(tls))
    }

    @Test fun unspecifiedTlsDoesNotInventACertificateFailureOrRecommendReplaying() {
        val message = NetworkFailureKind.TLS.userMessage(partialOutput = false)
        assertFalse(message.contains("certificate"))
        assertFalse(message.contains("could not be verified"))
        assertTrue(message.contains("not automatically retried"))
    }

    @Test fun tlsPresentationUsesTypedCausesAndPreservesSavedWork() {
        val protocol = IOException("secret", javax.net.ssl.SSLProtocolException("private endpoint"))
        assertEquals(TlsFailureKind.PROTOCOL, tlsFailureKind(protocol))
        val message = NetworkFailureKind.TLS.userMessage(true, true, protocol)
        assertTrue(message.contains("protocol error"))
        assertTrue(message.contains("partial response is saved"))
        assertFalse(message.contains("certificate"))
        assertFalse(message.contains("secret"))
        val certificate = SSLHandshakeException("private").apply { initCause(java.security.cert.CertificateExpiredException()) }
        assertEquals(TlsFailureKind.CERTIFICATE, tlsFailureKind(certificate))
        assertTrue(NetworkFailureKind.TLS.userMessage(false, failure = certificate).contains("certificate could not be verified"))
        assertEquals(TlsFailureKind.HANDSHAKE, tlsFailureKind(SSLHandshakeException("protocol unknown")))
        assertFalse(NetworkFailureKind.TLS.retryable)
    }

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
