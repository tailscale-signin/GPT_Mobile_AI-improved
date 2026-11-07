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
        val knownGateway = GatewayClientDiagnostics.runtimeEpoch(config) != "unobserved"
        // A gateway remains a gateway when diagnostics and optional request controls are disabled.
        if (requested.isEmpty() && !knownGateway) return permitted
        val identity = researchHash("${GatewayClientDiagnostics.routeIdentity(config)}|${LocalServiceHealth.networkEpoch}|${GatewayClientDiagnostics.capabilityEpoch(config)}")
        val capability = try {
            cache.getOrLoad(identity) {
                api.getCapabilities(permitted.copy(extraHeaders = permittedHeaders - requested.keys))?.also {
                    GatewayClientDiagnostics.observeInstance(config, it.instanceId)
                }
            }
        } catch (failure: GatewayCapabilityDiscoveryException) {
            // Optional debug metadata must not introduce a new availability gate for an ordinary inference route.
            if (knownGateway || GatewayClientDiagnostics.runtimeEpoch(config) != "unobserved" || requested.keys.any { !it.equals(GatewayClientDiagnostics.CLIENT_HEADER, true) }) throw failure
            return permitted.copy(extraHeaders = permittedHeaders - requested.keys)
        }
        // Discovery can observe gateway identity even when its capability endpoint rejects the request.
        if (capability == null && (knownGateway || GatewayClientDiagnostics.runtimeEpoch(config) != "unobserved")) {
            throw GatewayCapabilityDiscoveryException("GATEWAY_CAPABILITY_UNAVAILABLE: The selected gateway no longer confirms its capabilities. Reconnect to the gateway software and retry; no model request was sent.")
        }
        val isolation = requested.filterKeys {
            it.lowercase() in setOf("x-gateway-allow-local-tools", "x-gateway-delegated-worker", "x-gateway-request-role")
        }
        val accepted = capability?.acceptedRequestHeaders.orEmpty().map(String::lowercase).toSet()
        val transmitted = if (capability == null) {
            // A definite 404/501 identifies a direct route. Preserve tool isolation if an older proxy understands it.
            isolation
        } else {
            if (isolation.keys.any { it.lowercase() !in accepted }) {
                throw GatewayCapabilityDiscoveryException("GATEWAY_ISOLATION_UNAVAILABLE: The selected gateway did not confirm the required tool isolation controls. Check or update that gateway and retry; no model request was sent.")
            }
            requested.filterKeys { it.lowercase() in accepted }
        }
        val sourceCommit = (capability?.build?.get("sourceCommit") as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("[a-f0-9]{40}")) }
        AppLogRecorder.record("Gateway", "CONTROL_NEGOTIATION · route=$identity · requested=${requested.keys.sorted()} · accepted=${transmitted.keys.sorted()} · serverCommit=${sourceCommit ?: "Not measured"} · effective=Not measured · gatewayVersion=${capability?.gatewayVersion ?: capability?.version ?: "unavailable"}")
        return permitted.copy(extraHeaders = permittedHeaders - requested.keys + transmitted)
    }
}
