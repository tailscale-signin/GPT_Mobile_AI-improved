package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.context.RouteCapabilityCache
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.boundedMetadata
import dev.chungjungsoo.gptmobile.data.research.researchHash
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class GatewayDashboardAccessDenied : IllegalStateException("Gateway access was denied. Check this connection in GitHub settings.")

internal interface GatewayDashboardSource {
    suspend fun read(config: ProviderRequestConfig): GatewayDashboardObservation?
}

/** A bounded read of the selected gateway; never uses the desktop management endpoint. */
@Singleton
internal class GatewayDashboardClient internal constructor(private val http: HttpClient) : GatewayDashboardSource {
    @Inject constructor(network: NetworkClient) : this(network())
    private val cache = RouteCapabilityCache<GatewayDashboardObservation>({ System.nanoTime() / 1_000_000 }, positiveTtl = 4_000, negativeTtl = 60_000)

    fun refresh() = cache.invalidate()

    override suspend fun read(config: ProviderRequestConfig): GatewayDashboardObservation? = cache.getOrLoad(gatewayDashboardIdentity(config) + ":" + LocalServiceHealth.networkEpoch) {
        http.prepareGet(config.buildEndpoint("gateway/dashboard")) {
            config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
            // Profile headers are allowed; management credentials are never used here.
            config.extraHeaders.filterKeys { !it.equals("X-Gateway-Management", true) }.forEach { (key, value) -> header(key, value) }
            timeout {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = 8_000
                socketTimeoutMillis = 8_000
            }
        }.execute { response ->
            GatewayClientDiagnostics.observe(config, response.status.value) { response.headers[it] }
            if (response.status.value in setOf(404, 405)) return@execute null
            if (response.status.value in setOf(401, 403)) throw GatewayDashboardAccessDenied()
            check(response.status.isSuccess()) { "Gateway refresh is unavailable (HTTP ${response.status.value})." }
            val body = Json.parseToJsonElement(response.boundedMetadata(512_000)) as? JsonObject ?: error("Invalid gateway dashboard.")
            val snapshot = parseGatewayDashboard(body)
            GatewayClientDiagnostics.observeInstance(config, snapshot.instanceId)
            GatewayDashboardObservation(snapshot, System.currentTimeMillis())
        }
    }
}

internal fun gatewayDashboardIdentity(config: ProviderRequestConfig): String = researchHash("${config.profileUid}|${config.apiUrl.trim().trimEnd('/')}|${researchHash(config.token.orEmpty())}")
