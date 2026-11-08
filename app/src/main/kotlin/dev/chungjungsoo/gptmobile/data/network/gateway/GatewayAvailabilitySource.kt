package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.context.RouteCapabilityCache
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig

internal data class GatewayAvailabilityProbe(
    val capabilities: GatewayCapabilities?,
    val readiness: GatewayReadinessObservation?
)

internal fun interface GatewayAvailabilitySource {
    suspend fun probe(config: ProviderRequestConfig, model: String, refresh: Boolean): GatewayAvailabilityProbe
}

/** Metadata only: no model requests, warmup, job creation, tools or management credentials. */
internal class GatewayAvailabilityNetworkSource(
    private val gateway: GatewayAPI,
    private val dashboard: GatewayDashboardClient
) : GatewayAvailabilitySource {
    private val capabilities = RouteCapabilityCache<GatewayCapabilities>({ System.nanoTime() / 1_000_000 }, positiveTtl = 10_000, negativeTtl = 60_000)

    override suspend fun probe(config: ProviderRequestConfig, model: String, refresh: Boolean): GatewayAvailabilityProbe {
        if (refresh) {
            dashboard.refresh()
            capabilities.invalidate()
        }
        // This parser verifies the exact model digest and provides typed access failures.
        val readiness = dashboard.readiness(config, model)
        val key = "${GatewayClientDiagnostics.routeIdentity(config)}|${LocalServiceHealth.networkEpoch}|${GatewayClientDiagnostics.runtimeEpoch(config)}"
        val capability = capabilities.getOrLoad(key) { gateway.getCapabilities(config, forceRefresh = refresh) }
        return GatewayAvailabilityProbe(capability, readiness)
    }
}
