package dev.chungjungsoo.gptmobile.data.network

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.security.cert.CertificateException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException

/** Stable transport evidence shared by providers and tools; never includes a URL, prompt or credential. */
internal enum class NetworkFailureKind(val code: String, val failedStage: String, val retryable: Boolean) {
    DNS("DNS_RESOLUTION_FAILED", "dns", true),
    DISCONNECTED("NETWORK_DISCONNECTED", "connection", true),
    ABORTED("CONNECTION_ABORTED", "response_stream", true),
    TIMEOUT("NETWORK_TIMEOUT", "transport", true),
    TLS("TLS_FAILURE", "tls", false),
    CANCELLED("CLIENT_CANCELLED", "cancelled", false),
    UNKNOWN("TRANSPORT_UNKNOWN", "transport", false)
}

/** Engines wrap the socket error. Bound traversal and stop cycles rather than inspecting only the wrapper. */
internal fun networkFailureKind(failure: Throwable): NetworkFailureKind {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val causes = generateSequence(failure) { it.cause }.takeWhile(seen::add).take(16).toList()
    val message = causes.joinToString(" ") { it.message.orEmpty().take(512) }.lowercase()
    return when {
        causes.any { it is CancellationException } -> NetworkFailureKind.CANCELLED
        causes.any { it is SSLException || it is CertificateException } -> NetworkFailureKind.TLS
        causes.any { it is UnknownHostException || it is UnresolvedAddressException } || "unable to resolve host" in message || "dns_recovery" in message -> NetworkFailureKind.DNS
        causes.any { it is SocketTimeoutException || it.javaClass.simpleName in setOf("HttpRequestTimeoutException", "ConnectTimeoutException", "SocketTimeoutException") } || "timed out" in message || "timeout" in message -> NetworkFailureKind.TIMEOUT
        causes.any { it is ConnectException || it is NoRouteToHostException } || "network is unreachable" in message || "network_disconnected" in message -> NetworkFailureKind.DISCONNECTED
        causes.any { it is EOFException } ||
            causes.any { it is IOException } &&
            listOf(
                "software caused connection abort", "connection abort", "connection reset", "broken pipe", "unexpected end", "end of stream", "unexpected eof", "socket closed", "connection closed", "prematurely closed", "stream was reset"
            ).any { it in message } -> NetworkFailureKind.ABORTED
        else -> NetworkFailureKind.UNKNOWN
    }
}

internal fun NetworkFailureKind.userMessage(partialOutput: Boolean, responseAccepted: Boolean = false): String = when (this) {
    NetworkFailureKind.DNS -> "DNS could not resolve the configured endpoint. Check your connection and DNS settings, then retry the saved prompt."
    NetworkFailureKind.DISCONNECTED -> "The configured endpoint is unreachable. Check its address, listener, firewall and network connection, then retry."
    NetworkFailureKind.ABORTED -> when {
        partialOutput -> "The connection was interrupted. Your partial response has been retained; continue after connectivity returns."
        responseAccepted -> "The connection was interrupted after the provider accepted the request, before a visible answer completed. Continue from the saved task results after connectivity returns."
        else -> "The connection closed before the response arrived. Retry when connectivity returns."
    }
    NetworkFailureKind.TIMEOUT -> when {
        partialOutput -> "The response timed out. Your partial response has been retained; continue when the endpoint is available."
        responseAccepted -> "The accepted response timed out before a visible answer completed. Continue from the saved task results when the endpoint is available."
        else -> "The endpoint timed out. Retry when it is available."
    }
    NetworkFailureKind.TLS -> "The endpoint's secure connection could not be verified. Check its certificate and TLS configuration before retrying."
    NetworkFailureKind.CANCELLED -> "The request was cancelled."
    NetworkFailureKind.UNKNOWN -> "The request failed during transport. Review the connection diagnostics before retrying."
}
