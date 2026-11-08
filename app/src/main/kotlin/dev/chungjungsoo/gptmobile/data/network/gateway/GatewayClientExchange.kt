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
    val observedReadAttempt = currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.network.LocalMetadataReadAttempt]?.ordinal?.takeIf { safeRead && method == "GET" && it in 0..1 }
    val diagnosticHeaders = GatewayClientDiagnostics.requestHeaders(config, route = route, method = method)
    val headers = if (observedReadAttempt == null) diagnosticHeaders else diagnosticHeaders.filterKeys { !it.equals("X-Gateway-Retry-Attempt", true) } + ("X-Gateway-Retry-Attempt" to observedReadAttempt.toString())
    return try {
        block(headers)
    } catch (failure: Throwable) {
        GatewayClientDiagnostics.failed(config, headers, failure, route = route, safeRead = safeRead)
        throw failure
    } finally {
        GatewayClientDiagnostics.finished(headers)
    }
}
