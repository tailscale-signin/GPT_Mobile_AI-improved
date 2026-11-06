package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig

/** Keep failure evidence bound to the headers of this physical attempt, including retries. */
internal suspend fun <T> gatewayClientExchange(
    config: ProviderRequestConfig,
    route: String,
    safeRead: Boolean,
    method: String = "GET",
    block: suspend (Map<String, String>) -> T
): T {
    val headers = GatewayClientDiagnostics.requestHeaders(config, route = route, method = method)
    return try {
        block(headers)
    } catch (failure: Throwable) {
        GatewayClientDiagnostics.failed(config, headers, failure, route = route, safeRead = safeRead)
        throw failure
    } finally {
        GatewayClientDiagnostics.finished(headers)
    }
}
