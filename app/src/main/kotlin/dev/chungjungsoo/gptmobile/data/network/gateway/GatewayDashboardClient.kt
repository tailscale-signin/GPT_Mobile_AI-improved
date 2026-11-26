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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal class GatewayDashboardAccessDenied : IllegalStateException("Gateway access was denied. Check this connection in GitHub settings.")

internal data class GatewayObservedToolMetrics(
    val total: Long,
    val success: Long,
    val failure: Long,
    val p50Millis: Long?,
    val p95Millis: Long?,
    val sampleCount: Long,
    val lastUsed: Double?
)

internal data class GatewayInventoryTool(
    val name: String,
    val description: String?,
    val inputFields: List<String>,
    val id: String = name,
    val sourceId: String = "legacy-gateway",
    val authorized: Boolean? = null,
    val enabled: Boolean = true,
    val executableNow: Boolean? = null,
    val blockedReason: String? = null,
    val metrics: GatewayObservedToolMetrics? = null
) {
    val category: String get() = when {
        name.startsWith("github_", ignoreCase = true) -> "GitHub"
        name.startsWith("web_", ignoreCase = true) -> "Web"
        else -> "Gateway MCP"
    }
}

internal data class GatewayInventoryPage(val offset: Int, val total: Int, val catalogStatus: String, val next: String?)

// Gateway CatalogCache admits up to 64 connections and 2,048 tools each, plus its local built-ins.
private const val MAX_GATEWAY_INVENTORY_TOOLS = 64 * 2048 + 2

private class GatewayInventoryChanged : IllegalStateException("The Gateway catalog changed during discovery.")

internal data class GatewayToolInventory(
    val tools: List<GatewayInventoryTool>,
    val declaredCount: Int,
    val observedAtMillis: Long,
    val status: String = "PARTIAL",
    val scope: String = "legacy_gateway_mcp",
    val reason: String? = null,
    val hasMore: Boolean = false,
    val cursorLineage: String? = null,
    val page: GatewayInventoryPage? = null
)

