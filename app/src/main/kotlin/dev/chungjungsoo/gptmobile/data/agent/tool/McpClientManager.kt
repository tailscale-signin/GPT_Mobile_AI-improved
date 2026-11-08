package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.ToolCatalogConnectionDiscovery
import dev.chungjungsoo.gptmobile.data.agent.ToolCatalogDiscoveryState
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

class McpConnectionConfig(
    val connectionUid: String,
    val endpointUrl: String,
    val allowCleartext: Boolean,
    val authorizationHeader: String? = null,
    val googleApiKey: String? = null,
    /** Opaque saved-configuration/record revision; never the transport key or a plaintext credential hash. */
    val configurationRevision: String? = null
)

enum class McpConnectionHealthState {
    CONNECTED,
    DEGRADED,
    UNREACHABLE,
    AUTHENTICATION_REQUIRED,
    RECOVERING
}

enum class McpToolReadiness { NOT_DISCOVERED, DISCOVERING, READY, STALE }

data class McpConnectionHealth(
    val state: McpConnectionHealthState = McpConnectionHealthState.RECOVERING,
    val lastSuccessAtMs: Long? = null,
    val lastFailureAtMs: Long? = null,
    val latencyMs: Long? = null,
    val consecutiveFailures: Int = 0,
    val nextRetryAtMs: Long? = null,
    val lastError: String? = null,
    val availableToolCount: Int? = null,
    val connectionHealth: dev.chungjungsoo.gptmobile.data.network.ConnectionHealth = dev.chungjungsoo.gptmobile.data.network.ConnectionHealth.UNKNOWN,
    val failedStage: String? = null,
    val toolReadiness: McpToolReadiness = McpToolReadiness.NOT_DISCOVERED,
    val executionVerified: Boolean? = null,
    val executionObservedAtMs: Long? = null,
    val lastErrorCode: String? = null,
    val failureConfigurationRevision: String? = null,
    val lastHttpStatus: Int? = null,
    val lastValidationRule: String? = null,
    val lastValidationField: String? = null,
    val lastDispatchEvidence: String? = null,
    val lastRetryDecision: String? = null
) {
    val failureRetryable: Boolean? get() = if (lastFailureAtMs == null || state == McpConnectionHealthState.CONNECTED) null else nextRetryAtMs?.let { it != Long.MAX_VALUE }
    val toolsReady: Boolean get() = state == McpConnectionHealthState.CONNECTED && toolReadiness == McpToolReadiness.READY
}

class McpBackoffException(
    val connectionUid: String,
    val retryAtMs: Long
) : IllegalStateException("MCP connection is backing off until $retryAtMs.")

internal class McpCatalogueTimeoutException(timeout: Long) : IllegalStateException("MCP catalogue discovery exceeded $timeout ms; saved catalogue and connection settings retained. Retry discovery or check server load.")

class McpEndpointConfigurationException(val httpStatus: Int? = null) :
    IllegalStateException(
        (if (httpStatus != null) "MCP endpoint returned HTTP $httpStatus during initialization. " else "MCP endpoint configuration is blocked by its previous initialization failure. ") +
            "Check the server URL/path and running service in Plugins/Tools, then reconnect."
    )

