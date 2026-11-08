package dev.chungjungsoo.gptmobile.data.network.gateway

import android.os.SystemClock
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

internal fun interface GatewayAvailabilityProbe {
    suspend fun readiness(config: ProviderRequestConfig, model: String): GatewayReadinessObservation?
}

/** Read-only, shared admission observations. Every polling coroutine belongs to a visible collector. */
@Singleton
class GatewayAvailabilityRepository internal constructor(
    private val source: GatewayAvailabilityProbe,
    private val now: () -> Long = System::currentTimeMillis,
    private val routeAvailable: Flow<Boolean> = flowOf(true),
    private val networkEpoch: () -> Long = { LocalServiceHealth.networkEpoch },
    private val elapsedMillis: () -> Long = now
) {
    @Inject internal constructor(client: GatewayDashboardClient, network: GatewayNetworkRecoveryMonitor) : this(
        GatewayAvailabilityProbe { config, model -> client.readiness(config, model) },
        routeAvailable = network.routeAvailable,
        elapsedMillis = SystemClock::elapsedRealtime
    )

    private val permits = Semaphore(2)
    private class SharedProbe(val scope: CoroutineScope, val result: Deferred<GatewayAvailability>, var users: Int = 0)
    private data class Recorded(val result: GatewayAvailability, val startedAtMillis: Long)
    private val pending = mutableMapOf<String, SharedProbe>()
    private val saved = linkedMapOf<String, Recorded>()
    private val lock = Any()
    private val routeRevision = AtomicLong()
    private var lastRouteAvailable: Boolean? = null

    private fun key(target: GatewayAvailabilityTarget) = "${target.identity}:${networkEpoch()}:${routeRevision.get()}"

    private fun recordRoute(available: Boolean) = synchronized(lock) {
        if (lastRouteAvailable == false && available) routeRevision.incrementAndGet()
        lastRouteAvailable = available
    }

    private fun routeUnavailable(): Boolean {
        (routeAvailable as? StateFlow<Boolean>)?.value?.let(::recordRoute)
        return synchronized(lock) { lastRouteAvailable == false }
    }

    internal fun snapshot(target: GatewayAvailabilityTarget): GatewayAvailability =
        if (target.config == null || target.problem != null || !validGatewayAvailabilityModel(target.model)) {
            GatewayAvailability(target.identity, GatewayAvailabilityStatus.ATTENTION, target.problem ?: "Check this gateway connection.")
        } else {
            val offline = routeUnavailable()
            synchronized(lock) {
                if (offline) {
                    GatewayAvailability(target.identity, GatewayAvailabilityStatus.OFFLINE, "No network route to the gateway.")
                } else {
                    saved[key(target)]?.result
                }
            }?.at(now(), elapsedMillis())
                ?: GatewayAvailability(target.identity, GatewayAvailabilityStatus.CHECKING, "Checking model availability…")
        }

    internal suspend fun refresh(target: GatewayAvailabilityTarget): GatewayAvailability {
        if (target.config == null || target.problem != null || !validGatewayAvailabilityModel(target.model)) return snapshot(target)
        if (routeUnavailable()) return snapshot(target)
        val requestKey = key(target)
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val probe = synchronized(lock) {
            saved[requestKey]?.takeIf { elapsedMillis() - it.startedAtMillis in 0 until GATEWAY_AVAILABILITY_POLL_MILLIS }?.let {
                return it.result.at(now(), elapsedMillis())
            }
            pending.getOrPut(requestKey) {
                // A visible consumer owns a reference, not another consumer's job.
                val scope = CoroutineScope(caller.minusKey(Job) + SupervisorJob())
                SharedProbe(scope, scope.async(start = CoroutineStart.LAZY) { readOnce(target, requestKey) })
            }.also { it.users++ }
        }
        return try {
            probe.result.start()
            val result = probe.result.await()
            currentCoroutineContext().ensureActive()
            if (routeUnavailable() || requestKey != key(target)) snapshot(target) else result.at(now(), elapsedMillis())
        } finally {
            val unused = synchronized(lock) {
                probe.users--
                if (probe.users == 0 && pending[requestKey] === probe) {
                    pending.remove(requestKey)
                    true
                } else {
                    false
                }
            }
            if (unused) probe.scope.cancel()
        }
    }

    private suspend fun readOnce(target: GatewayAvailabilityTarget, requestKey: String): GatewayAvailability = permits.withPermit {
        currentCoroutineContext().ensureActive()
        val started = elapsedMillis()
        fun state(status: GatewayAvailabilityStatus, message: String) = GatewayAvailability(target.identity, status, message, now(), receivedAtMonotonicMillis = elapsedMillis())
        val result = try {
            val attempt = withTimeoutOrNull(6_000) { source.readiness(requireNotNull(target.config), target.model) to Unit }
            currentCoroutineContext().ensureActive()
            if (attempt == null) {
                state(GatewayAvailabilityStatus.CHECKING, "Gateway response is still unverified.")
            } else {
                gatewayAvailability(target, attempt.first, now(), elapsedMillis())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: GatewayDashboardAccessDenied) {
            state(GatewayAvailabilityStatus.ATTENTION, "Check gateway access in connection settings.")
        } catch (failure: Exception) {
            if (failure.confirmedUnreachable()) {
                state(GatewayAvailabilityStatus.OFFLINE, "Gateway is unreachable.")
            } else {
                state(GatewayAvailabilityStatus.CHECKING, "Gateway readiness could not be verified.")
            }
        }
        currentCoroutineContext().ensureActive()
        if (!routeUnavailable() && requestKey == key(target)) {
            synchronized(lock) {
                if (saved.size >= 128 && requestKey !in saved) saved.remove(saved.keys.first())
                saved[requestKey] = Recorded(result, started)
            }
        }
        result
    }

    /** Cancelling the collector cancels pending probes, freshness ticks, and the ten-second poll. */
    internal fun observe(targets: List<GatewayAvailabilityTarget>): Flow<Map<String, GatewayAvailability>> = channelFlow {
        if (targets.isEmpty()) {
            send(emptyMap())
            return@channelFlow
        }
        val current = MutableStateFlow(targets.associate { it.profileUid to snapshot(it) })
        launch {
            var published: Map<String, GatewayAvailability>? = null
            current.collectLatest { states ->
                while (currentCoroutineContext().isActive) {
                    val wall = now()
                    val elapsed = elapsedMillis()
                    val next = states.mapValues { (_, state) -> state.at(wall, elapsed) }
                    if (next != published) {
                        send(next)
                        published = next
                    }
                    // Cached backend evidence may have only a few milliseconds
                    // left; invalidate at that deadline, not at a whole-second tick.
                    val expiry = states.values.map { it.freshnessRemainingMillis(wall, elapsed) }.filter { it > 0 }.minOrNull()
                    if (expiry == null) awaitCancellation() else delay(expiry)
                }
            }
        }
        routeAvailable.distinctUntilChanged().collectLatest { available ->
            recordRoute(available)
            if (!available) {
                current.value = targets.associate { it.profileUid to snapshot(it) }
                return@collectLatest
            }
            coroutineScope {
                targets.groupBy { it.identity }.values.forEach { group ->
                    launch {
                        val target = group.first()
                        while (currentCoroutineContext().isActive) {
                            val started = elapsedMillis()
                            val result = refresh(target)
                            current.update { it + group.associate { selected -> selected.profileUid to result } }
                            if (result.status == GatewayAvailabilityStatus.ATTENTION && target.problem != null) return@launch
                            delay((GATEWAY_AVAILABILITY_POLL_MILLIS - (elapsedMillis() - started)).coerceAtLeast(1))
                        }
                    }
                }
            }
        }
    }
}

private fun Throwable.confirmedUnreachable(): Boolean {
    var failure: Throwable? = this
    repeat(8) {
        if (failure is ConnectException || failure is NoRouteToHostException || failure is UnknownHostException) return true
        failure = failure?.cause ?: return false
    }
    return false
}
