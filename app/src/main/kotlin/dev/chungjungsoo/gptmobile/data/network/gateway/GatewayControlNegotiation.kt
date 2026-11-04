package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.context.RouteCapabilityCache
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal class GatewayControlNegotiation(private val api: GatewayAPI) {
    private val cache = RouteCapabilityCache<GatewayCapabilities>({ System.nanoTime() / 1_000_000 }, negativeTtl = 60_000)
    suspend fun prepare(config: ProviderRequestConfig): ProviderRequestConfig {
        val requested = config.extraHeaders.filterKeys { it.startsWith("X-Gateway-", true) }
        if (requested.isEmpty()) return config
        val identity = researchHash("${config.profileUid}|${config.apiUrl.trimEnd('/')}|${researchHash(config.token.orEmpty())}")
        val capability = cache.getOrLoad(identity) {
            api.getCapabilities(config.copy(extraHeaders = config.extraHeaders - requested.keys))?.takeIf {
                !it.version.isNullOrBlank() || !it.gatewayVersion.isNullOrBlank()
            }
        }
        val accepted = capability?.acceptedRequestHeaders.orEmpty().map(String::lowercase).toSet()
        val transmitted = requested.filterKeys { it.lowercase() in accepted }
        AppLogRecorder.record("Gateway", "CONTROL_NEGOTIATION · route=$identity · requested=${requested.keys.sorted()} · accepted=${transmitted.keys.sorted()} · serverCommit=${capability?.build?.get("sourceCommit")?.jsonPrimitive?.contentOrNull ?: "Not measured"} · effective=Not measured · gatewayVersion=${capability?.gatewayVersion ?: capability?.version ?: "unavailable"}")
        return config.copy(extraHeaders = config.extraHeaders - requested.keys + transmitted)
    }
}