@Singleton
class McpClientManager internal constructor(
    private val httpClient: HttpClient,
    private val mediaStore: McpMediaStore? = null,
    private val interactions: McpInteractions? = null,
    private val transportConnectTimeoutMs: Long = 5_000,
    private val sessionConnectTimeoutMs: Long = 15_000,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val modern: ModernMcpTransport? = null,
    private val trust: dev.chungjungsoo.gptmobile.data.permissions.ToolTrustStore? = null,
    private val statistics: dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsRepository? = null,
    private val retryJitterMs: () -> Long = { kotlin.random.Random.nextLong(0, 1001) },
    private val catalogueScope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
    private val catalogueTimeoutMs: Long = 30_000L,
    private val savedCatalogues: McpCatalogStore? = null
) {
    @Inject
    constructor(networkClient: NetworkClient, mediaStore: McpMediaStore, interactions: McpInteractions, tasks: dev.chungjungsoo.gptmobile.data.workspace.RemoteTaskStore, trust: dev.chungjungsoo.gptmobile.data.permissions.ToolTrustStore, statistics: dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsRepository, @dev.chungjungsoo.gptmobile.di.ReadinessScope scope: kotlinx.coroutines.CoroutineScope, savedCatalogues: McpCatalogStore) : this(networkClient(), mediaStore, interactions, modern = ModernMcpTransport(networkClient(), interactions, tasks), trust = trust, statistics = statistics, catalogueScope = scope, savedCatalogues = savedCatalogues)

    private val mutex = Mutex()
    private val healthOrder = McpHealthObservationOrder()

    // ponytail: one global lock serializes session setup only; use per-connection locks if startup contention becomes measurable.
    private val providerCooldowns = ConcurrentHashMap<String, Pair<String, Long>>()
    private val configurationFailures = ConcurrentHashMap<String, String>()
    private val protocolFailures = ConcurrentHashMap<String, Pair<String, McpProtocolConfigurationException>>()
    private val transportConfigurationKeys = ConcurrentHashMap<String, String>()
    private val sessions = mutableMapOf<String, Session>()
    private val inFlight = mutableMapOf<String, InFlight>()
    private val _health = MutableStateFlow<Map<String, McpConnectionHealth>>(emptyMap())
    val health = _health.asStateFlow()

    private suspend fun modernTransport(config: McpConnectionConfig, refresh: Boolean = false): ModernMcpTransport? {
        val key = config.validatedKey()
        protocolFailures[config.connectionUid]?.let { (identity, failure) ->
            if (!refresh && identity == config.catalogueIdentity()) throw failure
            protocolFailures.remove(config.connectionUid)
            resetHealth(config.connectionUid)
        }
        val previousKey = transportConfigurationKeys.put(config.connectionUid, key)
        if (previousKey != null && previousKey != key) resetHealth(config.connectionUid)
        if (refresh) dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.checkAgain(config.endpointUrl)
        configurationFailures[config.connectionUid]?.let { failedKey ->
            if (!refresh && failedKey == key) throw McpEndpointConfigurationException()
            configurationFailures.remove(config.connectionUid, failedKey)
            resetHealth(config.connectionUid)
        }
        providerCooldowns[config.connectionUid]?.let { (blockedKey, until) ->
            if (blockedKey != key || refresh) {
                providerCooldowns.remove(config.connectionUid)
                resetHealth(config.connectionUid)
            } else if (until == Long.MAX_VALUE) {
                throw McpAuthenticationRequiredException()
            } else if (until > nowMs()) {
                throw McpBackoffException(config.connectionUid, until)
            }
        }
        val transport = modern ?: return null
        val retryAt = healthSnapshot(config.connectionUid).nextRetryAtMs
        if (!refresh && retryAt != null && retryAt > nowMs()) throw McpBackoffException(config.connectionUid, retryAt)
        return transport.takeIf { it.supports(config, refresh) }
    }

    private val catalogue = dev.chungjungsoo.gptmobile.data.readiness.ReadinessSnapshotStore<String, List<Tool>>(clock = { System.nanoTime() / 1_000_000 })
    private val catalogueIdentities = ConcurrentHashMap<String, Pair<Long, String>>()
    private val validatedCatalogues = ConcurrentHashMap<String, Pair<String, List<Tool>>>()
    private val restoredCatalogues = ConcurrentHashMap.newKeySet<String>()
    private val catalogueProducers = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<List<Tool>>>()

    fun catalogueDiscovery(connectionUid: String): ToolCatalogConnectionDiscovery {
        val health = healthSnapshot(connectionUid)
        val policyEpoch = dev.chungjungsoo.gptmobile.data.readiness.ReadinessInvalidation.epoch
        val networkEpoch = dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.networkEpoch
        val currentKey = transportConfigurationKeys[connectionUid]?.let { "$it:$policyEpoch" }
        val refreshing = health.toolReadiness == McpToolReadiness.DISCOVERING || currentKey?.let { catalogueProducers[it]?.isActive } == true
        val validatedNow = currentKey?.endsWith("|$networkEpoch:$policyEpoch") == true && validatedCatalogues[connectionUid]?.first == currentKey
        val state = when {
            health.state == McpConnectionHealthState.AUTHENTICATION_REQUIRED -> ToolCatalogDiscoveryState.AUTHENTICATION_REQUIRED
            refreshing -> ToolCatalogDiscoveryState.PENDING
            health.nextRetryAtMs == Long.MAX_VALUE -> ToolCatalogDiscoveryState.CONFIGURATION_REQUIRED
            (health.nextRetryAtMs ?: 0L) > nowMs() -> ToolCatalogDiscoveryState.RECOVERY_BACKOFF
            health.state in setOf(McpConnectionHealthState.DEGRADED, McpConnectionHealthState.UNREACHABLE) && health.lastFailureAtMs != null -> ToolCatalogDiscoveryState.FAILED
            validatedNow && health.toolReadiness == McpToolReadiness.READY && health.state == McpConnectionHealthState.CONNECTED -> ToolCatalogDiscoveryState.READY
            health.availableToolCount != null -> ToolCatalogDiscoveryState.STALE
            else -> ToolCatalogDiscoveryState.NOT_DISCOVERED
        }
        val revision = dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook.digest("$state|${health.lastSuccessAtMs}|${health.lastFailureAtMs}|${health.availableToolCount}|${health.lastErrorCode}|$policyEpoch|$networkEpoch")
        return ToolCatalogConnectionDiscovery(connectionUid, state, revision, health.availableToolCount, health.lastErrorCode, health.nextRetryAtMs)
    }

    fun peekTools(config: McpConnectionConfig): List<Tool>? = catalogue.peek(config.validatedKey() + ":" + dev.chungjungsoo.gptmobile.data.readiness.ReadinessInvalidation.epoch)?.value

    suspend fun peekToolsForConnection(uid: String, endpointUrl: String? = null, allowCleartext: Boolean = false): List<Tool>? {
        val validatedKey = endpointUrl?.let { McpConnectionConfig(uid, it, allowCleartext).validatedKey() }
        val health = healthSnapshot(uid)
        if (health.state == McpConnectionHealthState.AUTHENTICATION_REQUIRED || protocolFailures.containsKey(uid)) return null
        if (validatedKey != null && configurationFailures[uid] == validatedKey) return null
        val identity = catalogueIdentities[uid]?.takeIf {
            it.first == dev.chungjungsoo.gptmobile.data.readiness.ReadinessInvalidation.epoch &&
                (validatedKey == null || it.second.substringBefore('|') == validatedKey.substringBefore('|'))
        }
        val networkEpoch = dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.networkEpoch
        val current = identity?.takeIf { it.second.endsWith("|$networkEpoch:${it.first}") }?.let { catalogue.peek(it.second) }
        // Keep the last-known schema usable during transient network recovery. The cache
        // is scoped to the same endpoint, credentials and permission epoch, and expires
        // sooner than the normal readiness snapshot. Calls still pass through live
        // authorization, trust and transport checks before execution.
        val staleNetworkEligible = health.state in setOf(
            McpConnectionHealthState.DEGRADED,
            McpConnectionHealthState.UNREACHABLE,
            McpConnectionHealthState.RECOVERING
        )
        val snapshot = current ?: identity?.takeIf { staleNetworkEligible }?.let { catalogue.peek(it.second, 15 * 60_000L) }
        kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.readiness.ReadinessCacheObservation]?.read(snapshot != null, snapshot?.let { System.nanoTime() / 1_000_000 - it.observedAt > 300_000 } == true)
        return snapshot?.value
    }

    fun catalogueAvailability(config: McpConnectionConfig): McpCatalogueAvailability {
        val key = config.validatedKey() + ":" + dev.chungjungsoo.gptmobile.data.readiness.ReadinessInvalidation.epoch
        val snapshot = catalogue.peek(key)
        val state = healthSnapshot(config.connectionUid)
        val authorized = when {
            state.state == McpConnectionHealthState.AUTHENTICATION_REQUIRED -> false
            state.lastSuccessAtMs != null -> true
            else -> null
        }
        val sameConfiguration = transportConfigurationKeys[config.connectionUid] == config.validatedKey()
        val recentResponse = state.lastSuccessAtMs?.let { nowMs() - it in 0..60_000L } == true
        val healthy = sameConfiguration && recentResponse && state.state == McpConnectionHealthState.CONNECTED
        val validated = validatedCatalogues[config.connectionUid]
        val discovered = validated != null && validated.first == key && snapshot?.value == validated.second
        return McpCatalogueAvailability(snapshot != null, authorized.takeIf { sameConfiguration }, healthy, snapshot != null && healthy && authorized == true && discovered, catalogueProducers.containsKey(key), snapshot?.observedAt, state.executionVerified.takeIf { sameConfiguration }, state.executionObservedAtMs.takeIf { sameConfiguration })
    }

    fun clearReadinessCache(cancelProducers: Boolean = true) {
        catalogueIdentities.clear()
        validatedCatalogues.clear()
        _health.update { current ->
            current.mapValues { (_, value) -> value.copy(toolReadiness = if (value.availableToolCount == null) McpToolReadiness.NOT_DISCOVERED else McpToolReadiness.STALE) }
        }
        restoredCatalogues.clear()
        catalogue.invalidate()
        if (cancelProducers) {
            synchronized(catalogueProducers) {
                catalogueProducers.values.forEach { it.cancel() }
                catalogueProducers.clear()
            }
        }
    }

    suspend fun listTools(config: McpConnectionConfig, forceRefresh: Boolean = false, awaitCompletion: Boolean = false, passiveBackoff: Boolean = false): List<Tool> {
        val policyEpoch = dev.chungjungsoo.gptmobile.data.readiness.ReadinessInvalidation.epoch
        protocolFailures[config.connectionUid]?.takeIf { !forceRefresh && it.first == config.catalogueIdentity() }?.let { (_, failure) ->
            if (passiveBackoff && !awaitCompletion) return emptyList()
            throw failure
        }
        if (!forceRefresh && healthSnapshot(config.connectionUid).state == McpConnectionHealthState.AUTHENTICATION_REQUIRED && providerCooldowns[config.connectionUid]?.first == config.validatedKey()) throw McpAuthenticationRequiredException()
        val key = config.validatedKey() + ":" + policyEpoch
        val ready = if (forceRefresh || !catalogueAvailability(config).executionAvailable) null else catalogue.peek(key, 300_000)
        kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.readiness.ReadinessCacheObservation]?.read(ready != null)
        ready?.let {
            catalogueIdentities[config.connectionUid] = policyEpoch to key
            return it.value
        }
        val connectionHealth = healthSnapshot(config.connectionUid)
        if (passiveBackoff && !forceRefresh && !awaitCompletion && (connectionHealth.nextRetryAtMs ?: 0) > nowMs()) {
            // Optional discovery must not wait on a known-down endpoint. Its existing
            // health controller owns recovery; execution still validates live access.
            return peekToolsForConnection(config.connectionUid, config.endpointUrl, config.allowCleartext).orEmpty()
        }
        // Restore metadata only for the exact endpoint and credential identity. A saved
        // schema is never a handshake/authorization receipt; live calls still validate.
        val restoreEpoch = catalogue.epoch
        if (restoredCatalogues.size > 64) restoredCatalogues.clear()
        val restored = if (!forceRefresh && !awaitCompletion && restoredCatalogues.add(key)) {
            try {
                savedCatalogues?.load(config.connectionUid, config.catalogueIdentity(), nowMs())?.takeIf {
                    policyEpoch == dev.chungjungsoo.gptmobile.data.readiness.ReadinessInvalidation.epoch && restoreEpoch == catalogue.epoch
                }?.let { tools ->
                    val bytes = tools.sumOf { it.toString().toByteArray().size }
                    if (catalogue.restore(key, tools, bytes, restoreEpoch)) {
                        catalogueIdentities[config.connectionUid] = policyEpoch to key
                        tools
                    } else {
                        catalogue.peek(key)?.value
                    }
                }
            } catch (cancelled: CancellationException) {
                restoredCatalogues.remove(key)
                throw cancelled
            }
        } else {
            null
        }
        val producer = synchronized(catalogueProducers) {
            // Awaiters can observe a terminal producer before its completion handler
            // removes it. Only share live discovery; explicit refresh must not inherit
            // an already-completed failure from that cleanup window.
            catalogueProducers[key]?.takeUnless { it.isCompleted } ?: catalogueScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                withTimeout(catalogueTimeoutMs) {
                    val epoch = catalogue.epoch
                    val tools = fetchCatalogue(config, forceRefresh).toList()
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val bytes = validateMcpCatalogue(tools)
                    check(policyEpoch == dev.chungjungsoo.gptmobile.data.readiness.ReadinessInvalidation.epoch) { "MCP readiness invalidated during acquisition." }
                    if (catalogue.publish(key, tools, bytes, epoch)) {
                        if (catalogueIdentities.size >= 16) catalogueIdentities.keys.firstOrNull()?.let(catalogueIdentities::remove)
                        catalogueIdentities[config.connectionUid] = policyEpoch to key
                        try {
                            savedCatalogues?.save(config.connectionUid, config.catalogueIdentity(), tools, nowMs())
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            AppLogRecorder.record("MCP", "CATALOG_PERSIST_UNAVAILABLE · connection=${config.connectionUid} · reason=${failure.javaClass.simpleName} · liveCatalogRetained=true", "W")
                        }
                    }
                    tools
                }
            }.also { next ->
                catalogueProducers[key] = next
                next.invokeOnCompletion { synchronized(catalogueProducers) { catalogueProducers.remove(key, next) } }
            }
        }
        producer.start()
        if (restored != null) return restored
        // Interactive resolution may use a cached snapshot, but a health check must
        // observe the completed catalogue before claiming the endpoint has no tools.
        val result = if (awaitCompletion) {
            try {
                withTimeout(catalogueTimeoutMs) { producer.await() }
            } catch (deadline: kotlinx.coroutines.TimeoutCancellationException) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                throw McpCatalogueTimeoutException(catalogueTimeoutMs)
            }
        } else {
            withTimeoutOrNull(2_000L) { producer.await() } ?: catalogue.peek(key)?.value.orEmpty()
        }
        if (catalogue.peek(key) != null) catalogueIdentities[config.connectionUid] = policyEpoch to key
        return result
    }

    private suspend fun fetchCatalogue(config: McpConnectionConfig, forceRefresh: Boolean): List<Tool> {
        val observation = healthOrder.begin(config.connectionUid, config.validatedKey(), config.configurationRevision)
        healthOrder.publish(observation) {
            _health.update { current ->
                val previous = current[config.connectionUid] ?: McpConnectionHealth()
                current + (config.connectionUid to previous.copy(toolReadiness = if (previous.toolsReady) McpToolReadiness.READY else McpToolReadiness.DISCOVERING))
            }
        }
        return try {
            listToolsUnchecked(config, forceRefresh, observation)
        } catch (cancelled: CancellationException) {
            AppLogRecorder.record("MCP", "CATALOG_ACQUISITION_CANCELLED · connection=${config.connectionUid} · owner=APPLICATION_DISCOVERY · reason=${if (cancelled is kotlinx.coroutines.TimeoutCancellationException) "CATALOG_DEADLINE" else "OWNER_CANCELLED_OR_INVALIDATED"} · transportFailure=${cancelled is kotlinx.coroutines.TimeoutCancellationException}")
            if (cancelled is kotlinx.coroutines.TimeoutCancellationException) recordFailure(config.connectionUid, McpCatalogueTimeoutException(catalogueTimeoutMs), observation = observation, stage = "discovery")
            throw cancelled
        } catch (error: Exception) {
            if (error is McpBackoffException) throw error
            // The remembered endpoint gate is not a new HTTP exchange or observation.
            if (error is McpEndpointConfigurationException && error.httpStatus == null) throw error
            if (error is McpProtocolConfigurationException) {
                val previous = protocolFailures[config.connectionUid]
                if (previous?.first == config.catalogueIdentity() && previous.second === error) throw error
                healthOrder.publish(observation) {
                    if (protocolFailures.size >= 64) protocolFailures.keys.firstOrNull()?.let(protocolFailures::remove)
                    protocolFailures[config.connectionUid] = config.catalogueIdentity() to error
                }
            }
            if (error !is McpProtocolConfigurationException) dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.recordFailure(config.endpointUrl, error)
            if (error.isMcpUnauthorized()) providerCooldowns[config.connectionUid] = config.validatedKey() to Long.MAX_VALUE
            recordFailure(config.connectionUid, error, observation = observation, stage = "discovery")
            throw error
        } finally {
            healthOrder.publish(observation) {
                _health.update { current ->
                    val previous = current[config.connectionUid]
                    if (previous?.toolReadiness != McpToolReadiness.DISCOVERING) {
                        current
                    } else {
                        current + (config.connectionUid to previous.copy(toolReadiness = if (previous.availableToolCount == null) McpToolReadiness.NOT_DISCOVERED else McpToolReadiness.STALE))
                    }
                }
            }
        }
    }

    private suspend fun listToolsUnchecked(config: McpConnectionConfig, forceRefresh: Boolean, observation: McpHealthObservationOrder.Token): List<Tool> {
        modernTransport(config, forceRefresh)?.let { transport ->
            return transport.listTools(config).also {
                validateMcpCatalogue(it)
                dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.recordHandshakeSuccess(config.endpointUrl)
                recordSuccess(config.connectionUid, availableToolCount = it.size, observation = observation, discoveredTools = it)
                recordCatalog(config, it)
            }
        }
        var observedNetwork = false
        val result = withSession(config, bypassBackoff = forceRefresh, recordFailures = false, recordResponses = false, observation = observation) { session ->
            session.toolCatalogMutex.withLock {
                val now = nowMs()
                session.toolCatalog?.takeIf { !forceRefresh && catalogueAvailability(config).executionAvailable && now - it.loadedAtMs < TOOL_CATALOG_TTL_MS }?.tools ?: withMcpConnectivityLease(config) {
                    val tools = mutableListOf<Tool>()
                    val seenCursors = mutableSetOf<String>()
                    var pageCount = 0
                    var cursor: String? = null
                    do {
                        check(++pageCount <= MAX_TOOL_PAGES) { "MCP server returned too many tool pages." }
                        val page = session.client.listTools(
                            request = if (cursor == null) ListToolsRequest() else ListToolsRequest(PaginatedRequestParams(cursor))
                        )
                        dev.chungjungsoo.gptmobile.data.research.academic.ResearchConnectivityGate.shared.success(URI(config.endpointUrl).host)
                        tools += page.tools
                        check(tools.size <= MAX_DISCOVERED_TOOLS) { "MCP server returned too many tools." }
                        cursor = page.nextCursor
                        check(cursor == null || seenCursors.add(cursor)) { "MCP server returned a repeated tools cursor." }
                    } while (cursor != null)
                    tools.toList().also {
                        validateMcpCatalogue(it)
                        observedNetwork = true
                        session.toolCatalog = ToolCatalog(it, nowMs())
                    }
                }
            }
        }
        if (observedNetwork) dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.recordHandshakeSuccess(config.endpointUrl)
        if (observedNetwork) recordSuccess(config.connectionUid, availableToolCount = result.size, observation = observation, discoveredTools = result)
        recordCatalog(config, result)
        return result
    }

    private suspend fun recordCatalog(config: McpConnectionConfig, tools: List<Tool>) {
        val fingerprint = dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository.digest(tools.sortedBy { it.name }.joinToString { it.toString() })
        val changed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { trust?.observeCatalog(config.connectionUid, fingerprint) == true }
        if (changed) dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("MCP", "Tool catalog refreshed · savedPermissions=retained")
    }

    suspend fun callTool(
        config: McpConnectionConfig,
        toolName: String,
        arguments: JsonObject,
        callId: String? = null,
        metadata: JsonObject? = null
    ): CallToolResult {
        val key = config.validatedKey()
        providerCooldowns[config.connectionUid]?.let { (blockedKey, until) ->
            if (blockedKey == key && until == Long.MAX_VALUE) throw McpAuthenticationRequiredException()
            if (blockedKey == key && until > nowMs()) throw McpBackoffException(config.connectionUid, until)
            providerCooldowns.remove(config.connectionUid)
            resetHealth(config.connectionUid)
        }
        val executableTools = if (catalogueAvailability(config).executionAvailable) {
            peekTools(config).orEmpty()
        } else {
            listTools(config, awaitCompletion = true)
        }
        if (!catalogueAvailability(config).executionAvailable) throw McpLocalToolRejection("MCP_DISCOVERY_REQUIRED", "MCP_DISCOVERY_REQUIRED: refresh the live tool catalogue before execution.")
        if (executableTools.none { it.name == toolName }) throw McpLocalToolRejection("UNKNOWN_TOOL", "MCP_TOOL_UNAVAILABLE: the live catalogue no longer advertises this tool.")
        val observation = healthOrder.begin(config.connectionUid, key, config.configurationRevision)
        fun recordQuota(message: String) {
            if (isMcpQuotaFailure(message)) {
                providerCooldowns[config.connectionUid] = key to (nowMs() + 5 * 60_000L)
                recordFailure(config.connectionUid, IllegalStateException("Provider quota exhausted. Try later or configure its required credentials."), nowMs() + 5 * 60_000L, observation = observation)
            }
        }
        val statisticsContext = kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsContext]
        val attemptId = java.util.UUID.randomUUID().toString()
        var outcome = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.UNKNOWN
        return try {
            val result = modernTransport(config)?.let { it.callTool(config, toolName, arguments, callId, metadata) }
                ?: withSession(config, retryStale = false, recordFailures = false, observation = observation) {
                    it.client.callTool(
                        kotlinx.serialization.json.Json.decodeFromJsonElement(
                            io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.serializer(),
                            kotlinx.serialization.json.buildJsonObject {
                                put("method", kotlinx.serialization.json.JsonPrimitive("tools/call"))
                                put(
                                    "params",
                                    kotlinx.serialization.json.buildJsonObject {
                                        put("name", kotlinx.serialization.json.JsonPrimitive(toolName))
                                        put("arguments", arguments)
                                        metadata?.let { put("_meta", it) }
                                    }
                                )
                            }
                        )
                    )
                }
            outcome = if (result.isError == true) dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.FAILURE else dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.SUCCESS
            if (result.isError == true) {
                healthOrder.publish(observation) {
                    _health.update { current ->
                        current + (config.connectionUid to (current[config.connectionUid] ?: McpConnectionHealth()).copy(executionVerified = false, executionObservedAtMs = nowMs()))
                    }
                }
                val failure = result.toString()
                if (isMcpCredentialFailure(failure)) {
                    providerCooldowns[config.connectionUid] = key to Long.MAX_VALUE
                    recordFailure(config.connectionUid, McpAuthenticationRequiredException(), observation = observation)
                } else {
                    recordQuota(failure)
                }
            }
            if (result.isError != true) recordSuccess(config.connectionUid, observation = observation, executionVerified = true)
            mediaStore?.materialize(result) ?: result
        } catch (cancelled: CancellationException) {
            outcome = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.CANCELED
            throw cancelled
        } catch (error: Exception) {
            outcome = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.FAILURE
            if (error is McpBackoffException) throw error
            if (error.isMcpUnauthorized()) {
                val message = "MCP authentication or access failed. Open Manage connection for this provider and update its credentials."
                providerCooldowns[config.connectionUid] = key to Long.MAX_VALUE
                recordFailure(config.connectionUid, IllegalStateException(message, error), observation = observation)
                throw IllegalStateException(message, error)
            }
            dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.recordFailure(config.endpointUrl, error)
            recordFailure(config.connectionUid, error, observation = observation)
            recordQuota(error.message.orEmpty())
            throw error
        } finally {
            if (statisticsContext != null) withContext(NonCancellable) { statisticsContext.repository.attempt(statisticsContext.invocationId, attemptId, outcome) }
        }
    }

    suspend fun refreshTask(config: McpConnectionConfig, record: dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRecord, cancel: Boolean = false, answerInputs: Boolean = false): dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRecord {
        config.validatedKey()
        return requireNotNull(modernTransport(config)) { "This task requires modern MCP." }.refreshTask(config, record, cancel, answerInputs)
    }

    suspend fun browse(config: McpConnectionConfig): McpBrowserData {
        modernTransport(config)?.let { return it.browse(config) }
        return withSession(config) { session ->
            val client = session.client
            val resources = mutableListOf<io.modelcontextprotocol.kotlin.sdk.types.Resource>()
            val prompts = mutableListOf<io.modelcontextprotocol.kotlin.sdk.types.Prompt>()
            if (client.serverCapabilities?.resources != null) {
                var cursor: String? = null
                val seen = mutableSetOf<String>()
                do {
                    val page = client.listResources(io.modelcontextprotocol.kotlin.sdk.types.ListResourcesRequest(PaginatedRequestParams(cursor)))
                    resources += page.resources
                    cursor = page.nextCursor
                    check(resources.size <= 500 && (cursor == null || seen.add(cursor)) && seen.size <= 20) { "Resource catalog is too large or repeats pages." }
                } while (cursor != null)
            }
            if (client.serverCapabilities?.prompts != null) {
                var cursor: String? = null
                val seen = mutableSetOf<String>()
                do {
                    val page = client.listPrompts(io.modelcontextprotocol.kotlin.sdk.types.ListPromptsRequest(PaginatedRequestParams(cursor)))
                    prompts += page.prompts
                    cursor = page.nextCursor
                    check(prompts.size <= 500 && (cursor == null || seen.add(cursor)) && seen.size <= 20) { "Prompt catalog is too large or repeats pages." }
                } while (cursor != null)
            }
            McpBrowserData(resources, prompts, client.serverVersion?.name.orEmpty(), System.currentTimeMillis())
        }
    }

    suspend fun readResource(config: McpConnectionConfig, uri: String): String {
        modernTransport(config)?.let { return it.readResource(config, uri) }
        return withSession(config) { session ->
            val result = session.client.readResource(io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest(io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams(uri)))
            result.contents.filterIsInstance<io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents>().joinToString("\n") { it.text.take(32000) }.take(64000)
        }
    }

    suspend fun getPrompt(config: McpConnectionConfig, name: String, arguments: Map<String, String>): String {
        modernTransport(config)?.let { return it.getPrompt(config, name, arguments) }
        return withSession(config) { session ->
            val result = session.client.getPrompt(io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest(io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams(name, arguments)))
            result.messages.joinToString("\n\n") { message ->
                "${message.role}: ${(message.content as? io.modelcontextprotocol.kotlin.sdk.types.TextContent)?.text.orEmpty().take(32000)}"
            }.take(64000)
        }
    }

    fun healthSnapshot(connectionUid: String): McpConnectionHealth =
        _health.value[connectionUid] ?: McpConnectionHealth()

    fun resetHealth(connectionUid: String) {
        validatedCatalogues.remove(connectionUid)
        _health.update { current -> current - connectionUid }
    }

    suspend fun probe(config: McpConnectionConfig): Boolean = try {
        config.validateLocalConfiguration()
        dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.checkAgain(config.endpointUrl)
        dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.probe(config.endpointUrl)
        listTools(config, forceRefresh = true, awaitCompletion = true)
        catalogueAvailability(config).executionAvailable
    } catch (deadline: kotlinx.coroutines.TimeoutCancellationException) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        false
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (failure is dev.chungjungsoo.gptmobile.data.network.LocalServiceUnavailableException) {
            recordFailure(config.connectionUid, failure, observation = healthOrder.begin(config.connectionUid, config.validatedKey(), config.configurationRevision), stage = "connection")
        }
        false
    }

    private fun recordSuccess(
        connectionUid: String,
        latencyMs: Long? = null,
        availableToolCount: Int? = null,
        observation: McpHealthObservationOrder.Token,
        discoveredTools: List<Tool>? = null,
        executionVerified: Boolean? = null
    ) = healthOrder.publish(observation) {
        // Catalog/transport success cannot clear an authorization or quota block for this credential.
        val cooldown = providerCooldowns[connectionUid]
        if (cooldown?.first == observation.key && cooldown.second > nowMs()) {
            _health.update { current ->
                val previous = current[connectionUid] ?: McpConnectionHealth()
                current + (connectionUid to previous.copy(latencyMs = latencyMs ?: previous.latencyMs, availableToolCount = availableToolCount ?: previous.availableToolCount))
            }
            return@publish
        }
        discoveredTools?.let { tools ->
            validatedCatalogues[connectionUid] = (observation.key + ":" + dev.chungjungsoo.gptmobile.data.readiness.ReadinessInvalidation.epoch) to tools.toList()
        }
        val now = nowMs()
        statistics?.observeHealth(connectionUid, "CONNECTED", "SERVER_RESPONSE", now)
        var previousState = McpConnectionHealthState.RECOVERING
        var catalogChanged = false
        _health.update { current ->
            val previous = current[connectionUid] ?: McpConnectionHealth()
            previousState = previous.state
            catalogChanged = availableToolCount != null && availableToolCount != previous.availableToolCount
            current + Pair(
                connectionUid,
                previous.copy(
                    state = McpConnectionHealthState.CONNECTED,
                    lastSuccessAtMs = now,
                    executionVerified = executionVerified ?: previous.executionVerified,
                    executionObservedAtMs = if (executionVerified != null) now else previous.executionObservedAtMs,
                    latencyMs = latencyMs ?: previous.latencyMs,
                    consecutiveFailures = 0,
                    nextRetryAtMs = null,
                    connectionHealth = dev.chungjungsoo.gptmobile.data.network.ConnectionHealth.HEALTHY,
                    lastError = null,
                    lastErrorCode = null,
                    failureConfigurationRevision = null,
                    lastHttpStatus = null,
                    lastValidationRule = null,
                    lastValidationField = null,
                    lastDispatchEvidence = null,
                    lastRetryDecision = null,
                    failedStage = null,
                    availableToolCount = availableToolCount ?: previous.availableToolCount,
                    toolReadiness = when {
                        discoveredTools != null -> McpToolReadiness.READY
                        validatedCatalogues[connectionUid]?.first == observation.key + ":" + dev.chungjungsoo.gptmobile.data.readiness.ReadinessInvalidation.epoch -> McpToolReadiness.READY
                        else -> previous.toolReadiness
                    }
                )
            )
        }
        if (previousState != McpConnectionHealthState.CONNECTED || catalogChanged) {
            AppLogRecorder.record(
                "MCP",
                "Health · connection=$connectionUid · state=CONNECTED · latencyMs=${healthSnapshot(connectionUid).latencyMs ?: "unknown"} · tools=${healthSnapshot(connectionUid).availableToolCount ?: "Not discovered"} · toolReadiness=${healthSnapshot(connectionUid).toolReadiness}"
            )
        }
    }

    private fun recordFailure(connectionUid: String, error: Throwable, retryAtMs: Long? = null, observation: McpHealthObservationOrder.Token, stage: String = "execution") {
        if (dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.classify(error) == dev.chungjungsoo.gptmobile.data.network.ConnectionHealth.DNS_RECOVERY) return
        healthOrder.publish(observation) {
            validatedCatalogues.remove(connectionUid)
            modern?.close(connectionUid)
            // A shared DNS lease wait is recovery coordination, not another failed
            // connection attempt. Do not amplify it into an endpoint circuit breaker.
            val now = nowMs()
            val causes = generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(8).toList()
            val protocolFailure = causes.filterIsInstance<McpProtocolConfigurationException>().firstOrNull()
            val localFailure = causes.filterIsInstance<McpLocalValidationException>().firstOrNull()
            val httpStatus = protocolFailure?.httpStatus ?: causes.firstNotNullOfOrNull {
                when (it) {
                    is ModernMcpError -> it.status
                    is StreamableHttpError -> it.code
                    is McpEndpointConfigurationException -> it.httpStatus
                    else -> null
                }
            }
            val safeDetail = (localFailure ?: protocolFailure)?.message
                ?: if (causes.any { it is StreamableHttpError }) "MCP transport request failed; HTTP status=${httpStatus ?: "unavailable"}. Check the connection settings and server status." else error.message.orEmpty()
            var updated = McpConnectionHealth()
            _health.update { current ->
                val previous = current[connectionUid] ?: McpConnectionHealth()
                val failures = previous.consecutiveFailures + 1
                val delay = retryDelayMs(failures) + retryJitterMs().coerceIn(0, 1000)
                val authenticationRequired = error.isMcpUnauthorized()
                val failureClass = dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.classify(error)
                val tlsFailed = failureClass == dev.chungjungsoo.gptmobile.data.network.ConnectionHealth.TLS_FAILED
                val requiresReconnect = error is McpEndpointConfigurationException || protocolFailure != null || localFailure != null || authenticationRequired || tlsFailed
                val errorCode = when {
                    localFailure != null -> localFailure.diagnosticCode
                    protocolFailure != null -> protocolFailure.diagnosticCode
                    error is McpEndpointConfigurationException -> "MCP_ENDPOINT_CONFIGURATION"
                    error is McpCatalogueTimeoutException -> "MCP_CATALOG_TIMEOUT"
                    authenticationRequired -> "MCP_AUTHENTICATION_REQUIRED"
                    tlsFailed -> "TLS_FAILURE"
                    failureClass != dev.chungjungsoo.gptmobile.data.network.ConnectionHealth.UNKNOWN -> failureClass.name
                    else -> "MCP_FAILURE_UNCLASSIFIED"
                }
                val failedStage = localFailure?.phase ?: protocolFailure?.let {
                    when (it.diagnosticCode) {
                        "MCP_INITIALIZATION_SCHEMA_INVALID" -> "initialization"
                        "MCP_DISCOVERY_SCHEMA_INVALID", "MCP_PROTOCOL_VERSION_UNSUPPORTED", "MCP_DISCOVERY_CONFIGURATION" -> "protocol_negotiation"
                        else -> null
                    }
                } ?: when (failureClass) {
                    dev.chungjungsoo.gptmobile.data.network.ConnectionHealth.DNS_FAILED -> "dns"
                    dev.chungjungsoo.gptmobile.data.network.ConnectionHealth.TLS_FAILED -> "tls"
                    dev.chungjungsoo.gptmobile.data.network.ConnectionHealth.CONNECT_TIMEOUT,
                    dev.chungjungsoo.gptmobile.data.network.ConnectionHealth.CONNECTION_REFUSED,
                    dev.chungjungsoo.gptmobile.data.network.ConnectionHealth.ROUTE_UNAVAILABLE -> "connection"
                    dev.chungjungsoo.gptmobile.data.network.ConnectionHealth.MCP_HANDSHAKE_FAILED -> "initialization"
                    else -> stage
                }
                updated = previous.copy(
                    executionVerified = if (stage == "execution") false else previous.executionVerified,
                    executionObservedAtMs = if (stage == "execution") nowMs() else previous.executionObservedAtMs,
                    state = when {
                        authenticationRequired -> McpConnectionHealthState.AUTHENTICATION_REQUIRED
                        error is McpCatalogueTimeoutException -> McpConnectionHealthState.DEGRADED
                        requiresReconnect || failures >= CIRCUIT_BREAKER_FAILURES -> McpConnectionHealthState.UNREACHABLE
                        else -> McpConnectionHealthState.DEGRADED
                    },
                    lastFailureAtMs = now,
                    consecutiveFailures = failures,
                    nextRetryAtMs = if (requiresReconnect) Long.MAX_VALUE else retryAtMs ?: (now + delay),
                    lastErrorCode = errorCode,
                    lastError = "[$failedStage] " + dev.chungjungsoo.gptmobile.data.diagnostics.diagnosticMessageForStorage(safeDetail).take(1024),
                    failureConfigurationRevision = observation.configurationRevision?.takeIf { it.matches(Regex("[a-f0-9]{64}")) },
                    lastHttpStatus = httpStatus?.takeIf { it in 100..599 },
                    lastValidationRule = localFailure?.validationRule ?: protocolFailure?.validationRule,
                    lastValidationField = localFailure?.validationField ?: protocolFailure?.validationField,
                    lastDispatchEvidence = when {
                        localFailure != null -> localFailure.dispatchEvidence
                        httpStatus != null -> "CONFIRMED"
                        else -> "UNKNOWN"
                    },
                    lastRetryDecision = localFailure?.retryDecision ?: if (requiresReconnect) "AFTER_CONFIGURATION_CHANGE" else "AFTER_BACKOFF",
                    failedStage = failedStage,
                    connectionHealth = failureClass,
                    toolReadiness = if (previous.availableToolCount == null) McpToolReadiness.NOT_DISCOVERED else McpToolReadiness.STALE
                )
                current + (connectionUid to updated)
            }
            statistics?.observeHealth(connectionUid, updated.state.name, "PASSIVE_FAILURE", now)
            AppLogRecorder.record(
                "MCP",
                "Health · connection=$connectionUid · state=${updated.state} · failures=${updated.consecutiveFailures} · retryAtMs=${updated.nextRetryAtMs} · failureClass=${updated.connectionHealth} · failedStage=${updated.failedStage} · errorCode=${updated.lastErrorCode} · retryable=${updated.failureRetryable} · error=${updated.lastError.orEmpty()}",
                "W"
            )
        }
    }

    private fun markRecovering(connectionUid: String) {
        _health.update { current ->
            val previous = current[connectionUid] ?: McpConnectionHealth()
            current + (connectionUid to previous.copy(state = McpConnectionHealthState.RECOVERING))
        }
    }

    private fun retryDelayMs(failures: Int): Long = when (failures) {
        1 -> 5_000L
        2 -> 15_000L
        3 -> 30_000L
        4 -> 60_000L
        5 -> 120_000L
        6 -> 240_000L
        else -> 300_000L
    }

    suspend fun close(connectionUid: String) {
        healthOrder.invalidate(connectionUid)
        protocolFailures.remove(connectionUid)
        resetHealth(connectionUid)
        statistics?.observeHealth(connectionUid, "NOT_CHECKED", "CONFIG_CHANGED", nowMs())
        modern?.close(connectionUid)
        val session = takeSession(connectionUid) ?: return
        runCatching { session.client.close() }
    }

    suspend fun closeAll() {
        protocolFailures.clear()
        clearReadinessCache()
        healthOrder.invalidateAll().forEach { uid ->
            resetHealth(uid)
            statistics?.observeHealth(uid, "NOT_CHECKED", "CONFIG_CHANGED", nowMs())
        }
        modern?.closeAll()
        mutex.lock()
        val active = try {
            sessions.values.toList().also { sessions.clear() }
        } finally {
            mutex.unlock()
        }
        active.forEach { session -> runCatching { session.client.close() } }
    }

    private suspend fun <T> withSession(
        config: McpConnectionConfig,
        bypassBackoff: Boolean = false,
        retryStale: Boolean = true,
        recordFailures: Boolean = true,
        recordResponses: Boolean = true,
        observation: McpHealthObservationOrder.Token = healthOrder.begin(config.connectionUid, config.validatedKey(), config.configurationRevision),
        block: suspend (Session) -> T
    ): T {
        var staleSessionRetries = 0
        while (true) {
            val session = session(config, bypassBackoff || staleSessionRetries > 0, recordFailures, observation)
            val startedAtMs = nowMs()
            try {
                return block(session).also {
                    if (recordResponses) recordSuccess(config.connectionUid, latencyMs = nowMs() - startedAtMs, observation = observation)
                }
            } catch (error: CancellationException) {
                withContext(NonCancellable) { invalidate(config.connectionUid, session) }
                throw error
            } catch (error: Exception) {
                val staleSession = error.isMcpStaleSession()
                val interruptedRead = error.isMcpInterruptedRead()
                invalidate(config.connectionUid, session)
                if (retryStale && (staleSession || interruptedRead) && staleSessionRetries++ < MAX_STALE_SESSION_RETRIES) {
                    healthOrder.publish(observation) { markRecovering(config.connectionUid) }
                    AppLogRecorder.record(
                        "MCP",
                        "Read session interrupted · connection=${config.connectionUid} · stale=$staleSession · retry=$staleSessionRetries/$MAX_STALE_SESSION_RETRIES · reconnecting without provider backoff",
                        "W"
                    )
                    kotlinx.coroutines.delay((250L shl (staleSessionRetries - 1).coerceIn(0, 3)) + retryJitterMs().coerceIn(0, 250))
                    continue
                }
                if (recordFailures && !error.isMcpUnauthorized()) recordFailure(config.connectionUid, error, observation = observation)
                throw error
            }
        }
    }

    private suspend fun session(config: McpConnectionConfig, bypassBackoff: Boolean, recordFailures: Boolean, observation: McpHealthObservationOrder.Token): Session {
        val key = config.validatedKey()
        while (true) {
            val created = CompletableDeferred<Session>()
            var stale: Session? = null
            var awaiting: CompletableDeferred<Session>? = null
            mutex.lock()
            try {
                sessions[config.connectionUid]?.takeIf { it.key == key }?.let { return it }
                inFlight[config.connectionUid]?.let { existing ->
                    awaiting = existing.deferred
                } ?: run {
                    val health = _health.value[config.connectionUid]
                    val retryAt = health?.nextRetryAtMs
                    if (!bypassBackoff && retryAt != null && retryAt > nowMs()) {
                        AppLogRecorder.record(
                            "MCP",
                            "Circuit open · connection=${config.connectionUid} · state=${health.state} · failures=${health.consecutiveFailures} · retryAtMs=$retryAt",
                            "W"
                        )
                        throw McpBackoffException(config.connectionUid, retryAt)
                    }
                    if ((health?.consecutiveFailures ?: 0) > 0) healthOrder.publish(observation) { markRecovering(config.connectionUid) }
                    stale = sessions.remove(config.connectionUid)
                    inFlight[config.connectionUid] = InFlight(key, created)
                }
            } finally {
                mutex.unlock()
            }
            awaiting?.await()
            if (awaiting != null) continue
            withContext(NonCancellable) { stale?.let { runCatching { it.client.close() } } }

            val connectStartedAtMs = nowMs()
            val endpointHost = runCatching { URI(config.endpointUrl).host }.getOrNull().orEmpty()
            // Remote services may negotiate TLS and initialize slowly. Keep explicit short
            // test/user overrides and local gateway timeouts unchanged.
            val effectiveSessionTimeoutMs = if (sessionConnectTimeoutMs >= 15_000L && config.endpointUrl.startsWith("https://", ignoreCase = true)) {
                maxOf(sessionConnectTimeoutMs, 30_000L)
            } else {
                sessionConnectTimeoutMs
            }
            AppLogRecorder.record("MCP", "Connect started · connection=${config.connectionUid} · host=$endpointHost · transportTimeoutMs=$transportConnectTimeoutMs · initializationTimeoutMs=$effectiveSessionTimeoutMs")
            val result = runCatching {
                val connectivity = dev.chungjungsoo.gptmobile.data.research.academic.ResearchConnectivityGate.shared
                val lease = connectivity.acquire(endpointHost) ?: error("DNS_RECOVERY: MCP connection is waiting for the single connectivity probe; retryAfterMs=${connectivity.recoveryDelayMillis(endpointHost)}.")
                try {
                    val transport = StreamableHttpClientTransport(httpClient, config.endpointUrl) {
                        timeout {
                            connectTimeoutMillis = transportConnectTimeoutMs
                            requestTimeoutMillis = effectiveSessionTimeoutMs + 2_000
                            socketTimeoutMillis = effectiveSessionTimeoutMs + 2_000
                        }
                        config.authorizationHeader?.let { header(HttpHeaders.Authorization, it) }
                        config.googleApiKey?.let { header("X-Goog-Api-Key", it) }
                    }
                    val client = Client(
                        Implementation(name = CLIENT_NAME, version = CLIENT_VERSION),
                        io.modelcontextprotocol.kotlin.sdk.client.ClientOptions(
                            capabilities = io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities(
                                elicitation = if (interactions == null) null else io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities.Elicitation(form = JsonObject(emptyMap()))
                            )
                        )
                    )
                    interactions?.let { handler -> client.setElicitationHandler { handler.request(dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor.redact(config.endpointUrl), it) } }
                    try {
                        val connected = withTimeoutOrNull(effectiveSessionTimeoutMs) {
                            client.connect(transport)
                            true
                        }
                        if (connected == true) connectivity.success(endpointHost)
                        check(connected == true) { "MCP_INITIALIZATION_TIMEOUT: MCP initialization timed out. Check the server process and protocol endpoint, then retry." }
                        AppLogRecorder.record("MCP", "Connect completed · connection=${config.connectionUid} · host=$endpointHost · elapsedMs=${nowMs() - connectStartedAtMs}")
                        Session(key, client)
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) { runCatching { withTimeoutOrNull(2_000) { client.close() } } }
                        // Parent cancellation is not a server outage and must not trip the MCP circuit.
                        throw cancelled
                    } catch (error: Exception) {
                        if (generateSequence(error as Throwable) { it.cause?.takeUnless { cause -> cause === it } }.take(16).any { it is StreamableHttpError }) connectivity.success(endpointHost)
                        val httpStatus = generateSequence(error as Throwable) { it.cause?.takeUnless { cause -> cause === it } }.take(8).filterIsInstance<StreamableHttpError>().firstOrNull()?.code
                        AppLogRecorder.record("MCP", "Connect failed · connection=${config.connectionUid} · elapsedMs=${nowMs() - connectStartedAtMs} · transportTimeoutMs=$transportConnectTimeoutMs · initializationTimeoutMs=$effectiveSessionTimeoutMs · errorType=${error.javaClass.simpleName} · httpStatus=${httpStatus ?: "unavailable"}", "E")
                        withContext(NonCancellable) { runCatching { withTimeoutOrNull(2_000) { client.close() } } }
                        if (generateSequence(error as Throwable) { it.cause?.takeUnless { cause -> cause === it } }.take(16).any { it is StreamableHttpError && it.code == 400 } && !isMcpQuotaFailure(error.message.orEmpty())) {
                            throw McpProtocolConfigurationException("MCP_INITIALIZATION_SCHEMA_INVALID", "The server rejected the initialize handshake (HTTP 400). Validate the endpoint path, initialization request schema and supported protocol version.", 400, "INITIALIZE_REQUEST_ACCEPTANCE_REQUIRED", "initialize")
                        }
                        if (generateSequence(error as Throwable) { it.cause }.any { it is StreamableHttpError && it.code == 404 }) {
                            configurationFailures[config.connectionUid] = key
                            throw McpEndpointConfigurationException(404)
                        }
                        throw error
                    }
                } finally {
                    connectivity.release(lease)
                }
            }
            withContext(NonCancellable) {
                mutex.lock()
                try {
                    if (inFlight[config.connectionUid]?.deferred === created) {
                        inFlight.remove(config.connectionUid)
                        result.getOrNull()?.let { sessions[config.connectionUid] = it }
                    }
                } finally {
                    mutex.unlock()
                }
                result.fold(created::complete, created::completeExceptionally)
            }
            result.fold(
                onSuccess = { recordSuccess(config.connectionUid, latencyMs = nowMs() - connectStartedAtMs, observation = observation) },
                onFailure = { error -> if (recordFailures && error !is CancellationException && !error.isMcpUnauthorized()) recordFailure(config.connectionUid, error, observation = observation, stage = "initialization") }
            )
            return result.getOrThrow()
        }
    }

    private suspend fun invalidate(connectionUid: String, expected: Session) {
        mutex.lock()
        val removed = try {
            if (sessions[connectionUid] === expected) sessions.remove(connectionUid) else null
        } finally {
            mutex.unlock()
        }
        withContext(NonCancellable) { removed?.let { runCatching { it.client.close() } } }
    }

    private suspend fun takeSession(connectionUid: String): Session? {
        mutex.lock()
        return try {
            sessions.remove(connectionUid)
        } finally {
            mutex.unlock()
        }
    }

    private fun McpConnectionConfig.catalogueIdentity(): String =
        ("catalog-v1|$connectionUid|$endpointUrl|$allowCleartext|${authorizationHeader.orEmpty().sha256()}|${googleApiKey.orEmpty().sha256()}").sha256()

    private fun McpConnectionConfig.validatedKey(): String {
        validateLocalConfiguration()
        return "$endpointUrl|${authorizationHeader.orEmpty().sha256()}|${googleApiKey.orEmpty().sha256()}|${dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.networkEpoch}"
    }

    private class Session(val key: String, val client: Client) {
        val toolCatalogMutex = Mutex()
        var toolCatalog: ToolCatalog? = null
    }
    private data class ToolCatalog(val tools: List<Tool>, val loadedAtMs: Long)
    private data class InFlight(val key: String, val deferred: CompletableDeferred<Session>)

    private companion object {
        const val CLIENT_NAME = "gpt-mobile"
        const val CLIENT_VERSION = "0.9.30.0"

        // Bounded (not Int.MAX_VALUE): remote MCP server responses are untrusted input. Without a
        // finite cap, a misbehaving or malicious server (e.g. a compromised or misconfigured
        // Streamable HTTP endpoint such as a GitHub MCP preset) could force unbounded pagination
        // or an unbounded in-memory tool list. These values comfortably exceed any realistic
        // MCP server's tool catalog while restoring the intended safety guarantee.
        const val MAX_TOOL_PAGES = 200
        const val MAX_DISCOVERED_TOOLS = 1000
        const val TOOL_CATALOG_TTL_MS = 5 * 60 * 1000L
        const val CIRCUIT_BREAKER_FAILURES = 3
        const val MAX_STALE_SESSION_RETRIES = 1
    }
}