internal fun parseGatewayToolInventory(source: JsonObject, observedAtMillis: Long): GatewayToolInventory {
    if ((source["schema"] as? JsonPrimitive)?.contentOrNull == "gptmobile.gateway.capability-inventory.v1") {
        require((source["schemaVersion"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull == 1)
        val rows = requireNotNull(source["tools"] as? JsonArray)
        val count = requireNotNull((source["visibleCount"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull)
        require(count == rows.size && count in 0..2048)
        val status = (source["status"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        require(status in setOf("COMPLETE", "PARTIAL", "STALE", "BLOCKED", "NOT_DISCOVERED"))
        val tools = rows.map { element ->
            val row = requireNotNull(element as? JsonObject)
            fun text(key: String, maximum: Int): String? = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.take(maximum)
            fun count(key: String): Long? = (row[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
            fun flag(key: String): Boolean? = (row[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            val name = requireNotNull(text("name", 200)).also { require(it.isNotBlank() && it.none(Char::isISOControl)) }
            val id = requireNotNull(text("id", 64)).also { require(it.matches(Regex("[a-f0-9]{64}"))) }
            val sourceId = requireNotNull(text("sourceId", 80)).also { require(it.isNotBlank() && it.none(Char::isISOControl)) }
            val fields = (row["inputFields"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull }
            require(fields.size <= 32 && fields.distinct().size == fields.size && fields.all { it.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}")) })
            val metrics = (row["metrics"] as? JsonObject)?.let { metric ->
                fun metricCount(key: String) = (metric[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
                fun metricNumber(key: String) = (metric[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }
                val total = requireNotNull(metricCount("total"))
                val success = requireNotNull(metricCount("success"))
                val failure = requireNotNull(metricCount("failure"))
                val samples = requireNotNull(metricCount("sampleCount"))
                require(success + failure <= total && samples <= success)
                GatewayObservedToolMetrics(total, success, failure, metricCount("p50Millis"), metricCount("p95Millis"), samples, metricNumber("lastUsed"))
            }
            GatewayInventoryTool(name, text("description", 400), fields, id, sourceId, flag("authorized"), flag("enabled") ?: false, flag("executableNow"), text("blockedReason", 160), metrics)
        }
        require(tools.distinctBy { it.id }.size == tools.size && tools.distinctBy { it.sourceId to it.name }.size == tools.size)
        val scope = (source["scope"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty().take(100)
        val reason = (source["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.take(500)
        val hasMore = (source["hasMore"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: false
        val lineage = (source["cursorLineage"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        val page = if ("pageOffset" in source) {
            val offset = requireNotNull((source["pageOffset"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull)
            val total = requireNotNull((source["authorizedCount"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull)
            require(total in 0..MAX_GATEWAY_INVENTORY_TOOLS)
            val catalogStatus = (source["catalogStatus"] as? JsonPrimitive)?.contentOrNull
            require(catalogStatus in setOf("COMPLETE", "PARTIAL", "STALE", "BLOCKED", "NOT_DISCOVERED"))
            val end = offset.toLong() + count
            require(scope == "selected_gateway_authorized_mcp" && lineage != null && offset >= 0 && end <= total && hasMore == (end < total))
            val cursor = (source["nextCursor"] as? JsonPrimitive)?.contentOrNull
            require(if (hasMore) count > 0 && cursor == "$lineage:$end" else cursor == null)
            GatewayInventoryPage(offset, total, requireNotNull(catalogStatus), cursor)
        } else {
            null
        }
        return GatewayToolInventory(tools.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }, count, observedAtMillis, requireNotNull(status), scope, reason, hasMore, lineage, page)
    }
    val rows = requireNotNull(source["value"] as? JsonArray)
    val count = requireNotNull((source["Count"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull)
    require(count == rows.size)
    val tools = rows.map { row ->
        val function = requireNotNull((row as? JsonObject)?.get("function") as? JsonObject)
        val name = requireNotNull((function["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull)
        require(name.isNotBlank() && name.length <= 200 && name.none(Char::isISOControl))
        val description = (function["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(400)
        val fields = ((function["parameters"] as? JsonObject)?.get("properties") as? JsonObject)?.keys.orEmpty()
        GatewayInventoryTool(name, description, fields.filter { it.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,63}")) }.sorted().take(32))
    }
    require(tools.distinctBy { it.name }.size == tools.size)
    return GatewayToolInventory(tools.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }, count, observedAtMillis)
}

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

    fun refresh(config: ProviderRequestConfig? = null) {
        // An explicit reconnect may reopen only the selected service. Cache-only
        // invalidation must not let passive observers bypass a transport cooldown.
        config?.let { LocalServiceHealth.checkAgain(it.apiUrl) }
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
                    parameter("inventory_preview", true)
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

    suspend fun readToolInventory(config: ProviderRequestConfig): GatewayToolInventory? = try {
        readInventoryPages(config)
    } catch (_: GatewayInventoryChanged) {
        readInventoryPages(config)
    }

    private suspend fun readInventoryPages(config: ProviderRequestConfig): GatewayToolInventory? {
        val first = readToolInventory(config, "gateway/dashboard", normalized = true)
            ?: return readToolInventory(config, "gateway/tools", normalized = false)
        val firstPage = first.page ?: return first
        require(firstPage.offset == 0)
        val all = ArrayList(first.tools)
        var current = first
        while (current.hasMore) {
            val cursor = requireNotNull(current.page?.next)
            val next = readToolInventory(config, "gateway/dashboard", normalized = true, cursor = cursor) ?: throw GatewayInventoryChanged()
            val page = next.page ?: throw GatewayInventoryChanged()
            if (next.cursorLineage != first.cursorLineage ||
                page.total != firstPage.total ||
                page.catalogStatus != firstPage.catalogStatus ||
                page.offset != all.size
            ) {
                throw GatewayInventoryChanged()
            }
            all.addAll(next.tools)
            current = next
        }
        require(all.size == firstPage.total && all.distinctBy { it.id }.size == all.size && all.distinctBy { it.sourceId to it.name }.size == all.size)
        return first.copy(
            tools = all.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { tool: GatewayInventoryTool -> tool.name }.thenBy { it.sourceId }),
            declaredCount = all.size,
            status = firstPage.catalogStatus,
            hasMore = false,
            page = null
        )
    }

    private suspend fun readToolInventory(config: ProviderRequestConfig, path: String, normalized: Boolean, cursor: String? = null): GatewayToolInventory? {
        val endpoint = config.buildEndpoint(path)
        return LocalEndpointConnectionPolicy.probe(endpoint, evictIdleConnections) {
            gatewayClientExchange(config, "dashboard", safeRead = true) { headers ->
                http.prepareGet(endpoint) {
                    if (normalized) parameter("inventory_page_size", 128)
                    if (cursor != null) parameter("inventory_cursor", cursor)
                    config.token?.takeIf(String::isNotBlank)?.let { bearerAuth(it) }
                    headers.filterKeys { !it.equals("X-Gateway-Management", true) }.forEach { (key, value) -> header(key, value) }
                    timeout {
                        connectTimeoutMillis = 5_000
                        requestTimeoutMillis = 8_000
                        socketTimeoutMillis = 8_000
                    }
                }.execute { response ->
                    if (response.status.value in setOf(404, 405)) return@execute null
                    if (response.status.value in setOf(401, 403)) throw GatewayDashboardAccessDenied()
                    if (cursor != null && response.status.value == 409) throw GatewayInventoryChanged()
                    check(response.status.isSuccess())
                    val body = response.boundedMetadata(2_000_000)
                    withContext(Dispatchers.Default) {
                        val parsed = Json.parseToJsonElement(body) as JsonObject
                        val inventory = if (normalized) parsed["capabilityInventory"] as? JsonObject else parsed
                        inventory?.let { parseGatewayToolInventory(it, System.currentTimeMillis()) }
                    }
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
        return dev.chungjungsoo.gptmobile.data.network.PrivateGatewayMetadata.withReadiness(endpoint, model) { grant ->
            LocalEndpointConnectionPolicy.probe(endpoint, evictIdleConnections) {
                gatewayClientExchange(config, "readiness", safeRead = true) { requestHeaders ->
                    val started = elapsedMillis()
                    http.prepareGet(endpoint) {
                        grant?.let { attributes.put(dev.chungjungsoo.gptmobile.data.network.PrivateGatewayMetadata.grantKey, it) }
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
}

internal fun gatewayDashboardIdentity(config: ProviderRequestConfig): String = researchHash("${config.profileUid}|${GatewayClientDiagnostics.routeIdentity(config)}|${config.profileRevision.orEmpty()}")
