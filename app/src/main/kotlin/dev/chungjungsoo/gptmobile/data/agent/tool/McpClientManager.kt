package dev.chungjungsoo.gptmobile.data.agent.tool

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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

class McpConnectionConfig(
    val connectionUid: String,
    val endpointUrl: String,
    val allowCleartext: Boolean,
    val authorizationHeader: String? = null,
    val googleApiKey: String? = null
)

enum class McpConnectionHealthState {
    CONNECTED,
    DEGRADED,
    UNREACHABLE,
    AUTHENTICATION_REQUIRED,
    RECOVERING
}

data class McpConnectionHealth(
    val state: McpConnectionHealthState = McpConnectionHealthState.RECOVERING,
    val lastSuccessAtMs: Long? = null,
    val lastFailureAtMs: Long? = null,
    val latencyMs: Long? = null,
    val consecutiveFailures: Int = 0,
    val nextRetryAtMs: Long? = null,
    val lastError: String? = null,
    val availableToolCount: Int? = null,
    val connectionHealth: dev.chungjungsoo.gptmobile.data.network.ConnectionHealth = dev.chungjungsoo.gptmobile.data.network.ConnectionHealth.UNKNOWN
)

class McpBackoffException(
    val connectionUid: String,
    val retryAtMs: Long
) : IllegalStateException("MCP connection is backing off until $retryAtMs.")

