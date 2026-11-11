package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.context.RouteCapabilityCache
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

internal data class GatewayAvailabilityProbe(
    val capabilities: GatewayCapabilities?,
    val readiness: GatewayReadinessObservation?,
    val capabilitiesFailure: GatewayAvailabilityFailure? = null,
    val readinessFailure: GatewayAvailabilityFailure? = null,
    /** Completion time of the metadata check, which may reuse a valid bounded cache. */
    val capabilitiesCheckedAtMillis: Long? = null,
    val readinessCheckedAtMillis: Long? = null,
    /** Original successful capability response time, retained across cache reuse. */
    val capabilitiesObservedAtMillis: Long? = null
)

internal data class GatewayAvailabilityFailure(val status: GatewayAvailabilityStatus, val reasonCode: String)

internal fun interface GatewayAvailabilitySource {
    suspend fun probe(config: ProviderRequestConfig, model: String, refresh: Boolean): GatewayAvailabilityProbe
}

/** Metadata only: no model requests, warmup, job creation, tools or management credentials. */
internal class GatewayAvailabilityNetworkSource(
    private val gateway: GatewayAPI,
    private val dashboard: GatewayDashboardClient
) : GatewayAvailabilitySource {
    private data class CapabilityObservation(val value: GatewayCapabilities, val observedAtMillis: Long)
    private val capabilities = RouteCapabilityCache<CapabilityObservation>({ System.nanoTime() / 1_000_000 }, positiveTtl = 10_000, negativeTtl = 60_000)

    override suspend fun probe(config: ProviderRequestConfig, model: String, refresh: Boolean): GatewayAvailabilityProbe {
        val key = "${GatewayClientDiagnostics.routeIdentity(config)}|${LocalServiceHealth.networkEpoch}|${GatewayClientDiagnostics.runtimeEpoch(config)}"
        var capabilityObservedAt: Long? = null
        // Readiness and capabilities are independent observations. A timeout from /ready
        // must not discard a successful capability response or label a reachable gateway absent.
        return gatewayAvailabilityProbe(
            readCapabilities = {
                capabilities.getOrLoad(key, forceRefresh = refresh) {
                    gateway.getCapabilities(config, forceRefresh = refresh)?.let { CapabilityObservation(it, System.currentTimeMillis()) }
                }?.also { capabilityObservedAt = it.observedAtMillis }?.value
            },
            readReadiness = { dashboard.readiness(config, model, forceRefresh = refresh) }
        ).copy(capabilitiesObservedAtMillis = capabilityObservedAt)
    }
}

internal suspend fun gatewayAvailabilityProbe(
    readCapabilities: suspend () -> GatewayCapabilities?,
    readReadiness: suspend () -> GatewayReadinessObservation?,
    now: () -> Long = System::currentTimeMillis
): GatewayAvailabilityProbe = coroutineScope {
    data class Observation<T>(val value: T?, val failure: GatewayAvailabilityFailure?, val checkedAt: Long)
    suspend fun <T> observe(read: suspend () -> T?): Observation<T> = try {
        // Finish each read before the repository's outer eight-second deadline so
        // a stalled transport or bounded retry cannot erase its completed sibling.
        withTimeout(7_000) { Observation(read(), null, now()) }
    } catch (_: TimeoutCancellationException) {
        // Parent cancellation still owns the whole probe; only a read's own
        // deadline becomes an independent metadata failure.
        currentCoroutineContext().ensureActive()
        Observation(null, GatewayAvailabilityFailure(GatewayAvailabilityStatus.CHECKING, "METADATA_DEADLINE_EXCEEDED"), now())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Observation(null, gatewayAvailabilityFailure(failure), now())
    }
    val capabilities = async { observe(readCapabilities) }
    val readiness = async { observe(readReadiness) }
    val capabilityResult = capabilities.await()
    val readinessResult = readiness.await()
    GatewayAvailabilityProbe(capabilityResult.value, readinessResult.value, capabilityResult.failure, readinessResult.failure, capabilityResult.checkedAt, readinessResult.checkedAt, capabilityResult.checkedAt.takeIf { capabilityResult.value != null })
}
