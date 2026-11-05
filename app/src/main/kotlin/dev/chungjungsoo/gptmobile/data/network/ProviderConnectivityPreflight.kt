package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.model.EndpointLocality
import dev.chungjungsoo.gptmobile.data.model.endpointLocality
import dev.chungjungsoo.gptmobile.data.research.academic.ResearchConnectivityGate
import java.io.IOException
import java.net.URI

/** A known outage is a precheck rejection, not another provider request or model-quality failure. */
internal class ProviderConnectivityPendingException(val dnsRecovery: Boolean, val retryAfterMs: Long = 0) :
    IOException(
        if (dnsRecovery) {
            "DNS_RECOVERY: waiting for connectivity recovery; retryAfterMs=$retryAfterMs; provider_dispatched=false"
        } else {
            "NETWORK_DISCONNECTED: device has no active network; provider_dispatched=false"
        }
    )

internal object ProviderConnectivityPreflight {
    suspend fun <T> withAvailability(endpoint: String, validation: String = DeviceNetworkDiagnostics.episodes.current().validation, block: suspend () -> T): T {
        if (endpointLocality(endpoint) != EndpointLocality.EXTERNAL) return block()
        val host = runCatching { URI(endpoint).host }.getOrNull() ?: return block()
        if (validation == "DISCONNECTED") throw ProviderConnectivityPendingException(false)
        val gate = ResearchConnectivityGate.shared
        val lease = gate.acquire(host) ?: throw ProviderConnectivityPendingException(true, gate.recoveryDelayMillis(host))
        try {
            return block()
        } finally {
            gate.release(lease)
        }
    }

    fun failed(endpoint: String, failure: Throwable) {
        if (failure is ProviderConnectivityPendingException || endpointLocality(endpoint) != EndpointLocality.EXTERNAL) return
        val host = runCatching { URI(endpoint).host }.getOrNull() ?: return
        ResearchConnectivityGate.shared.failed(host, failure)
    }

    fun responseReceived(endpoint: String) {
        val host = runCatching { URI(endpoint).host }.getOrNull() ?: return
        ResearchConnectivityGate.shared.success(host)
    }
}
