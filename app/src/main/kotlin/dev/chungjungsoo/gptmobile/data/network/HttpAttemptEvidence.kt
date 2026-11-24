package dev.chungjungsoo.gptmobile.data.network

/** Transport boundary observations only. No endpoint, certificate, address, request or response content. */
internal enum class HttpAttemptPhase { NOT_OBSERVED, CALL_STARTED, CONNECTING, TLS_HANDSHAKE, CONNECTION_ACQUIRED, REQUEST_HEADERS, REQUEST_BODY, REQUEST_BODY_COMPLETE, RESPONSE_HEADERS }

internal data class HttpAttemptEvidence(
    val phase: HttpAttemptPhase = HttpAttemptPhase.NOT_OBSERVED,
    val requestBodyCompleted: Boolean = false,
    val connectionReused: Boolean? = null,
    val tlsVersion: String? = null,
    val httpProtocol: String? = null
)

/** One collector per physical call. Its current exchange never borrows an earlier redirect's body completion. */
internal class HttpAttemptEvidenceCollector {
    private var evidence = HttpAttemptEvidence()
    private var connecting = false

    @Synchronized fun phase(phase: HttpAttemptPhase) {
        if (phase == HttpAttemptPhase.CONNECTING) {
            connecting = true
            evidence = HttpAttemptEvidence()
        }
        val bodyCompleted = when (phase) {
            HttpAttemptPhase.CONNECTING, HttpAttemptPhase.TLS_HANDSHAKE, HttpAttemptPhase.REQUEST_HEADERS, HttpAttemptPhase.REQUEST_BODY -> false
            HttpAttemptPhase.REQUEST_BODY_COMPLETE -> true
            else -> evidence.requestBodyCompleted
        }
        evidence = evidence.copy(phase = phase, requestBodyCompleted = bodyCompleted)
    }

    @Synchronized fun connection(tlsVersion: String?, httpProtocol: String?) {
        evidence = evidence.copy(
            phase = HttpAttemptPhase.CONNECTION_ACQUIRED,
            requestBodyCompleted = false,
            connectionReused = !connecting,
            tlsVersion = tlsVersion?.takeIf { it in TLS_VERSIONS },
            httpProtocol = httpProtocol?.takeIf { it in HTTP_PROTOCOLS }
        )
        connecting = false
    }

    @Synchronized fun snapshot(): HttpAttemptEvidence = evidence

    companion object {
        val TLS_VERSIONS = setOf("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3", "SSLv3")
        val HTTP_PROTOCOLS = setOf("http/1.0", "http/1.1", "h2", "h2_prior_knowledge", "quic", "h3")
    }
}