internal class McpAuthenticationRequiredException : IllegalStateException("MCP authentication is required. Open Manage connection and reconnect or update the credential.")

internal fun Throwable.isMcpUnauthorized(): Boolean = generateSequence(this) { it.cause?.takeUnless { cause -> cause === it } }.take(8)
    .any { error -> error is McpAuthenticationRequiredException || error is McpDiscoveryOAuthRefreshException || (error is StreamableHttpError && error.code in setOf(401, 403)) || (error is ModernMcpError && error.status in setOf(401, 403)) }

/** Only replay discovery/resource reads after a dropped connection, never tools/call. */
internal fun Throwable.isMcpInterruptedRead(): Boolean = generateSequence(this) { it.cause?.takeUnless { cause -> cause === it } }.take(8)
    .any { error ->
        // The SDK uses this exact lifecycle exception. Subclasses also include
        // cancellation and provider errors, which must not become retryable.
        val sdkDisconnected = error.javaClass == IllegalStateException::class.java && error.message == "Not connected"
        error is java.io.EOFException ||
            sdkDisconnected ||
            listOf("unexpected end of stream", "broken pipe", "connection reset", "software caused connection abort", "socket closed").any { error.message.orEmpty().contains(it, ignoreCase = true) }
    }

private fun Throwable.isMcpStaleSession(): Boolean = generateSequence(this) { it.cause?.takeUnless { cause -> cause === it } }.take(16)
    .any { error ->
        val message = error.message.orEmpty().lowercase()
        (error is StreamableHttpError && error.code == 404) ||
            message.contains("session not found") ||
            message.contains("-32001") &&
            message.contains("session")
    }

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

data class McpBrowserData(
    val resources: List<io.modelcontextprotocol.kotlin.sdk.types.Resource> = emptyList(),
    val prompts: List<io.modelcontextprotocol.kotlin.sdk.types.Prompt> = emptyList(),
    val serverName: String = "",
    val verifiedAt: Long = 0,
    val protocolVersion: String = "Legacy session",
    val capabilities: List<String> = emptyList()
)
