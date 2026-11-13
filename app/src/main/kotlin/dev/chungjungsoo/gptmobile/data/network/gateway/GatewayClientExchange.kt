package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.delegation.CancellationOrigin
import dev.chungjungsoo.gptmobile.data.delegation.DelegationCancellationContext
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import kotlinx.coroutines.currentCoroutineContext

/** Keep failure evidence bound to the headers of this physical attempt, including retries. */
internal suspend fun <T> gatewayClientExchange(
    config: ProviderRequestConfig,
    route: String,
    safeRead: Boolean,
    method: String = "GET",
    knownGateway: Boolean = true,
    operationId: String? = null,
    block: suspend (Map<String, String>) -> T
): T {
    val context = currentCoroutineContext()
    val read = context[dev.chungjungsoo.gptmobile.data.network.LocalMetadataReadAttempt]?.takeIf { safeRead && method == "GET" && it.ordinal in 0..1 }
    val observedReadAttempt = read?.ordinal
    val diagnosticHeaders = GatewayClientDiagnostics.requestHeaders(config, route = route, method = method, metadataReadId = read?.readId, metadataReadAttempt = read?.ordinal, knownGateway = knownGateway, logicalOperationId = operationId)
    val headers = if (observedReadAttempt == null) diagnosticHeaders else diagnosticHeaders.filterKeys { !it.equals("X-Gateway-Retry-Attempt", true) } + ("X-Gateway-Retry-Attempt" to observedReadAttempt.toString())
    if (!safeRead && method == "POST") GatewayJobPollCoordinator.continuing(config)
    return try {
        block(headers).also { if (it != null) GatewayClientDiagnostics.metadataReadCompleted(config, headers) }
    } catch (failure: Throwable) {
        val origin = when (context[DelegationCancellationContext]?.record?.origin) {
            CancellationOrigin.USER_WHOLE_RUN, CancellationOrigin.USER_STOP_RESEARCH_AND_SUMMARIZE, CancellationOrigin.USER_PRIMARY_ONLY -> "user"
            CancellationOrigin.SUPERSEDED_GENERATION -> "superseded"
            CancellationOrigin.OPERATION_DEADLINE -> "deadline"
            else -> null
        }
        GatewayClientDiagnostics.failed(config, headers, failure, route = route, safeRead = safeRead, cancellationOrigin = origin)
        throw failure
    } finally {
        if (!safeRead && method == "POST") GatewayJobPollCoordinator.continuing(config)
        GatewayClientDiagnostics.finished(headers)
    }
}
