package dev.chungjungsoo.gptmobile.data.network.gateway

import android.os.SystemClock
import dev.chungjungsoo.gptmobile.data.context.RouteCapabilityCache
import dev.chungjungsoo.gptmobile.data.network.LocalEndpointConnectionPolicy
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.boundedMetadata
import dev.chungjungsoo.gptmobile.data.research.researchHash
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class GatewayDashboardAccessDenied : IllegalStateException("Gateway access was denied. Check this connection in GitHub settings.")

internal interface GatewayDashboardSource {
    suspend fun read(config: ProviderRequestConfig): GatewayDashboardObservation?
    suspend fun readiness(config: ProviderRequestConfig, model: String?): GatewayReadinessObservation? = null
}

/** A bounded read of the selected gateway; never uses the desktop management endpoint. */
@Singleton
internal class GatewayDashboardClient internal constructor(private val http: HttpClient, private val evictIdleConnections: suspend () -> Unit, private val elapsedMillis: () -> Long) : GatewayDashboardSource {
    internal constructor(http: HttpClient, evictIdleConnections: suspend () -> Unit = {}) : this(http, evictIdleConnections, { System.nanoTime() / 1_000_000 })

    @Inject constructor(network: NetworkClient) : this(network.gatewayReadClient(), network::evictIdleConnections, SystemClock::elapsedRealtime)
    private data class Attempt(val runtimeIdentity: String, val observation: GatewayDashboardObservation? = null, val failure: Exception? = null)
    private data class ReadinessAttempt(val runtimeIdentity: String, val observation: GatewayReadinessObservation? = null, val failure: Exception? = null)
    private val cache = RouteCapabilityCache<Attempt>(elapsedMillis, valueTtl = { if (it.observation == null && it.failure == null) 60_000L else 4_000L })
    private val readinessCache = RouteCapabilityCache<ReadinessAttempt>(
        elapsedMillis,
        valueTtl = { attempt ->
            when {
                attempt.failure != null || attempt.observation?.ready == false -> 30_000
                attempt.observation == null -> 60_000
                else -> 4_000
            }
        }
    )

    fun refresh() {
        cache.invalidate()
        readinessCache.invalidate()
    }

