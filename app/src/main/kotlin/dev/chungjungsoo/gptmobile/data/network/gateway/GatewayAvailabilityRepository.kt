package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.context.RouteCapabilityCache
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlinx.coroutines.withTimeout

internal fun interface GatewayAvailabilityProbe {
    suspend fun readiness(config: ProviderRequestConfig, model: String): GatewayReadinessObservation?
}

/** Read-only, shared admission observations. Every polling coroutine belongs to a visible collector. */
@Singleton
class GatewayAvailabilityRepository internal constructor(
    private val source: GatewayAvailabilityProbe,
    private val now: () -> Long = System::currentTimeMillis,
    private val routeAvailable: Flow<Boolean> = flowOf(true),
    private val networkEpoch: () -> Long = { LocalServiceHealth.networkEpoch }
) {
    @Inject internal constructor(client: GatewayDashboardClient, network: GatewayNetworkRecoveryMonitor) : this(
        GatewayAvailabilityProbe { config, model -> client.readiness(config, model) },
        routeAvailable = network.routeAvailable
    )

    private val permits = Semaphore(2)
    private val cache = RouteCapabilityCache<GatewayAvailability>(now, positiveTtl = GATEWAY_AVAILABILITY_POLL_MILLIS, negativeTtl = GATEWAY_AVAILABILITY_POLL_MILLIS)
    private val saved = linkedMapOf<String, GatewayAvailability>()
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
        if (target.config == null || target.problem != null || target.model.isBlank()) {
            GatewayAvailability(target.identity, GatewayAvailabilityStatus.ATTENTION, target.problem ?: "Check this gateway connection.")
        } else {
            val offline = routeUnavailable()
            synchronized(lock) {
                if (offline) {
                    GatewayAvailability(target.identity, GatewayAvailabilityStatus.OFFLINE, "No network route to the gateway.")
                } else {
                    saved[key(target)]
                }
            }?.at(now())
                ?: GatewayAvailability(target.identity, GatewayAvailabilityStatus.CHECKING, "Checking model availability…")
        }

    internal suspend fun refresh(target: GatewayAvailabilityTarget): GatewayAvailability {
        val config = target.config ?: return snapshot(target)
        if (target.problem != null || target.model.isBlank()) return snapshot(target)
        if (routeUnavailable()) return snapshot(target)
        val requestKey = key(target)
        val result = requireNotNull(
            cache.getOrLoad(requestKey) {
                permits.withPermit {
                    try {
                        val observation = withTimeout(6_000) { source.readiness(config, target.model) }
                        gatewayAvailability(target, observation, now())
                    } catch (_: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        GatewayAvailability(target.identity, GatewayAvailabilityStatus.OFFLINE, "Gateway did not respond in time.", now())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: GatewayDashboardAccessDenied) {
                        GatewayAvailability(target.identity, GatewayAvailabilityStatus.ATTENTION, "Check gateway access in connection settings.", now())
                    } catch (_: IOException) {
                        GatewayAvailability(target.identity, GatewayAvailabilityStatus.OFFLINE, "Gateway is offline or unreachable.", now())
                    } catch (_: Exception) {
                        GatewayAvailability(target.identity, GatewayAvailabilityStatus.ATTENTION, "Gateway readiness could not be verified.", now())
                    }
                }
            }
        )
        currentCoroutineContext().ensureActive()
        if (routeUnavailable() || requestKey != key(target)) return snapshot(target)
        synchronized(lock) {
            if (saved.size >= 128 && requestKey !in saved) saved.remove(saved.keys.first())
            saved[requestKey] = result
        }
        return result.at(now())
    }

    /** Cancelling the collector cancels pending probes, freshness ticks, and the ten-second poll. */
    internal fun observe(targets: List<GatewayAvailabilityTarget>): Flow<Map<String, GatewayAvailability>> = channelFlow {
        if (targets.isEmpty()) {
            send(emptyMap())
            return@channelFlow
        }
        val current = MutableStateFlow(targets.associate { it.profileUid to snapshot(it) })
        launch { current.collect { send(it) } }
        launch {
            while (currentCoroutineContext().isActive) {
                delay(1_000)
                current.update { states -> states.mapValues { (_, state) -> state.at(now()) } }
            }
        }
        routeAvailable.distinctUntilChanged().collectLatest { available ->
            recordRoute(available)
            if (!available) {
                current.value = targets.associate { it.profileUid to GatewayAvailability(it.identity, GatewayAvailabilityStatus.OFFLINE, "No network route to the gateway.") }
                return@collectLatest
            }
            coroutineScope {
                targets.groupBy { it.identity }.values.forEach { group ->
                    launch {
                        val target = group.first()
                        while (currentCoroutineContext().isActive) {
                            val result = refresh(target)
                            current.update { it + group.associate { selected -> selected.profileUid to result } }
                            if (result.status == GatewayAvailabilityStatus.ATTENTION && target.problem != null) return@launch
                            delay(GATEWAY_AVAILABILITY_POLL_MILLIS)
                        }
                    }
                }
            }
        }
    }
}
