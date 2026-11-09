package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogEntry
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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

/** Existing GatewayClientDiagnostics owns this bounded IO bridge; no raw AppLogEntry crosses the wire. */
internal object GatewayDiagnosticBridge {
    private class Route(@Volatile var config: ProviderRequestConfig) {
        @Volatile var spool: GatewayDiagnosticSpool? = null

        @Volatile var protocol: Int? = null

        @Volatile var gatewayEnabled = false
        val synchronization = GatewayDiagnosticRetryGate()
        val storage = GatewayDiagnosticRetryGate()
        val dropped = AtomicLong()
    }
    private data class Exchange(val route: String, val job: Job)
    private data class Pending(val route: String, val occurredAt: Long, val fields: JsonObject)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val storageLock = Any()
    private val privacyEpoch = AtomicLong()
    private val routes = linkedMapOf<String, Route>()
    private val queue = Channel<Pending>(256)
    private val boot = UUID.randomUUID().toString().replace("-", "")
    private val mutableStatus = MutableStateFlow(GatewayDiagnosticStatus())
    val status = mutableStatus.asStateFlow()

    @Volatile private var directory: File? = null

    @Volatile private var active: String? = null

    @Volatile private var activeAt = 0L

    @Volatile private var exchange: Exchange? = null
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
                    val events = pending.filter { it.route == key }.map { it.occurredAt to it.fields }
                    if (route.spool == null || events.isNotEmpty() || route.dropped.get() > 0) {
                        var retained = false
                        route.storage.run({ allowed && epoch == privacyEpoch.get() && knownRoute(key, route) }) { permitted ->
                            try {
                                synchronized(storageLock) {
                                    if (permitted()) {
                                        val spool = route.spool ?: GatewayDiagnosticSpool(File(root, "$key/capture.jsonl"), boot).also { route.spool = it }
                                        val losses = route.dropped.getAndSet(0)
                                        try {
                                            if (events.isNotEmpty() || losses > 0) spool.append(events, losses)
                                            retained = true
                                        } catch (failure: Exception) {
                                            route.dropped.addAndGet(losses)
                                            throw failure
                                        }
                                    }
                                }
                            } catch (failure: IOException) {
                                throw failure as? DiagnosticSyncFailure ?: DiagnosticSyncFailure("DEBUG_STORAGE_UNAVAILABLE")
                            }
                        }
                        if (!retained) route.dropped.addAndGet(events.size.toLong())
                    }
                }
                val key = active
                val route = synchronized(lock) { routes[key] }
                val spool = route?.spool
                if (key != null && route != null) {
                    if (spool != null &&
                        allowed &&
                        route.protocol == 2 &&
                        route.gatewayEnabled &&
                        route.storage.snapshot().state == GatewayDiagnosticRetryState.READY &&
                        route.synchronization.snapshot().state == GatewayDiagnosticRetryState.READY &&
                        exchange?.job?.isActive != true
                    ) {
                        val job = launch(start = CoroutineStart.LAZY) {
                            route.synchronization.run({ allowed && epoch == privacyEpoch.get() && active == key && knownRoute(key, route) }) { permitted ->
                                GatewayDiagnosticSync(spool, GatewayDiagnosticHttpTransport(http, route.config, permitted), permitted) { change ->
                                    synchronized(storageLock) { if (permitted()) change() }
                                }.once()
                            }
                        }
                        exchange = Exchange(key, job)
                        job.start()
                    }
                    if (allowed) mutableStatus.value = routeStatus(key, route)
                }
            }
        }
    }

    private fun knownRoute(key: String, route: Route): Boolean = synchronized(lock) { routes[key] === route }

    fun remember(config: ProviderRequestConfig, protocol: Int? = null, gatewayEnabled: Boolean? = null) {
        if (!allowed || directory == null) return
        val key = GatewayClientDiagnostics.routeIdentity(config)
        val previous = synchronized(lock) {
            if (!allowed) return
            val route = routes[key]
            if (route == null && routes.size >= 8) {
                mutableStatus.value = GatewayDiagnosticStatus(captureState = "route_limit", storageError = "DIAGNOSTIC_ROUTE_LIMIT")
                return
            }
            val current = route ?: Route(config).also { routes[key] = it }
            current.config = config
            if (protocol != null) current.protocol = protocol
            if (gatewayEnabled != null) current.gatewayEnabled = gatewayEnabled
            val oldExchange = exchange.takeIf { active != key }
            if (active != key) routes[active]?.synchronization?.pause()
            active = key
            activeAt = System.currentTimeMillis()
            oldExchange
        }
        previous?.job?.cancel()
    }

    /** An explicit refresh applies only to this authenticated route and retains the durable capture. */
    fun refresh(config: ProviderRequestConfig) {
        if (!allowed) return
        val key = GatewayClientDiagnostics.routeIdentity(config)
        val previous = synchronized(lock) {
            if (!allowed) return
            val route = routes[key] ?: return
            route.config = config
            route.synchronization.refresh(true)
            route.storage.refresh(true)
            if (active != key) routes[active]?.synchronization?.pause()
            active = key
            activeAt = System.currentTimeMillis()
            exchange
        }
        previous?.job?.cancel()
    }

    fun sessionHeaders(config: ProviderRequestConfig): Map<String, String> {
        if (!allowed) return emptyMap()
        val spool = synchronized(lock) { routes[GatewayClientDiagnostics.routeIdentity(config)]?.spool } ?: return emptyMap()
        return mapOf("X-Gateway-Diagnostic-Session" to spool.sessionId, "X-Gateway-Diagnostic-Started" to spool.captureStartedAt.toString())
    }

    fun event(config: ProviderRequestConfig, fields: JsonObject, occurredAt: Long = System.currentTimeMillis()) {
        if (!allowed || directory == null) return
        val key = GatewayClientDiagnostics.routeIdentity(config)
        val route = synchronized(lock) { routes[key] } ?: return
        if (!queue.trySend(Pending(key, occurredAt, GatewayDiagnosticEvent.fields(fields))).isSuccess) route.dropped.incrementAndGet()
    }

    /** Called by AppLogRecorder's IO consumer, after its private-scope check. Only enum metadata is queued. */
    fun appEvent(entry: AppLogEntry) {
        if (!allowed || System.currentTimeMillis() - activeAt > 5 * 60_000) return
        val route = synchronized(lock) { routes[active] } ?: return
        val projected = gatewayAppDiagnosticProjection(entry) ?: return
        event(route.config, projected, entry.time)
    }

    fun pause() {
        privacyEpoch.incrementAndGet()
        val previous = synchronized(lock) {
            routes.values.forEach {
                it.synchronization.pause()
                it.storage.pause()
            }
            exchange
        }
        previous?.job?.cancel()
        while (queue.tryReceive().isSuccess) Unit
        publishPaused()
    }

    private fun publishPaused() {
        mutableStatus.value = mutableStatus.value.copy(captureState = if (AppLogRecorder.privateSessionActive) "private_session" else "disabled")
    }

    suspend fun statusFor(config: ProviderRequestConfig): GatewayDiagnosticStatus = withContext(Dispatchers.IO) {
        val key = GatewayClientDiagnostics.routeIdentity(config)
        val route = synchronized(lock) { routes[key] }
        if (route == null) {
            GatewayDiagnosticStatus(
                routeDigest = key,
                captureState = when {
                    AppLogRecorder.privateSessionActive -> "private_session"
                    !LocalDiagnosticsPolicy.enabled -> "disabled"
                    else -> "protocol_unavailable"
                }
            )
        } else {
            routeStatus(key, route)
        }
    }

    private fun routeStatus(key: String, route: Route): GatewayDiagnosticStatus {
        val snapshot = route.spool?.status() ?: GatewayDiagnosticStatus(captureState = "not_observed")
        val storage = route.storage.snapshot()
        val synchronization = route.synchronization.snapshot()
        val failure = storage.error ?: synchronization.error ?: snapshot.storageError
        return snapshot.copy(
            routeDigest = key,
            protocolVersion = route.protocol,
            storageError = failure,
            captureState = when {
                AppLogRecorder.privateSessionActive -> "private_session"
                !LocalDiagnosticsPolicy.enabled -> "disabled"
                storage.state == GatewayDiagnosticRetryState.BLOCKED || synchronization.state == GatewayDiagnosticRetryState.BLOCKED -> "sync_blocked"
                storage.state == GatewayDiagnosticRetryState.EXHAUSTED || synchronization.state == GatewayDiagnosticRetryState.EXHAUSTED -> "retry_exhausted"
                route.protocol != 2 -> "protocol_unavailable"
                !route.gatewayEnabled -> "gateway_disabled"
                failure != null -> "retry_wait"
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