    override suspend fun read(config: ProviderRequestConfig): GatewayDashboardObservation? {
        val attempt = cache.getOrLoad(gatewayDashboardIdentity(config) + ":" + LocalServiceHealth.networkEpoch, isCurrent = { it?.runtimeIdentity == GatewayClientDiagnostics.runtimeEpoch(config) }) {
            val runtime = GatewayClientDiagnostics.runtimeEpoch(config)
            try {
                val observation = readDashboard(config)
                Attempt(observation?.snapshot?.instanceId ?: GatewayClientDiagnostics.runtimeEpoch(config), observation = observation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Concurrent observers share a short failure observation as well
                // as successes; a single offline refresh cannot fan out retries.
                Attempt(runtime, failure = failure)
            }
        } ?: return null
        attempt.failure?.let { throw it }
        return attempt.observation
    }

    private suspend fun readDashboard(config: ProviderRequestConfig): GatewayDashboardObservation? {
        val endpoint = config.buildEndpoint("gateway/dashboard")
        return LocalEndpointConnectionPolicy.probe(endpoint, evictIdleConnections) {
            gatewayClientExchange(config, "dashboard", safeRead = true) { requestHeaders ->
                http.prepareGet(endpoint) {
                    config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                    // Profile headers are allowed; management credentials are never used here.
                    requestHeaders.filterKeys { !it.equals("X-Gateway-Management", true) }.forEach { (key, value) -> header(key, value) }
                    if (LocalEndpointConnectionPolicy.freshConnection(endpoint)) header(HttpHeaders.Connection, "close")
                    timeout {
                        connectTimeoutMillis = 5_000
                        requestTimeoutMillis = 8_000
                        socketTimeoutMillis = 8_000
                    }
                }.execute { response ->
                    GatewayClientDiagnostics.observe(config, response.status.value, response.call.request.headers["traceparent"]) { response.headers[it] }
                    if (response.status.value in setOf(404, 405)) return@execute null
                    if (response.status.value in setOf(401, 403)) throw GatewayDashboardAccessDenied()
                    check(response.status.isSuccess()) { "Gateway refresh is unavailable (HTTP ${response.status.value})." }
                    val snapshot = withContext(Dispatchers.Default) {
                        val body = Json.parseToJsonElement(response.boundedMetadata(512_000)) as? JsonObject ?: error("Invalid gateway dashboard.")
                        parseGatewayDashboard(body)
                    }
                    GatewayClientDiagnostics.observeInstance(config, snapshot.instanceId)
                    GatewayDashboardObservation(snapshot, System.currentTimeMillis())
                }
            }
        }
    }

    override suspend fun readiness(config: ProviderRequestConfig, model: String?): GatewayReadinessObservation? =
        readiness(config, model, forceRefresh = false)

    internal suspend fun readiness(config: ProviderRequestConfig, model: String?, forceRefresh: Boolean): GatewayReadinessObservation? {
        require(model == null || validGatewayAvailabilityModel(model)) { "Invalid gateway model identifier." }
        val key = gatewayDashboardIdentity(config) + ":" + researchHash(model.orEmpty()) + ":" + LocalServiceHealth.networkEpoch
        val attempt = readinessCache.getOrLoad(key, forceRefresh, isCurrent = { it?.runtimeIdentity == GatewayClientDiagnostics.runtimeEpoch(config) }) {
            val startRuntime = GatewayClientDiagnostics.runtimeEpoch(config)
            try {
                val observation = readReadiness(config, model)
                ReadinessAttempt(observation?.instanceId ?: GatewayClientDiagnostics.runtimeEpoch(config), observation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ReadinessAttempt(startRuntime, failure = failure)
            }
        } ?: return null
        attempt.failure?.let { throw it }
        return attempt.observation
    }

    private suspend fun readReadiness(config: ProviderRequestConfig, model: String?): GatewayReadinessObservation? {
        val endpoint = config.buildEndpoint("gateway/ready")
        return LocalEndpointConnectionPolicy.probe(endpoint, evictIdleConnections) {
            gatewayClientExchange(config, "readiness", safeRead = true) { requestHeaders ->
                val started = elapsedMillis()
                http.prepareGet(endpoint) {
                    config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                    requestHeaders.filterKeys { !it.equals("X-Gateway-Management", true) }.forEach { (key, value) -> header(key, value) }
                    if (LocalEndpointConnectionPolicy.freshConnection(endpoint)) header(HttpHeaders.Connection, "close")
                    model?.takeIf(String::isNotBlank)?.let { parameter("model", it) }
                    timeout {
                        connectTimeoutMillis = 2_000
                        requestTimeoutMillis = 5_000
                        socketTimeoutMillis = 5_000
                    }
                }.execute { response ->
                    GatewayClientDiagnostics.observe(config, response.status.value, response.call.request.headers["traceparent"]) { response.headers[it] }
                    if (response.status.value in setOf(404, 405, 501)) return@execute null
                    if (response.status.value in setOf(401, 403)) throw GatewayDashboardAccessDenied()
                    check(response.status.value in setOf(200, 503)) { "Gateway readiness is unavailable (HTTP ${response.status.value})." }
                    val readiness = withContext(Dispatchers.Default) {
                        val body = Json.parseToJsonElement(response.boundedMetadata(128_000)) as? JsonObject ?: error("Invalid gateway readiness.")
                        val received = elapsedMillis()
                        val parsed = parseGatewayReadiness(body, response.status.value, model, System.currentTimeMillis(), received)
                        // The server age excludes travel to the client. Counting
                        // the bounded round trip conservatively avoids extending
                        // old model evidence while a response is in transit.
                        val travel = (received - started).takeIf { it >= 0 } ?: Long.MAX_VALUE
                        val age = parsed.observationAgeMillis ?: 0
                        parsed.copy(observationAgeMillis = if (travel > Long.MAX_VALUE - age) Long.MAX_VALUE else age + travel)
                    }
                    GatewayClientDiagnostics.readiness(config, requestHeaders, readiness, model)
                    readiness.also {
                        GatewayClientDiagnostics.observeInstance(config, it.instanceId)
                    }
                }
            }
        }
    }
}

internal fun gatewayDashboardIdentity(config: ProviderRequestConfig): String = researchHash("${config.profileUid}|${GatewayClientDiagnostics.routeIdentity(config)}|${config.profileRevision.orEmpty()}")
