package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.isPrivateDestination
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal data class GatewayDashboardTarget(
    val selectedProfileUid: String,
    val name: String,
    val host: String,
    val explicit: Boolean,
    val config: ProviderRequestConfig?,
    val problem: String? = null
) {
    val identity: String get() = config?.let(::gatewayDashboardIdentity) ?: "unavailable:$selectedProfileUid"
}

/** An explicit engineering connection wins; an invalid selection never picks another profile. */
internal fun gatewayDashboardTarget(selectedProfileUid: String, profiles: List<PlatformV2>, gatewayProfileUid: String?): GatewayDashboardTarget? {
    val selected = profiles.singleOrNull { it.uid == selectedProfileUid && it.enabled } ?: return null
    val explicit = !gatewayProfileUid.isNullOrBlank()
    val profile = if (explicit) profiles.singleOrNull { it.uid == gatewayProfileUid && it.enabled } else selected
    fun available(item: PlatformV2) = item.compatibleType in setOf(ClientType.LLAMA, ClientType.OLLAMA) || item.compatibleType != ClientType.LITERT_LM && item.isPrivateDestination()
    if (profile == null || !available(profile)) {
        return if (explicit) GatewayDashboardTarget(selectedProfileUid, "Gateway", "", true, null, "The selected gateway connection is unavailable.") else null
    }
    val address = runCatching { URI(profile.apiUrl.trim()) }.getOrNull()
    if (address == null || address.scheme !in setOf("http", "https") || address.host.isNullOrBlank() || address.rawUserInfo != null || address.rawQuery != null || address.rawFragment != null) {
        return GatewayDashboardTarget(selectedProfileUid, profile.name, "", explicit, null, "Check this gateway address in GitHub settings.")
    }
    val host = address.host + if (address.port >= 0) ":${address.port}" else ""
    return GatewayDashboardTarget(selectedProfileUid, profile.name, host, explicit, ProviderRequestConfig(profile.apiUrl, profile.token, profileUid = profile.uid))
}

internal enum class GatewayDashboardPhase { HIDDEN, CONNECTING, LIVE, STALE, UNAVAILABLE, DENIED }

internal data class GatewayDashboardState(
    val target: GatewayDashboardTarget? = null,
    val phase: GatewayDashboardPhase = GatewayDashboardPhase.HIDDEN,
    val observation: GatewayDashboardObservation? = null,
    val message: String? = null
) {
    fun isFresh(now: Long): Boolean = phase == GatewayDashboardPhase.LIVE && observation?.let { now - it.receivedAtMillis in 0L..15_000L } == true
    fun recentDevices(now: Long): Int? = observation?.snapshot?.devices?.takeIf { devices -> devices.all { it.state in setOf("active", "recent", "idle") } }?.count { it.state in setOf("active", "recent") }?.takeIf { isFresh(now) }
    fun openJobs(now: Long): Long? = observation?.snapshot?.openJobs?.takeIf { isFresh(now) }
}

/** Owned by the visible screen's coroutine: leaving Status cancels HTTP and the poll timer. */
internal class GatewayDashboardMonitor(private val source: GatewayDashboardSource) {
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
        publish(GatewayDashboardState(target, if (saved == null) GatewayDashboardPhase.CONNECTING else GatewayDashboardPhase.STALE, saved))
        var failures = 0
        while (true) {
            var pause = 5_000L
            val next = try {
                val observation = source.read(config)
                currentCoroutineContext().ensureActive()
                failures = 0
                saved = observation
                if (observation == null) {
                    pause = 60_000
                    GatewayDashboardState(target, if (target.explicit) GatewayDashboardPhase.UNAVAILABLE else GatewayDashboardPhase.HIDDEN, message = "Update the desktop gateway to view live statistics.")
                } else {
                    GatewayDashboardState(target, GatewayDashboardPhase.LIVE, observation)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: GatewayDashboardAccessDenied) {
                saved = null
                pause = 60_000
                GatewayDashboardState(target, GatewayDashboardPhase.DENIED, message = "Check gateway access in GitHub settings.")
            } catch (_: Exception) {
                failures = (failures + 1).coerceAtMost(3)
                pause = 15_000L * (1L shl (failures - 1))
                GatewayDashboardState(target, if (saved == null) GatewayDashboardPhase.UNAVAILABLE else GatewayDashboardPhase.STALE, saved, if (saved == null) "Gateway is unavailable. Check its connection." else "Refresh paused. Showing saved measurements.")
            }
            currentCoroutineContext().ensureActive()
            publish(next)
            withTimeoutOrNull(pause) { refresh.receive() }
        }
    }
}
