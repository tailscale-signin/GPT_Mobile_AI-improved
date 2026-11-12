package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.context.RouteCapabilityCache
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

internal data class GatewayJobReadObservation(val value: GatewayJobResult?, val retryAfter: String? = null)

/**
 * Passive job readers share completed observations as well as in-flight reads.
 * No coroutine or timer survives its caller, and writes never enter this cache.
 */
internal class GatewayJobReadCoordinator(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) }
) {
    private data class Observation(val value: GatewayJobResult?, val interval: Long, val runtime: String, val version: Long)
    private data class Deadline(val at: Long, val runtime: String, val version: Long)
    private val lock = Any()
    private val deadlines = linkedMapOf<String, Deadline>()
    private val versions = linkedMapOf<String, Long>()
    private var versionClock = 0L
    private var versionFloor = 0L

    // A result can contain up to 4 MiB of retained text/tool calls. Keep this
    // separate from the much larger, small-metadata capability cache.
    private val observations = RouteCapabilityCache<Observation>(now, valueTtl = { it.interval }, maxEntries = 4)

    suspend fun result(config: ProviderRequestConfig, jobId: String, load: suspend () -> GatewayJobReadObservation): GatewayJobResult? {
        val resource = resource(config, jobId)
        val key = observationKey(config, resource)
        return observations.getOrLoad(
            key,
            isCurrent = { it != null && it.runtime == GatewayClientDiagnostics.runtimeEpoch(config) && it.version == version(resource) }
        ) {
            // Evicting a large result must not erase the server's polling deadline.
            val remaining = remaining(config, jobId)
            if (remaining > 0) pause(remaining)
            val capturedVersion = version(resource)
            val received = load()
            val runtime = GatewayClientDiagnostics.runtimeEpoch(config)
            val interval = retryAfterMillis(received.retryAfter).coerceAtLeast(MIN_POLL_MILLIS)
            val observedAt = now()
            val deadline = if (observedAt > Long.MAX_VALUE - interval) Long.MAX_VALUE else observedAt + interval
            synchronized(lock) {
                if (deadlines.size >= 128 && key !in deadlines) deadlines.remove(deadlines.keys.first())
                deadlines[key] = Deadline(deadline, runtime, capturedVersion)
            }
            Observation(received.value, interval, runtime, capturedVersion)
        }?.value
    }

    fun remaining(config: ProviderRequestConfig, jobId: String): Long {
        val resource = resource(config, jobId)
        val key = observationKey(config, resource)
        val runtime = GatewayClientDiagnostics.runtimeEpoch(config)
        return synchronized(lock) {
            val deadline = deadlines[key] ?: return@synchronized 0L
            if (deadline.runtime != runtime || deadline.version != (versions[resource] ?: versionFloor)) {
                deadlines.remove(key)
                0L
            } else {
                (deadline.at - now()).coerceAtLeast(0L)
            }
        }
    }

    /** A real control/continuation invalidates all observation variants for this authenticated job. */
    fun changed(config: ProviderRequestConfig, jobId: String) {
        val resource = resource(config, jobId)
        synchronized(lock) {
            if (versions.size >= 128 && resource !in versions) {
                // Never return to an earlier generation when bounded metadata is
                // evicted: an old payload or in-flight read may still exist.
                versionFloor = maxOf(versionFloor, versions.remove(versions.keys.first()) ?: versionFloor)
            }
            versions[resource] = ++versionClock
        }
    }

    private fun version(resource: String): Long = synchronized(lock) { versions[resource] ?: versionFloor }

    private fun resource(config: ProviderRequestConfig, jobId: String) = "${GatewayClientDiagnostics.routeIdentity(config)}|$jobId"

    private fun observationKey(config: ProviderRequestConfig, resource: String): String =
        "$resource|${scope(config)}"

    private fun retryAfterMillis(raw: String?): Long {
        val value = raw?.trim() ?: return MIN_POLL_MILLIS
        value.toLongOrNull()?.takeIf { it >= 0 }?.let { return if (it > Long.MAX_VALUE / 1_000) Long.MAX_VALUE else it * 1_000 }
        return runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - wallTime() }
            .getOrNull()?.coerceAtLeast(0L) ?: MIN_POLL_MILLIS
    }

    companion object {
        const val MIN_POLL_MILLIS = 2_000L
        val shared = GatewayJobReadCoordinator()

        fun continuing(config: ProviderRequestConfig) {
            config.extraHeaders.entries.firstOrNull { it.key.equals("X-Gateway-Job-ID", true) }?.value
                ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
                ?.let { shared.changed(config, it) }
        }

        /** Authority headers are omitted from routeIdentity, so bind them here explicitly. */
        fun scope(config: ProviderRequestConfig): String {
            val authority = config.extraHeaders.entries
                .filter { it.key.equals("X-Gateway-Installation-ID", true) || it.key.equals("X-Gateway-Principal-ID", true) }
                .sortedBy { it.key.lowercase() }.joinToString("\n") { "${it.key.lowercase()}:${it.value}" }
            return researchHash("${GatewayClientDiagnostics.routeIdentity(config)}|${researchHash(authority)}|${config.profileUid.orEmpty()}|${config.profileRevision.orEmpty()}|${LocalServiceHealth.networkEpoch}")
        }
    }
}
