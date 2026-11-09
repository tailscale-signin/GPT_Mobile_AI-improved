package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import kotlinx.coroutines.currentCoroutineContext

/** Keep failure evidence bound to the headers of this physical attempt, including retries. */
internal suspend fun <T> gatewayClientExchange(
    config: ProviderRequestConfig,
    route: String,
    safeRead: Boolean,
    method: String = "GET",
    block: suspend (Map<String, String>) -> T
): T {
    val read = currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.network.LocalMetadataReadAttempt]?.takeIf { safeRead && method == "GET" && it.ordinal in 0..1 }
    val observedReadAttempt = read?.ordinal
    val diagnosticHeaders = GatewayClientDiagnostics.requestHeaders(config, route = route, method = method, metadataReadId = read?.readId, metadataReadAttempt = read?.ordinal)
    val headers = if (observedReadAttempt == null) diagnosticHeaders else diagnosticHeaders.filterKeys { !it.equals("X-Gateway-Retry-Attempt", true) } + ("X-Gateway-Retry-Attempt" to observedReadAttempt.toString())
    return try {
        block(headers).also { if (it != null) GatewayClientDiagnostics.metadataReadCompleted(config, headers) }
    } catch (failure: Throwable) {
        GatewayClientDiagnostics.failed(config, headers, failure, route = route, safeRead = safeRead)
        throw failure
    } finally {
        GatewayClientDiagnostics.finished(headers)
    }
}
