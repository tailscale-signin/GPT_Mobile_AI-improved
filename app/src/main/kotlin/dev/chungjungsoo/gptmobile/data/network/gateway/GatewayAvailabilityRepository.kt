package dev.chungjungsoo.gptmobile.data.network.gateway

import android.os.SystemClock
import dev.chungjungsoo.gptmobile.data.network.ConnectionHealth
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.LocalServiceUnavailableException
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * Shared readiness for the exact authenticated route and model. Active work/pickers use a
 * short interval; the process menu subscription keeps a three-minute idle interval alive
 * across navigation. The last owner cancels metadata reads, never remote generation.
 */
@Singleton
class GatewayAvailabilityRepository internal constructor(
    private val source: GatewayAvailabilitySource,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallNow: () -> Long = System::currentTimeMillis,
    private val networkEpoch: () -> Long = { LocalServiceHealth.networkEpoch },
    private val processEpoch: (ProviderRequestConfig) -> String = GatewayClientDiagnostics::runtimeEpoch,
    private val routeAvailable: Flow<Boolean> = flowOf(true),
    private val routeChanges: Flow<Long> = flowOf(0L)
) {
    @Inject internal constructor(gateway: GatewayAPI, dashboard: GatewayDashboardClient, network: GatewayNetworkRecoveryMonitor) : this(GatewayAvailabilityNetworkSource(gateway, dashboard), now = SystemClock::elapsedRealtime, routeAvailable = network.routeAvailable, routeChanges = network.routeChanges)

    private class Entry(val target: GatewayAvailabilityTarget, var epoch: String, var nextCheckMillis: Long) {
        val state = MutableStateFlow(GatewayAvailability())
        var references = 0
        var activeReferences = 0
        var generation = 0L
        var polling: Job? = null
        var consecutiveFailures = 0
        var unavailableObservationKey: String? = null
        var unavailableStatus: GatewayAvailabilityStatus? = null
        var retryObservedAtMillis: Long? = null
        var retryNotBeforeMillis = 0L
        var lastScheduledProbeMillis: Long? = null
        var cadenceMillis = GATEWAY_AVAILABILITY_POLL_MILLIS
    }

    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()
    private val probes = Semaphore(2)

    internal fun observe(targets: List<GatewayAvailabilityTarget>): Flow<Map<String, GatewayAvailability>> = observe(targets, background = false)

    internal fun observeBackground(targets: List<GatewayAvailabilityTarget>): Flow<Map<String, GatewayAvailability>> = observe(targets, background = true)

    private fun observe(targets: List<GatewayAvailabilityTarget>, background: Boolean): Flow<Map<String, GatewayAvailability>> {
        if (targets.isEmpty()) return flowOf(emptyMap())
        return combine(
            targets.map { target ->
                val states = if (target.config == null || !validGatewayAvailabilityModel(target.model)) {
                    flowOf(GatewayAvailability(status = GatewayAvailabilityStatus.CONFIGURATION_REQUIRED))
                } else {
                    observeTarget(target, background)
                }
                flow {
                    states.collect { state ->
                        emit(
                            target.profileUid to state.copy(
                                gatewayName = target.gatewayName,
                                gatewayProfileUid = target.gatewayProfileUid,
                                engineeringBinding = target.engineeringBinding
                            )
                        )
                    }
                }
            }
        ) { observations -> observations.toMap() }
    }

    private fun key(target: GatewayAvailabilityTarget): String {
        val config = requireNotNull(target.config)
        return "${GatewayClientDiagnostics.routeIdentity(config)}|${researchHash(target.model)}|${researchHash(config.profileRevision.orEmpty())}"
    }

    private fun epoch(target: GatewayAvailabilityTarget) = "${networkEpoch()}|${processEpoch(requireNotNull(target.config))}"

    private fun observeTarget(target: GatewayAvailabilityTarget, background: Boolean): Flow<GatewayAvailability> = flow {
        val key = key(target)
        val entry = synchronized(lock) {
            val current = entries.getOrPut(key) { Entry(target, epoch(target), now()) }
            if (current.epoch != epoch(target)) {
                current.epoch = epoch(target)
                current.generation++
                current.state.value = current.state.value.unverified()
                current.consecutiveFailures = 0
                current.unavailableObservationKey = null
                current.unavailableStatus = null
                current.retryObservedAtMillis = null
                current.retryNotBeforeMillis = 0
                current.nextCheckMillis = now()
                current.lastScheduledProbeMillis = null
            } else if ((routeAvailable as? StateFlow<Boolean>)?.value == false) {
                current.state.value = current.state.value.unverified(GatewayAvailabilityStatus.OFFLINE)
            } else {
                current.state.value = current.state.value.fresh(now())
            }
            current.references++
            if (!background) current.activeReferences++
            if (current.polling == null) {
                current.polling = scope.launch(start = CoroutineStart.LAZY) { poll(current) }.also { it.start() }
            }
            current
        }
        try {
            emitAll(entry.state)
        } finally {
            synchronized(lock) {
                entry.references--
                if (!background) entry.activeReferences--
                if (entry.references == 0) {
                    entry.polling?.cancel()
                    entry.polling = null
                    entry.generation++
                }
                while (entries.size > 128) {
                    val oldestIdle = entries.entries.firstOrNull { it.value.references == 0 } ?: break
                    entries.remove(oldestIdle.key)
                }
            }
        }
    }

    private suspend fun poll(entry: Entry) {
        // A previous failed probe is not a new network recovery. Reattaching a
        // screen must retain its route's backoff instead of starting another read.
        var wasUnavailable = false
        var previousRouteRevision: Long? = null
        combine(routeAvailable, routeChanges) { available, revision -> available to revision }.distinctUntilChanged().collectLatest { (available, revision) ->
            val recovered = available && (wasUnavailable || previousRouteRevision?.let { it != revision } == true)
            previousRouteRevision = revision
            wasUnavailable = !available
            synchronized(lock) {
                entry.generation++
                if (!available) entry.state.value = entry.state.value.unverified(GatewayAvailabilityStatus.OFFLINE)
                if (recovered) {
                    entry.state.value = entry.state.value.unverified()
                    entry.consecutiveFailures = 0
                    entry.unavailableObservationKey = null
                    entry.unavailableStatus = null
                    entry.retryObservedAtMillis = null
                    entry.retryNotBeforeMillis = 0
                    entry.nextCheckMillis = now()
                    entry.lastScheduledProbeMillis = null
                }
            }
            if (available) pollAvailable(entry, recovered)
        }
    }

    private suspend fun pollAvailable(entry: Entry, recovered: Boolean) = coroutineScope {
        launch {
            entry.state.collectLatest { observed ->
                val remaining = observed.freshnessRemainingMillis(now())
                if (remaining > 0) {
                    delay(remaining)
                    synchronized(lock) {
                        if (entry.state.value == observed) entry.state.value = observed.fresh(now())
                    }
                }
            }
        }
        var running: Job? = null
        var forceRefresh = recovered
        while (isActive) {
            val changed = synchronized(lock) {
                val currentEpoch = epoch(entry.target)
                if (entry.epoch != currentEpoch) {
                    entry.epoch = currentEpoch
                    entry.generation++
                    entry.state.value = entry.state.value.unverified()
                    entry.consecutiveFailures = 0
                    entry.unavailableObservationKey = null
                    entry.unavailableStatus = null
                    entry.retryObservedAtMillis = null
                    entry.retryNotBeforeMillis = 0
                    entry.nextCheckMillis = now()
                    entry.lastScheduledProbeMillis = null
                    true
                } else {
                    entry.state.value = entry.state.value.fresh(now())
                    false
                }
            }
            if (changed) {
                running?.cancel()
                running = null
                forceRefresh = true
            }
            val nextCheck = synchronized(lock) {
                val cadence = pollMillis(entry)
                if (entry.cadenceMillis != cadence) {
                    entry.cadenceMillis = cadence
                    entry.nextCheckMillis = entry.lastScheduledProbeMillis?.plus(cadence) ?: now()
                    entry.retryObservedAtMillis?.let { observed ->
                        entry.unavailableStatus?.let { status -> entry.retryNotBeforeMillis = observed + retryDelayMillis(entry, status) }
                    }
                }
                maxOf(entry.nextCheckMillis, entry.retryNotBeforeMillis)
            }
            if (now() >= nextCheck && running?.isActive != true) {
                val generation = synchronized(lock) { entry.generation }
                val requestRefresh = forceRefresh
                forceRefresh = false
                // Advance from the scheduled time, skipping missed ticks instead of bursting.
                synchronized(lock) {
                    val cadence = entry.cadenceMillis
                    val missed = ((now() - entry.nextCheckMillis).coerceAtLeast(0) / cadence)
                    entry.lastScheduledProbeMillis = entry.nextCheckMillis + missed * cadence
                    entry.nextCheckMillis = requireNotNull(entry.lastScheduledProbeMillis) + cadence
                }
                running = launch {
                    probes.withPermit { refresh(entry, generation, requestRefresh) }
                }
            }
            delay(1_000)
        }
    }

    private suspend fun refresh(entry: Entry, generation: Long, forceRefresh: Boolean) {
        val network = networkEpoch()
        val config = requireNotNull(entry.target.config)
        var observationFailed = false
        var unavailableReason: String? = null
        val state = try {
            val probe = withTimeout(8_000) { source.probe(config, entry.target.model, forceRefresh) }
            unavailableReason = probe.readiness?.reasonCode
            observedState(probe, entry.state.value)
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            observationFailed = true
            GatewayAvailability(status = GatewayAvailabilityStatus.CHECKING)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            observationFailed = true
            unavailableReason = LocalServiceHealth.classify(failure).name
            GatewayAvailability(status = failureStatus(failure))
        }
        synchronized(lock) {
            if (entry.generation != generation || network != networkEpoch() || entry.references == 0) return
            val unavailable = observationFailed || state.status in setOf(GatewayAvailabilityStatus.NOT_READY, GatewayAvailabilityStatus.MODEL_UNAVAILABLE, GatewayAvailabilityStatus.MODEL_LOADING, GatewayAvailabilityStatus.DRAINING, GatewayAvailabilityStatus.NOT_GATEWAY, GatewayAvailabilityStatus.INCOMPATIBLE)
            if (unavailable) {
                val key = "${state.status}|$unavailableReason"
                if (entry.unavailableObservationKey != key) entry.consecutiveFailures = 0
                entry.unavailableObservationKey = key
                entry.consecutiveFailures = (entry.consecutiveFailures + 1).coerceAtMost(4)
                entry.unavailableStatus = state.status
                entry.retryObservedAtMillis = now()
                entry.retryNotBeforeMillis = now() + retryDelayMillis(entry, state.status)
            } else {
                entry.consecutiveFailures = 0
                entry.unavailableObservationKey = null
                entry.unavailableStatus = null
                entry.retryObservedAtMillis = null
                entry.retryNotBeforeMillis = 0
            }
            val instance = processEpoch(config)
            // A new instance discovered by another reader cannot bless an old cached response.
            entry.state.value = if (state.instanceId != null && instance != "unobserved" && instance != state.instanceId) {
                entry.state.value.unverified()
            } else {
                state.copy(
                    lastObservedStatus = state.lastObservedStatus ?: entry.state.value.lastObservedStatus,
                    lastObservedAtMillis = state.lastObservedAtMillis ?: entry.state.value.lastObservedAtMillis
                ).fresh(now())
            }
            entry.epoch = epoch(entry.target)
        }
    }

    private fun pollMillis(entry: Entry): Long = if (entry.activeReferences > 0) GATEWAY_AVAILABILITY_POLL_MILLIS else GATEWAY_BACKGROUND_POLL_MILLIS

    private fun retryDelayMillis(entry: Entry, status: GatewayAvailabilityStatus): Long {
        val background = entry.activeReferences == 0
        if (status in setOf(GatewayAvailabilityStatus.ACCESS_DENIED, GatewayAvailabilityStatus.INCOMPATIBLE, GatewayAvailabilityStatus.NOT_GATEWAY)) {
            return if (background) 900_000L else 60_000L
        }
        val ceiling = if (background) {
            900_000L
        } else if (status in setOf(GatewayAvailabilityStatus.MODEL_LOADING, GatewayAvailabilityStatus.DRAINING)) {
            20_000L
        } else {
            60_000L
        }
        return (pollMillis(entry) * (1L shl (entry.consecutiveFailures - 1).coerceAtLeast(0))).coerceAtMost(ceiling)
    }

    private fun observedState(probe: GatewayAvailabilityProbe, previous: GatewayAvailability): GatewayAvailability {
        val capability = probe.capabilities
            ?: return GatewayAvailability(status = if (probe.readiness == null) GatewayAvailabilityStatus.NOT_GATEWAY else GatewayAvailabilityStatus.INCOMPATIBLE)
        if (capability.version.isNullOrBlank() && capability.gatewayVersion.isNullOrBlank()) {
            return GatewayAvailability(status = GatewayAvailabilityStatus.INCOMPATIBLE)
        }
        val readiness = probe.readiness ?: return GatewayAvailability()
        if (!capability.instanceId.isNullOrBlank() && capability.instanceId != readiness.instanceId) return GatewayAvailability()
        val elapsed = now()
        val clientAge = readiness.receivedAtMonotonicMillis?.let { elapsed - it } ?: (wallNow() - readiness.receivedAtMillis)
        val serverAge = readiness.observationAgeMillis ?: 0
        val age = if (clientAge < 0 || serverAge < 0 || serverAge > Long.MAX_VALUE - clientAge) Long.MAX_VALUE else clientAge + serverAge
        val observedAt = readiness.observedAtMillis ?: readiness.receivedAtMillis
        val candidate = elapsed - age.coerceAtMost(GATEWAY_AVAILABILITY_FRESH_MILLIS)
        // A cached response keeps its original evidence age across client and server caches.
        val observed = previous.observedAtElapsedMillis?.takeIf { previous.observedAtMillis == observedAt && previous.instanceId == readiness.instanceId }?.let { minOf(it, candidate) } ?: candidate
        val status = when {
            readiness.draining -> GatewayAvailabilityStatus.DRAINING
            readiness.modelLoading == true -> GatewayAvailabilityStatus.MODEL_LOADING
            readiness.selectedModelAvailable == false -> GatewayAvailabilityStatus.MODEL_UNAVAILABLE
            !readiness.ready || readiness.generationReady != true || readiness.selectedModelAvailable != true -> GatewayAvailabilityStatus.NOT_READY
            readiness.canAccept == false -> GatewayAvailabilityStatus.NOT_READY
            readiness.availableWorkers == 0L -> if (readiness.canAccept == true && readiness.canQueue == true) GatewayAvailabilityStatus.BUSY_QUEUE else GatewayAvailabilityStatus.NOT_READY
            readiness.ready && readiness.generationReady == true && readiness.selectedModelAvailable == true -> GatewayAvailabilityStatus.ONLINE
            else -> GatewayAvailabilityStatus.NOT_READY
        }
        return GatewayAvailability(
            status = status,
            observedAtMillis = observedAt,
            observedAtElapsedMillis = observed,
            instanceId = readiness.instanceId,
            lastObservedStatus = status,
            lastObservedAtMillis = observedAt,
            conversationSyncSupported = capability.supportsFeature("conversation_sync_v1")
        ).fresh(now())
    }
}

private fun failureStatus(failure: Exception): GatewayAvailabilityStatus {
    val causes = generateSequence<Throwable>(failure) { it.cause }.take(8).toList()
    return when {
        causes.any { it is GatewayDashboardAccessDenied } -> GatewayAvailabilityStatus.ACCESS_DENIED
        causes.any { it is ConnectException || it is NoRouteToHostException || it is UnknownHostException } -> GatewayAvailabilityStatus.OFFLINE
        causes.any {
            it is LocalServiceUnavailableException && it.health in setOf(ConnectionHealth.DNS_FAILED, ConnectionHealth.ROUTE_UNAVAILABLE, ConnectionHealth.CONNECTION_REFUSED)
        } -> GatewayAvailabilityStatus.OFFLINE
        causes.any { it is IllegalArgumentException || it is kotlinx.serialization.SerializationException } -> GatewayAvailabilityStatus.INCOMPATIBLE
        else -> GatewayAvailabilityStatus.CHECKING
    }
}
