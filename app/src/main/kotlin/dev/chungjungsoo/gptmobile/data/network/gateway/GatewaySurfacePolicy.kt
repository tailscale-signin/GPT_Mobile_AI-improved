package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig

/** Remember only definite route capabilities, never a timeout, authentication failure or server error. */
internal class GatewaySurfacePolicy(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val ttlMillis: Long = 300_000L
) {
    enum class Surface { CAPABILITIES, PROPS, HEALTH }
    private data class Unavailable(val status: Int, val expiresAt: Long)
    private val entries = linkedMapOf<Pair<String, Surface>, Unavailable>()

    @Synchronized
    fun rememberedStatus(identity: String, surface: Surface): Int? {
        val key = identity to surface
        val entry = entries[key] ?: return null
        if (entry.expiresAt <= now()) {
            entries.remove(key)
            return null
        }
        return entry.status
    }

    @Synchronized
    fun observe(identity: String, surface: Surface, status: Int, code: String? = null) {
        val key = identity to surface
        val definite = when (surface) {
            Surface.CAPABILITIES -> status == 404 || status == 501
            Surface.PROPS, Surface.HEALTH -> status == 403 && code == "remote_surface_not_admitted"
        }
        if (definite) {
            if (entries.size >= 128 && key !in entries) entries.remove(entries.keys.first())
            entries[key] = Unavailable(status, now() + ttlMillis)
        } else if (status in 200..299) {
            entries.remove(key)
        }
    }

    @Synchronized
    fun invalidate() = entries.clear()

    @Synchronized
    fun invalidate(identity: String) {
        entries.keys.removeAll { it.first == identity }
    }

    companion object {
        val shared = GatewaySurfacePolicy()

        fun identity(config: ProviderRequestConfig): String =
            "${GatewayClientDiagnostics.routeIdentity(config)}|${LocalServiceHealth.networkEpoch}|${GatewayClientDiagnostics.capabilityEpoch(config)}"
    }
}
