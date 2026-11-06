package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogEntry
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy
import dev.chungjungsoo.gptmobile.data.diagnostics.classifyDiagnostic
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Existing GatewayClientDiagnostics owns this bounded IO bridge; no raw AppLogEntry crosses the wire. */
internal object GatewayDiagnosticBridge {
    private data class Route(
        var config: ProviderRequestConfig,
        var spool: GatewayDiagnosticSpool? = null,
        var protocol: Int? = null,
        var gatewayEnabled: Boolean = false,
        var retryAt: Long = 0,
        var failures: Int = 0,
        var error: String? = null
    )
    private data class Pending(val route: String, val occurredAt: Long, val fields: JsonObject)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val storageLock = Any()
    private val privacyEpoch = AtomicLong()
    private val routes = linkedMapOf<String, Route>()
    private val queue = Channel<Pending>(256)
    private val dropped = AtomicLong()
    private val boot = UUID.randomUUID().toString().replace("-", "")
    private val mutableStatus = MutableStateFlow(GatewayDiagnosticStatus())
    val status = mutableStatus.asStateFlow()

    @Volatile private var directory: File? = null

    @Volatile private var active: String? = null

    @Volatile private var activeAt = 0L

    @Volatile private var exchange: Job? = null
    private val http by lazy {
        HttpClient(OkHttp) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
            engine { config { retryOnConnectionFailure(false) } }
        }
    }
    private val allowed: Boolean get() = LocalDiagnosticsPolicy.enabled && !AppLogRecorder.privateSessionActive

    @Synchronized fun initialize(root: File) {
        if (directory != null) return
        directory = root
        scope.launch {
            LocalDiagnosticsPolicy.state.collect { enabled ->
                if (!enabled) pause()
            }
        }
        scope.launch {
            while (isActive) {
                delay(750)
                if (!allowed) {
                    while (queue.tryReceive().isSuccess) Unit
                    publishPaused()
                    continue
                }
                val pending = mutableListOf<Pending>()
                repeat(64) { queue.tryReceive().getOrNull()?.let(pending::add) }
                val selected = synchronized(lock) { routes.toMap() }
                val epoch = privacyEpoch.get()
                for ((key, route) in selected) {
                    if (!allowed) break
                    try {
                        synchronized(storageLock) {
                            if (epoch == privacyEpoch.get() && allowed) {
                                var spool = route.spool
                                if (spool == null) {
                                    spool = GatewayDiagnosticSpool(File(root, "$key/capture.jsonl"), boot)
                                    synchronized(lock) { route.spool = spool }
                                }
                                val events = pending.filter { it.route == key }.map { it.occurredAt to it.fields }
                                val losses = if (key == active) dropped.getAndSet(0) else 0
                                if (events.isNotEmpty() || losses > 0) spool.append(events, losses)
                            }
                        }
                    } catch (_: IOException) {
                        route.error = "DEBUG_STORAGE_UNAVAILABLE"
                    }
                }
                val route = synchronized(lock) { routes[active] }
                val spool = route?.spool
                if (route != null && spool != null) {
                    if (allowed && route.protocol == 2 && route.gatewayEnabled && System.currentTimeMillis() >= route.retryAt && exchange?.isActive != true) {
                        exchange = launch {
                            try {
                                val config = synchronized(lock) { route.config }
                                val permitted = { allowed && epoch == privacyEpoch.get() }
                                GatewayDiagnosticSync(spool, GatewayDiagnosticHttpTransport(http, config, permitted), permitted) { change ->
                                    synchronized(storageLock) { if (permitted()) change() }
                                }.once()
                                route.error = null
                                route.failures = 0
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                route.error = (failure as? DiagnosticSyncFailure)?.code ?: "DIAGNOSTIC_TRANSPORT_FAILURE"
                                route.failures = (route.failures + 1).coerceAtMost(5)
                                route.retryAt = System.currentTimeMillis() + (1000L shl route.failures).coerceAtMost(30_000)
                            }
                        }
                    }
                    if (allowed) {
                        val snapshot = spool.status()
                        mutableStatus.value = snapshot.copy(
                            protocolVersion = route.protocol,
                            routeDigest = active,
                            captureState = when {
                                route.protocol != 2 -> "protocol_unavailable"
                                !route.gatewayEnabled -> "gateway_disabled"
                                route.error != null -> "offline_or_blocked"
                                else -> "recording"
                            },
                            storageError = route.error ?: snapshot.storageError
                        )
                    }
                }
            }
        }
    }

    fun remember(config: ProviderRequestConfig, protocol: Int? = null, gatewayEnabled: Boolean? = null) {
        if (!allowed || directory == null) return
        val key = GatewayClientDiagnostics.routeIdentity(config)
        synchronized(lock) {
            val route = routes[key]
            if (route == null && routes.size >= 8) {
                mutableStatus.value = GatewayDiagnosticStatus(captureState = "route_limit", storageError = "DIAGNOSTIC_ROUTE_LIMIT")
                return
            }
            val current = route ?: Route(config).also { routes[key] = it }
            current.config = config
            if (protocol != null) current.protocol = protocol
            if (gatewayEnabled != null) current.gatewayEnabled = gatewayEnabled
            active = key
            activeAt = System.currentTimeMillis()
        }
    }

    fun sessionHeaders(config: ProviderRequestConfig): Map<String, String> {
        if (!allowed) return emptyMap()
        val spool = synchronized(lock) { routes[GatewayClientDiagnostics.routeIdentity(config)]?.spool } ?: return emptyMap()
        return mapOf("X-Gateway-Diagnostic-Session" to spool.sessionId, "X-Gateway-Diagnostic-Started" to spool.captureStartedAt.toString())
    }

    fun event(config: ProviderRequestConfig, fields: JsonObject, occurredAt: Long = System.currentTimeMillis()) {
        if (!allowed || directory == null) return
        val key = GatewayClientDiagnostics.routeIdentity(config)
        if (synchronized(lock) { key !in routes }) return
        if (!queue.trySend(Pending(key, occurredAt, GatewayDiagnosticEvent.fields(fields))).isSuccess) dropped.incrementAndGet()
    }

    /** Called by AppLogRecorder's IO consumer, after its private-scope check. Only enum metadata is queued. */
    fun appEvent(entry: AppLogEntry) {
        if (!allowed || System.currentTimeMillis() - activeAt > 5 * 60_000) return
        val route = synchronized(lock) { routes[active] } ?: return
        if (entry.tag == "Gateway" && entry.message.startsWith("CLIENT_EXCHANGE")) return
        val classification = classifyDiagnostic(entry.level, entry.tag, entry.message)
        val stage = when (entry.tag) {
            "Lifecycle", "Application", "AppCrash", "Diagnostics" -> "lifecycle"
            "Frame", "Rendering", "ChatRendering" -> "renderer"
            "Network", "Connectivity" -> "network"
            "MCP", "Mcp", "McpRuntime" -> "mcp_call"
            "Agent", "AgentRun", "Tool", "ToolExecution", "GitHub", "GitHubTool" -> "operation_dispatch"
            else -> if (classification.severity in setOf("error", "critical", "warning")) "unknown" else return
        }
        val error = when {
            classification.category in setOf("fatal", "memory_recovery") && entry.message.contains("memory", true) -> "JAVA_HEAP_PRESSURE"
            classification.category in setOf("transport_availability", "network_callback_lifecycle") -> "TRANSPORT_FAILURE"
            stage == "renderer" && classification.severity in setOf("error", "warning", "critical") -> "RENDERER_STALL"
            classification.severity in setOf("error", "critical") -> "UNKNOWN_CAUSE"
            else -> "NONE"
        }
        val projected = buildJsonObject {
            put("stage", stage)
            put("direction", "local")
            put("owner", "android")
            put("severity", classification.severity)
            put("error_code", error)
            put("dispatch_state", "unknown")
            put("retry_class", "unknown")
            put(
                "category",
                when (stage) {
                    "renderer" -> "renderer"
                    "network" -> "network"
                    "mcp_call", "operation_dispatch" -> "tool"
                    "lifecycle" -> "lifecycle"
                    else -> "unknown"
                }
            )
            // Raw messages never leave this callback. Only syntactically opaque domain IDs can correlate events.
            val names = mapOf("runId" to "run_id", "jobId" to "job_id", "requestDigest" to "request_id", "operationId" to "operation_id", "toolCallId" to "tool_call_id", "bindingId" to "binding_id", "incidentId" to "incident_id")
            names.forEach { (name, field) ->
                Regex("(?:^|[ ·])${Regex.escape(name)}=([a-z0-9_-]{16,80})(?:[ ·]|$)").find(entry.message)?.groupValues?.get(1)?.let { put(field, it) }
            }
            put(
                "bounded_attributes",
                buildJsonObject {
                    put("occurrence_count", 1)
                    put("source_event_digest", GatewayDiagnosticEvent.digest(entry.eventId))
                    put("vendor_log_available", false)
                    put("app_version_code", BuildConfig.VERSION_CODE)
                    put("app_revision", BuildConfig.APP_COMMIT)
                    put("android_sdk", android.os.Build.VERSION.SDK_INT)
                    listOf("slowFrames" to "slow_frames", "frameCount" to "frame_count", "droppedFrames" to "dropped_frames").forEach { (name, field) ->
                        Regex("(?:^|[ ·])$name=([0-9]{1,12})(?:[ ·]|$)").find(entry.message)?.groupValues?.get(1)?.toLongOrNull()?.let { put(field, it) }
                    }
                }
            )
        }
        event(route.config, projected, entry.time)
    }

    fun pause() {
        privacyEpoch.incrementAndGet()
        exchange?.cancel()
        while (queue.tryReceive().isSuccess) Unit
        publishPaused()
    }

    private fun publishPaused() {
        mutableStatus.value = mutableStatus.value.copy(captureState = if (AppLogRecorder.privateSessionActive) "private_session" else "disabled")
    }

    suspend fun statusFor(config: ProviderRequestConfig): GatewayDiagnosticStatus = withContext(Dispatchers.IO) {
        val key = GatewayClientDiagnostics.routeIdentity(config)
        val route = synchronized(lock) { routes[key] }
        val snapshot = route?.spool?.status() ?: GatewayDiagnosticStatus(captureState = if (allowed) "not_observed" else "disabled")
        snapshot.copy(
            routeDigest = key,
            protocolVersion = route?.protocol,
            storageError = route?.error ?: snapshot.storageError,
            captureState = when {
                AppLogRecorder.privateSessionActive -> "private_session"
                !LocalDiagnosticsPolicy.enabled -> "disabled"
                route?.protocol != 2 -> "protocol_unavailable"
                !route.gatewayEnabled -> "gateway_disabled"
                route.error != null -> "offline_or_blocked"
                else -> snapshot.captureState
            }
        )
    }

    suspend fun page(config: ProviderRequestConfig?, offset: Int = 0, failuresOnly: Boolean = true, traceId: String? = null, stages: Set<String> = emptySet()): GatewayDiagnosticPage = withContext(Dispatchers.IO) {
        if (AppLogRecorder.privateSessionActive) return@withContext GatewayDiagnosticPage(emptyList(), null, 0)
        val key = config?.let(GatewayClientDiagnostics::routeIdentity) ?: active
        synchronized(lock) { routes[key]?.spool }?.page(offset, 32, failuresOnly, traceId, stages) ?: GatewayDiagnosticPage(emptyList(), null, 0)
    }

    suspend fun export(config: ProviderRequestConfig?, destination: File): File = withContext(Dispatchers.IO) {
        if (AppLogRecorder.privateSessionActive) throw IOException("PRIVATE_SESSION")
        val epoch = privacyEpoch.get()
        val key = config?.let(GatewayClientDiagnostics::routeIdentity) ?: active
        val spool = synchronized(lock) { routes[key]?.spool } ?: throw IOException("DIAGNOSTIC_SOURCE_UNAVAILABLE")
        synchronized(storageLock) {
            val exported = spool.export(destination)
            if (epoch != privacyEpoch.get() || AppLogRecorder.privateSessionActive) {
                exported.delete()
                throw IOException("DIAGNOSTIC_CAPTURE_REVOKED")
            }
            exported
        }
    }

    fun clear() {
        pause()
        scope.launch {
            synchronized(storageLock) {
                synchronized(lock) {
                    routes.clear()
                    active = null
                }
                directory?.deleteRecursively()
            }
            mutableStatus.value = GatewayDiagnosticStatus()
        }
    }
}
