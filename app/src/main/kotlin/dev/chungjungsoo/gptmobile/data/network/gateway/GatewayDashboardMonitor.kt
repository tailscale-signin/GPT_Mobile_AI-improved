package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.engineeringConfig
import dev.chungjungsoo.gptmobile.data.github.engineeringGatewayProfile
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull

internal data class GatewayDashboardTarget(
    val selectedProfileUid: String,
    val name: String,
    val host: String,
    val explicit: Boolean,
    val config: ProviderRequestConfig?,
    val problem: String? = null,
    val model: String? = null
) {
    val identity: String get() = config?.let(::gatewayDashboardIdentity) ?: "unavailable:$selectedProfileUid"
}

/** The selected engineering connection owns status; an inference address is not gateway discovery. */
internal fun gatewayDashboardTarget(selectedProfileUid: String, profiles: List<PlatformV2>, gatewayProfileUid: String?): GatewayDashboardTarget? {
    val selected = profiles.singleOrNull { it.uid == selectedProfileUid && it.enabled } ?: return null
    if (gatewayProfileUid.isNullOrBlank()) {
        return GatewayDashboardTarget(selectedProfileUid, "Gateway", "", false, null, "Select an engineering gateway connection in GitHub settings to view its status.")
    }
    val profile = engineeringGatewayProfile(profiles, gatewayProfileUid)
        ?: return GatewayDashboardTarget(selectedProfileUid, "Gateway", "", true, null, "The selected gateway connection is unavailable. Choose an enabled private Llama or Ollama connection in GitHub settings.")
    val address = runCatching { URI(profile.apiUrl.trim()) }.getOrNull()
    if (address == null || address.scheme !in setOf("http", "https") || address.host.isNullOrBlank() || address.rawUserInfo != null || address.rawQuery != null || address.rawFragment != null) {
        return GatewayDashboardTarget(selectedProfileUid, profile.name, "", true, null, "Check this gateway address in GitHub settings.")
    }
    val host = address.host + if (address.port >= 0) ":${address.port}" else ""
    return GatewayDashboardTarget(selectedProfileUid, profile.name, host, true, profile.engineeringConfig(), model = selected.model.takeIf(String::isNotBlank))
}

internal enum class GatewayDashboardPhase { HIDDEN, CONNECTING, LIVE, STALE, UNAVAILABLE, DENIED, RECONNECTING, DEGRADED, OFFLINE }

internal data class GatewayDashboardState(
    val target: GatewayDashboardTarget? = null,
    val phase: GatewayDashboardPhase = GatewayDashboardPhase.HIDDEN,
    val observation: GatewayDashboardObservation? = null,
    val message: String? = null,
    val readiness: GatewayReadinessObservation? = null
) {
    // Fresh statistics are independent of model readiness. A reachable gateway
    // with an unavailable model can still report accurate devices and jobs.
    fun isFresh(now: Long): Boolean = phase in setOf(GatewayDashboardPhase.LIVE, GatewayDashboardPhase.DEGRADED) && observation?.let { now - it.receivedAtMillis in 0L..15_000L } == true
    fun recentDevices(now: Long): Int? = observation?.snapshot?.devices?.takeIf { devices -> devices.all { it.state in setOf("active", "recent", "idle") } }?.count { it.state in setOf("active", "recent") }?.takeIf { isFresh(now) }
    fun openJobs(now: Long): Long? = observation?.snapshot?.openJobs?.takeIf { isFresh(now) }
}

/** Owned by the visible screen's coroutine: leaving Status cancels HTTP and the poll timer. */
internal class GatewayDashboardMonitor(private val source: GatewayDashboardSource, private val routeAvailable: Flow<Boolean> = flowOf(true)) {
    private val refresh = Channel<Unit>(Channel.CONFLATED)

    fun refresh() {
        refresh.trySend(Unit)
    }

    suspend fun observe(target: GatewayDashboardTarget?, previous: GatewayDashboardState, publish: (GatewayDashboardState) -> Unit) {
        if (target == null) {
            publish(GatewayDashboardState())
            return
        }
        val config = target.config
        if (config == null) {
            publish(GatewayDashboardState(target, GatewayDashboardPhase.UNAVAILABLE, message = target.problem))
            return
        }
        var saved = previous.observation.takeIf { previous.target?.identity == target.identity }
        var reconnecting = previous.phase in setOf(GatewayDashboardPhase.OFFLINE, GatewayDashboardPhase.STALE, GatewayDashboardPhase.UNAVAILABLE)
        routeAvailable.distinctUntilChanged().collectLatest { available ->
            if (!available) {
                reconnecting = true
                publish(GatewayDashboardState(target, GatewayDashboardPhase.OFFLINE, saved, "No network route. Saved jobs remain on the gateway; reconnect to check them."))
                return@collectLatest
            }
            // Refresh taps while offline or during a cancelled request share the
            // first recovery probe; they do not create a second polling loop.
            refresh.tryReceive()
            publish(
                GatewayDashboardState(
                    target,
                    if (reconnecting) {
                        GatewayDashboardPhase.RECONNECTING
                    } else if (saved == null) {
                        GatewayDashboardPhase.CONNECTING
                    } else {
                        GatewayDashboardPhase.STALE
                    },
                    saved
                )
            )
            var failures = 0
            while (true) {
                var pause = 5_000L
                if (failures > 0) publish(GatewayDashboardState(target, GatewayDashboardPhase.RECONNECTING, saved, "Checking gateway readiness…"))
                val next = try {
                    // A network callback proves only that Android has a route.
                    // Readiness is observed on the selected authenticated gateway.
                    val readiness = source.readiness(config, target.model)
                    val observation = source.read(config)
                    currentCoroutineContext().ensureActive()
                    check(readiness == null || observation == null || readiness.instanceId == observation.snapshot.instanceId) { "Gateway restarted during refresh." }
                    failures = 0
                    reconnecting = false
                    saved = observation
                    if (observation == null) {
                        pause = 60_000
                        GatewayDashboardState(target, if (target.explicit) GatewayDashboardPhase.UNAVAILABLE else GatewayDashboardPhase.HIDDEN, message = "Update the desktop gateway to view live statistics.")
                    } else {
                        val message = when {
                            readiness?.selectedModelAvailable == false -> "Gateway connected. The selected model is unavailable."
                            readiness?.draining == true -> "Gateway connected. New work is paused."
                            readiness?.ready == false -> "Gateway connected. Generation or durable storage is not ready."
                            readiness == null -> "Gateway connected. Model readiness was not reported."
                            else -> null
                        }
                        GatewayDashboardState(target, if (readiness?.ready == false) GatewayDashboardPhase.DEGRADED else GatewayDashboardPhase.LIVE, observation, message, readiness)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: GatewayDashboardAccessDenied) {
                    saved = null
                    GatewayDashboardState(target, GatewayDashboardPhase.DENIED, message = "Check gateway access in GitHub settings.")
                } catch (_: Exception) {
                    failures = (failures + 1).coerceAtMost(3)
                    reconnecting = true
                    pause = 15_000L * (1L shl (failures - 1))
                    GatewayDashboardState(target, if (saved == null) GatewayDashboardPhase.UNAVAILABLE else GatewayDashboardPhase.STALE, saved, if (saved == null) "Gateway is unavailable. Check its connection." else "Refresh paused. Showing saved measurements.")
                }
                currentCoroutineContext().ensureActive()
                publish(next)
                if (next.phase == GatewayDashboardPhase.DENIED) refresh.receive() else withTimeoutOrNull(pause) { refresh.receive() }
            }
        }
    }
}
