package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.ProviderRetryPolicy
import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.util.WeakHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class GatewayJobPollResponse(val status: Int, val retryAfter: String?, val bodyCharacters: Int = 0)

/** One read owner and response-based deadline per authenticated saved job; no background timer. */
internal class GatewayJobPollCoordinator(
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallMillis: () -> Long = System::currentTimeMillis
) {
    private data class Observation(val result: GatewayJobResult?, val terminal: Boolean, val weight: Long)
    private class Entry {
        val read = Mutex()
        var users = 0
        var revision = 0L
        var runtime: String? = null
        var nextAllowedAt = Long.MIN_VALUE
        var observation: Observation? = null
    }

    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()

    init {
        synchronized(instances) { instances[this] = Unit }
    }

    suspend fun read(
        identity: String,
        jobId: String,
        runtime: () -> String,
        load: suspend (received: (GatewayJobPollResponse) -> Unit) -> GatewayJobResult?
    ): GatewayJobResult? {
        val key = "$identity|$jobId"
        val entry = synchronized(lock) {
            entries.getOrPut(key) { Entry() }.also { it.users++ }
        }
        try {
            return entry.read.withLock {
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (entry.runtime != null && entry.runtime != runtime()) {
                        entry.observation = null
                        // A new capabilities epoch invalidates evidence, not the
                        // Retry-After already received for this route and job.
                        entry.revision++
                    }
                    val observation = entry.observation
                    if (observation != null && (observation.terminal || entry.nextAllowedAt > elapsedMillis())) return@withLock observation.result
                }
                // A cancelled body read can still have received Retry-After. The
                // next observer waits for that deadline without inventing a result.
                val remaining = synchronized(lock) { ProviderRetryPolicy.remainingMillis(entry.nextAllowedAt, elapsedMillis()) }
                if (remaining > 0) delay(remaining)
                currentCoroutineContext().ensureActive()
                val revision = synchronized(lock) { entry.revision }
                var responseReceived = false
                var responseStatus: Int? = null
                var retainedWeight = 1024L
                val result = load { response ->
                    responseReceived = true
                    responseStatus = response.status
                    retainedWeight = 1024L + response.bodyCharacters.coerceAtLeast(0).toLong() * 4L
                    val wait = response.retryAfter?.let { ProviderRetryPolicy.retryAfterMillis(it, wallMillis()) }
                        ?.coerceAtLeast(DEFAULT_INTERVAL_MILLIS) ?: DEFAULT_INTERVAL_MILLIS
                    synchronized(lock) {
                        // Invalidating stale result evidence does not erase a
                        // deadline supplied by an already dispatched read.
                        entry.nextAllowedAt = maxOf(entry.nextAllowedAt, ProviderRetryPolicy.deadlineMillis(elapsedMillis(), wait))
                        entry.runtime = runtime()
                    }
                }
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (entry.revision != revision) return@withLock null
                    if (!responseReceived) entry.nextAllowedAt = ProviderRetryPolicy.deadlineMillis(elapsedMillis(), DEFAULT_INTERVAL_MILLIS)
                    entry.runtime = runtime()
                    val terminal = responseStatus == 200 &&
                        result != null &&
                        result.status.lowercase() in setOf("completed", "failed", "cancelled", "canceled", "interrupted") &&
                        result.terminal != false &&
                        result.continuationAllowed != true
                    entry.observation = Observation(result, terminal, retainedWeight)
                    result
                }
            }
        } finally {
            synchronized(lock) {
                entry.users--
                // Expired/terminal owners can leave the cache. Active cooldowns
                // retain their lightweight deadline even under cache pressure.
                while (entries.size > 128) {
                    val unused = entries.entries.firstOrNull {
                        it.value.users == 0 && (it.value.observation?.terminal == true || it.value.nextAllowedAt <= elapsedMillis())
                    } ?: break
                    entries.remove(unused.key)
                }
                var weight = entries.values.sumOf { it.observation?.weight ?: 0L }
                for (candidate in entries.values) {
                    if (weight <= MAX_RETAINED_WEIGHT) break
                    if (candidate.users != 0) continue
                    weight -= candidate.observation?.weight ?: 0L
                    candidate.observation = null
                }
            }
        }
    }

    /** Also gates the connection policy's bounded reconnect after a response body fails. */
    suspend fun awaitReadPermission(identity: String, jobId: String) {
        val remaining = remaining(identity, jobId)
        if (remaining > 0) delay(remaining)
        currentCoroutineContext().ensureActive()
    }

    private fun remaining(identity: String, jobId: String): Long = synchronized(lock) {
        entries["$identity|$jobId"]?.let { ProviderRetryPolicy.remainingMillis(it.nextAllowedAt, elapsedMillis()) } ?: 0L
    }

    /** A mutation discards evidence across verified/unverified authority aliases on the same credentials. */
    fun invalidate(identity: String, jobId: String) = synchronized(lock) {
        forEachRouteEntry(identity, jobId) { entry ->
            entry.revision++
            entry.observation = null
        }
    }

    /** Accepted replay events may invalidate old state, but never shorten Retry-After. */
    fun observeProgress(identity: String, jobId: String, progress: GatewayProgress) = synchronized(lock) {
        forEachRouteEntry(identity, jobId) { entry ->
            val saved = entry.observation?.result
            if (progress.generationChanged == true ||
                progress.resultAvailable == true &&
                saved?.resultAvailable != true ||
                progress.status != null &&
                !progress.status.equals(saved?.status, ignoreCase = true)
            ) {
                entry.revision++
                entry.observation = null
            }
        }
    }

    private inline fun forEachRouteEntry(identity: String, jobId: String, action: (Entry) -> Unit) {
        val route = identity.substringBefore('|')
        for ((key, entry) in entries) {
            if (key.substringBefore('|') == route && key.endsWith("|$jobId")) action(entry)
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MILLIS = 2_000L
        private const val MAX_RETAINED_WEIGHT = 16L * 1024 * 1024 + 64 * 1024
        private val owners = WeakHashMap<NetworkClient, GatewayJobPollCoordinator>()
        private val instances = WeakHashMap<GatewayJobPollCoordinator, Unit>()

        /** The normal API and provider recovery API use the same network owner. */
        fun forNetwork(network: NetworkClient): GatewayJobPollCoordinator = synchronized(owners) {
            owners.getOrPut(network) { GatewayJobPollCoordinator() }
        }

        /** Continuation/tool-result POSTs discard old observations; they never waive a received deadline. */
        fun continuing(config: ProviderRequestConfig) {
            val job = config.extraHeaders.entries.firstOrNull { it.key.equals("X-Gateway-Job-ID", true) }?.value
                ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) } ?: return
            val identity = gatewayJobObservationIdentity(config)
            synchronized(instances) { instances.keys.toList() }.forEach { it.invalidate(identity, job) }
        }

        /** Accepted command bindings are immutable and need no refresh during a known job cooldown. */
        fun remaining(config: ProviderRequestConfig, jobId: String): Long {
            val identity = gatewayJobObservationIdentity(config)
            return synchronized(instances) { instances.keys.toList() }.maxOfOrNull { it.remaining(identity, jobId) } ?: 0L
        }
    }
}

/** Different credentials/accounts cannot share results; display profiles on the same route can. */
internal fun gatewayJobObservationIdentity(config: ProviderRequestConfig): String {
    val authority = config.extraHeaders.entries
        .filter { it.key.equals("X-Gateway-Installation-ID", true) || it.key.equals("X-Gateway-Principal-ID", true) }
        .sortedBy { it.key.lowercase() }.joinToString("\n") { "${it.key.lowercase()}:${it.value}" }
    return "${GatewayClientDiagnostics.routeIdentity(config)}|${researchHash(authority)}"
}

internal fun gatewayJobObservationEpoch(config: ProviderRequestConfig): String =
    "${LocalServiceHealth.networkEpoch}|${GatewayClientDiagnostics.runtimeEpoch(config)}"
