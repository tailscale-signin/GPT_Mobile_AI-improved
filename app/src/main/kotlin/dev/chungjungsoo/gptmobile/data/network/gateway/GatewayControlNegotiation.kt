package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.context.RouteCapabilityCache
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal class GatewayControlNegotiation(private val api: GatewayAPI) {
    private val cache = RouteCapabilityCache<GatewayCapabilities>({ System.nanoTime() / 1_000_000 }, negativeTtl = 15_000)
    suspend fun prepare(config: ProviderRequestConfig): ProviderRequestConfig {
        val permittedHeaders = GatewayClientDiagnostics.requestHeaders(config)
        val permitted = config.copy(extraHeaders = permittedHeaders)
        val requested = permittedHeaders.filterKeys { it.startsWith("X-Gateway-", true) }
        if (requested.isEmpty()) return permitted
        val identity = researchHash("${GatewayClientDiagnostics.routeIdentity(config)}|${LocalServiceHealth.networkEpoch}|${GatewayClientDiagnostics.capabilityEpoch(config)}")
        val knownGateway = GatewayClientDiagnostics.runtimeEpoch(config) != "unobserved"
        val capability = try {
            cache.getOrLoad(identity) {
                api.getCapabilities(permitted.copy(extraHeaders = permittedHeaders - requested.keys))?.also {
                    GatewayClientDiagnostics.observeInstance(config, it.instanceId)
                }
            }
        } catch (failure: GatewayCapabilityDiscoveryException) {
            // Optional debug metadata must not introduce a new availability gate for an ordinary inference route.
            if (knownGateway || requested.keys.any { !it.equals(GatewayClientDiagnostics.CLIENT_HEADER, true) }) throw failure
            return permitted.copy(extraHeaders = permittedHeaders - requested.keys)
        }
        val isolation = requested.filterKeys {
            it.lowercase() in setOf("x-gateway-allow-local-tools", "x-gateway-delegated-worker", "x-gateway-request-role")
        }
        val accepted = capability?.acceptedRequestHeaders.orEmpty().map(String::lowercase).toSet()
        val transmitted = if (capability == null && !knownGateway) {
            // A definite 404/501 identifies a direct route. Preserve tool isolation if an older proxy understands it.
            isolation
        } else {
            if (capability == null || isolation.keys.any { it.lowercase() !in accepted }) {
                throw GatewayCapabilityDiscoveryException("GATEWAY_ISOLATION_UNAVAILABLE: The selected gateway did not confirm the required tool isolation controls. Check or update that gateway and retry; no model request was sent.")
            }
            requested.filterKeys { it.lowercase() in accepted }
        }
        val sourceCommit = (capability?.build?.get("sourceCommit") as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[a-f0-9]{40}")) }
        AppLogRecorder.record("Gateway", "CONTROL_NEGOTIATION · route=$identity · requested=${requested.keys.sorted()} · accepted=${transmitted.keys.sorted()} · serverCommit=${sourceCommit ?: "Not measured"} · effective=Not measured · gatewayVersion=${capability?.gatewayVersion ?: capability?.version ?: "unavailable"}")
        return permitted.copy(extraHeaders = permittedHeaders - requested.keys + transmitted)
    }
}