class McpEndpointConfigurationException :
    IllegalStateException(
        "MCP endpoint returned HTTP 404 during initialization. Check the server URL/path and running service in Plugins/Tools, then reconnect."
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
    private val catalogueScope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
) {
    @Inject
    constructor(networkClient: NetworkClient, mediaStore: McpMediaStore, interactions: McpInteractions, tasks: dev.chungjungsoo.gptmobile.data.workspace.RemoteTaskStore, trust: dev.chungjungsoo.gptmobile.data.permissions.ToolTrustStore, statistics: dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsRepository, @dev.chungjungsoo.gptmobile.di.ReadinessScope scope: kotlinx.coroutines.CoroutineScope) : this(networkClient(), mediaStore, interactions, modern = ModernMcpTransport(networkClient(), interactions, tasks), trust = trust, statistics = statistics, catalogueScope = scope)

    private val mutex = Mutex()
    private val healthOrder = McpHealthObservationOrder()

    // ponytail: one global lock serializes session setup only; use per-connection locks if startup contention becomes measurable.
    private val providerCooldowns = ConcurrentHashMap<String, Pair<String, Long>>()
    private val configurationFailures = ConcurrentHashMap<String, String>()
    private val transportConfigurationKeys = ConcurrentHashMap<String, String>()
    private val sessions = mutableMapOf<String, Session>()
    private val inFlight = mutableMapOf<String, InFlight>()
    private val _health = MutableStateFlow<Map<String, McpConnectionHealth>>(emptyMap())
    val health = _health.asStateFlow()

    private suspend fun modernTransport(config: McpConnectionConfig, refresh: Boolean = false): ModernMcpTransport? {
        val key = config.validatedKey()
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
    private val catalogueFlights = dev.chungjungsoo.gptmobile.data.readiness.ReadinessSingleFlight<String, List<Tool>>(catalogueScope)

    fun peekTools(config: McpConnectionConfig): List<Tool>? = catalogue.peek(config.validatedKey() + ":" + dev.chungjungsoo.gptmobile.data.readiness.ReadinessInvalidation.epoch)?.value

    suspend fun peekToolsForConnection(uid: String): List<Tool>? {
        if (healthSnapshot(uid).state in setOf(McpConnectionHealthState.AUTHENTICATION_REQUIRED, McpConnectionHealthState.UNREACHABLE)) return null
        val snapshot = catalogueIdentities[uid]?.takeIf { it.first == dev.chungjungsoo.gptmobile.data.readiness.ReadinessInvalidation.epoch }?.let { catalogue.peek(it.second) }
        kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.readiness.ReadinessCacheObservation]?.read(snapshot != null, snapshot?.let { System.nanoTime() / 1_000_000 - it.observedAt > 300_000 } == true)
        return snapshot?.value
    }

    fun clearReadinessCache(cancelProducers: Boolean = true) {
        catalogueIdentities.clear()
        catalogue.invalidate()
        if (cancelProducers) catalogueFlights.invalidate()
    }

    suspend fun listTools(config: McpConnectionConfig, forceRefresh: Boolean = false): List<Tool> {
        val policyEpoch = dev.chungjungsoo.gptmobile.data.readiness.ReadinessInvalidation.epoch
        if (healthSnapshot(config.connectionUid).state == McpConnectionHealthState.AUTHENTICATION_REQUIRED && providerCooldowns[config.connectionUid]?.first == config.validatedKey()) throw McpAuthenticationRequiredException()
        val key = config.validatedKey() + ":" + policyEpoch
        val ready = if (forceRefresh) null else catalogue.peek(key, 300_000)
        kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.readiness.ReadinessCacheObservation]?.read(ready != null)
        ready?.let { return it.value }
        return catalogueFlights.acquire(key) {
            val epoch = catalogue.epoch
            val tools = fetchCatalogue(config, forceRefresh).toList()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val bytes = tools.sumOf { it.toString().toByteArray(Charsets.UTF_8).size.toLong() }
            require(tools.size <= 2048 && bytes <= 4 * 1024 * 1024) { "MCP catalogue exceeds metadata bounds." }
            require(tools.map { it.name }.distinct().size == tools.size) { "Duplicate MCP tool names." }
            check(policyEpoch == dev.chungjungsoo.gptmobile.data.readiness.ReadinessInvalidation.epoch) { "MCP readiness invalidated during acquisition." }
            if (catalogue.publish(key, tools, bytes.toInt(), epoch)) {
                if (catalogueIdentities.size >= 16) catalogueIdentities.keys.firstOrNull()?.let(catalogueIdentities::remove)
                catalogueIdentities[config.connectionUid] = policyEpoch to key
            }
            tools
        }
    }

    private suspend fun fetchCatalogue(config: McpConnectionConfig, forceRefresh: Boolean): List<Tool> {
        val observation = healthOrder.begin(config.connectionUid, config.validatedKey())
        return try {
            listToolsUnchecked(config, forceRefresh, observation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is McpBackoffException) throw error
            dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.recordFailure(config.endpointUrl, error)
            if (error.isMcpUnauthorized()) providerCooldowns[config.connectionUid] = config.validatedKey() to Long.MAX_VALUE
            recordFailure(config.connectionUid, error, observation = observation)
            throw error
        }
    }

    private suspend fun listToolsUnchecked(config: McpConnectionConfig, forceRefresh: Boolean, observation: McpHealthObservationOrder.Token): List<Tool> {
        modernTransport(config, forceRefresh)?.let { transport ->
            return transport.listTools(config).also {
                dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.recordHandshakeSuccess(config.endpointUrl)
                recordSuccess(config.connectionUid, availableToolCount = it.size, observation = observation)
                recordCatalog(config, it)
            }
        }
        var observedNetwork = false
        val result = withSession(config, bypassBackoff = forceRefresh, recordFailures = false, recordResponses = false, observation = observation) { session ->
            session.toolCatalogMutex.withLock {
                val now = nowMs()
                session.toolCatalog?.takeIf { !forceRefresh && now - it.loadedAtMs < TOOL_CATALOG_TTL_MS }?.tools ?: run {
                    val tools = mutableListOf<Tool>()
                    val seenCursors = mutableSetOf<String>()
                    var pageCount = 0
                    var cursor: String? = null
                    do {
                        check(++pageCount <= MAX_TOOL_PAGES) { "MCP server returned too many tool pages." }
                        val page = session.client.listTools(
                            request = if (cursor == null) ListToolsRequest() else ListToolsRequest(PaginatedRequestParams(cursor))
                        )
                        tools += page.tools
                        check(tools.size <= MAX_DISCOVERED_TOOLS) { "MCP server returned too many tools." }
                        cursor = page.nextCursor
                        check(cursor == null || seenCursors.add(cursor)) { "MCP server returned a repeated tools cursor." }
                    } while (cursor != null)
                    tools.toList().also {
                        observedNetwork = true
                        session.toolCatalog = ToolCatalog(it, nowMs())
                    }
                }
            }
        }
        if (observedNetwork) dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.recordHandshakeSuccess(config.endpointUrl)
        if (observedNetwork) recordSuccess(config.connectionUid, availableToolCount = result.size, observation = observation)
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
        callId: String? = null
    ): CallToolResult {
        val key = config.validatedKey()
        val observation = healthOrder.begin(config.connectionUid, key)
        providerCooldowns[config.connectionUid]?.let { (blockedKey, until) ->
            if (blockedKey == key && until == Long.MAX_VALUE) throw McpAuthenticationRequiredException()
            if (blockedKey == key && until > nowMs()) throw McpBackoffException(config.connectionUid, until)
            providerCooldowns.remove(config.connectionUid)
            resetHealth(config.connectionUid)
        }
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
            val result = modernTransport(config)?.let { it.callTool(config, toolName, arguments, callId) }
                ?: withSession(config, retryStale = false, recordFailures = false, observation = observation) { it.client.callTool(toolName, arguments) }
            outcome = if (result.isError == true) dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.FAILURE else dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.SUCCESS
            if (result.isError == true) {
                val failure = result.toString()
                if (isMcpCredentialFailure(failure)) {
                    providerCooldowns[config.connectionUid] = key to Long.MAX_VALUE
                    recordFailure(config.connectionUid, McpAuthenticationRequiredException(), observation = observation)
                } else {
                    recordQuota(failure)
                }
            }
            if (result.isError != true) recordSuccess(config.connectionUid, observation = observation)
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
        _health.update { current -> current - connectionUid }
    }

    suspend fun probe(config: McpConnectionConfig): Boolean = try {
        listTools(config, forceRefresh = true)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun recordSuccess(
        connectionUid: String,
        latencyMs: Long? = null,
        availableToolCount: Int? = null,
        observation: McpHealthObservationOrder.Token
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
                    latencyMs = latencyMs ?: previous.latencyMs,
                    consecutiveFailures = 0,
                    nextRetryAtMs = null,
                    connectionHealth = dev.chungjungsoo.gptmobile.data.network.ConnectionHealth.HEALTHY,
                    lastError = null,
                    availableToolCount = availableToolCount ?: previous.availableToolCount
                )
            )
        }
        if (previousState != McpConnectionHealthState.CONNECTED || catalogChanged) {
            AppLogRecorder.record(
                "MCP",
                "Health · connection=$connectionUid · state=CONNECTED · latencyMs=${healthSnapshot(connectionUid).latencyMs ?: "unknown"} · tools=${healthSnapshot(connectionUid).availableToolCount ?: "unknown"}"
            )
        }
    }

    private fun recordFailure(connectionUid: String, error: Throwable, retryAtMs: Long? = null, observation: McpHealthObservationOrder.Token) = healthOrder.publish(observation) {
        val now = nowMs()
        var updated = McpConnectionHealth()
        _health.update { current ->
            val previous = current[connectionUid] ?: McpConnectionHealth()
            val failures = previous.consecutiveFailures + 1
            val delay = retryDelayMs(failures) + retryJitterMs().coerceIn(0, 1000)
            val authenticationRequired = error.isMcpUnauthorized()
            updated = previous.copy(
                state = when {
                    authenticationRequired -> McpConnectionHealthState.AUTHENTICATION_REQUIRED
                    error is McpEndpointConfigurationException || failures >= CIRCUIT_BREAKER_FAILURES -> McpConnectionHealthState.UNREACHABLE
                    else -> McpConnectionHealthState.DEGRADED
                },
                lastFailureAtMs = now,
                consecutiveFailures = failures,
                nextRetryAtMs = if (error is McpEndpointConfigurationException || authenticationRequired) Long.MAX_VALUE else retryAtMs ?: (now + delay),
                lastError = dev.chungjungsoo.gptmobile.data.diagnostics.redactLogMessage(error.message.orEmpty().take(1024)),
                connectionHealth = dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.classify(error)
            )
            current + (connectionUid to updated)
        }
        statistics?.observeHealth(connectionUid, updated.state.name, "PASSIVE_FAILURE", now)
        AppLogRecorder.record(
            "MCP",
            "Health · connection=$connectionUid · state=${updated.state} · failures=${updated.consecutiveFailures} · retryAtMs=${updated.nextRetryAtMs} · error=${updated.lastError.orEmpty()}",
            "W"
        )
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
        else -> 60_000L
    }

    suspend fun close(connectionUid: String) {
        healthOrder.invalidate(connectionUid)
        resetHealth(connectionUid)
        statistics?.observeHealth(connectionUid, "NOT_CHECKED", "CONFIG_CHANGED", nowMs())
        modern?.close(connectionUid)
        val session = takeSession(connectionUid) ?: return
        runCatching { session.client.close() }
    }

    suspend fun closeAll() {
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
        observation: McpHealthObservationOrder.Token = healthOrder.begin(config.connectionUid, config.validatedKey()),
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
            AppLogRecorder.record("MCP", "Connect started · connection=${config.connectionUid} · host=$endpointHost · transportTimeoutMs=$transportConnectTimeoutMs · initializationTimeoutMs=$sessionConnectTimeoutMs")
            val result = runCatching {
                val transport = StreamableHttpClientTransport(httpClient, config.endpointUrl) {
                    timeout {
                        connectTimeoutMillis = transportConnectTimeoutMs
                        requestTimeoutMillis = sessionConnectTimeoutMs + 2_000
                        socketTimeoutMillis = sessionConnectTimeoutMs + 2_000
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
                    val connected = withTimeoutOrNull(sessionConnectTimeoutMs) {
                        client.connect(transport)
                        true
                    }
                    check(connected == true) { "MCP connection timed out. Check the server address and VPN connection, then retry." }
                    AppLogRecorder.record("MCP", "Connect completed · connection=${config.connectionUid} · host=$endpointHost · elapsedMs=${nowMs() - connectStartedAtMs}")
                    Session(key, client)
                } catch (error: Exception) {
                    AppLogRecorder.record("MCP", "Connect failed · connection=${config.connectionUid} · host=$endpointHost · elapsedMs=${nowMs() - connectStartedAtMs} · transportTimeoutMs=$transportConnectTimeoutMs · initializationTimeoutMs=$sessionConnectTimeoutMs · ${error.javaClass.simpleName}: ${error.message.orEmpty()}", "E")
                    withContext(NonCancellable) { runCatching { withTimeoutOrNull(2_000) { client.close() } } }
                    if (generateSequence(error as Throwable) { it.cause }.any { it is StreamableHttpError && it.code == 404 }) {
                        configurationFailures[config.connectionUid] = key
                        throw McpEndpointConfigurationException()
                    }
                    throw error
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
                onFailure = { error -> if (recordFailures && !error.isMcpUnauthorized()) recordFailure(config.connectionUid, error, observation = observation) }
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

    private fun McpConnectionConfig.validatedKey(): String {
        require(connectionUid.isNotBlank()) { "MCP connection ID is required." }
        require(endpointUrl.length <= MAX_ENDPOINT_LENGTH) { "MCP endpoint URL is too long." }
        val uri = runCatching { URI(endpointUrl) }.getOrNull()
            ?: throw IllegalArgumentException("MCP endpoint must be a valid URL.")
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") { "MCP endpoint must use HTTP or HTTPS." }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "MCP endpoint URL is invalid." }
        require(scheme != "http" || allowCleartext) { "Cleartext MCP requires explicit user approval." }
        require(authorizationHeader == null || authorizationHeader.isNotBlank()) { "MCP authorization header is required." }
        require(authorizationHeader?.contains('\r') != true && authorizationHeader?.contains('\n') != true) {
            "MCP authorization header is invalid."
        }
        require(authorizationHeader == null || authorizationHeader.length <= MAX_AUTHORIZATION_HEADER_LENGTH) {
            "MCP authorization header is too long."
        }
        require(googleApiKey == null || (uri.host == "mapstools.googleapis.com" && scheme == "https" && uri.port == -1 && googleApiKey.isNotBlank() && googleApiKey.length <= 4096 && googleApiKey.all { it.code in 33..126 })) { "Invalid Google MCP API key configuration." }
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
        const val MAX_ENDPOINT_LENGTH = 32 * 1024
        const val MAX_AUTHORIZATION_HEADER_LENGTH = 128 * 1024
        const val TOOL_CATALOG_TTL_MS = 5 * 60 * 1000L
        const val CIRCUIT_BREAKER_FAILURES = 3
        const val MAX_STALE_SESSION_RETRIES = 1
    }
}

internal class McpAuthenticationRequiredException : IllegalStateException("MCP authentication is required. Open Manage connection and reconnect or update the credential.")

private fun Throwable.isMcpUnauthorized(): Boolean = generateSequence(this) { it.cause?.takeUnless { cause -> cause === it } }.take(8)
    .any { error -> error is McpAuthenticationRequiredException || (error is StreamableHttpError && error.code in setOf(401, 403)) || (error is ModernMcpError && error.status in setOf(401, 403)) }

/** Only replay discovery/resource reads after a dropped connection, never tools/call. */
internal fun Throwable.isMcpInterruptedRead(): Boolean = generateSequence(this) { it.cause }
    .any { error ->
        error is java.io.EOFException ||
            listOf("unexpected end of stream", "broken pipe", "connection reset").any { error.message.orEmpty().contains(it, ignoreCase = true) }
    }

private fun Throwable.isMcpStaleSession(): Boolean = generateSequence(this) { it.cause }
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
