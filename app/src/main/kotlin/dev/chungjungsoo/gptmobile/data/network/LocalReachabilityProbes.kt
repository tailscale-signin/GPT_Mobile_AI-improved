package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.context.RouteCapabilityCache

/** Share one TCP observation for an exact service and network generation, never model/MCP readiness. */
internal class LocalReachabilityProbes(now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val observations = RouteCapabilityCache<Boolean>(now, positiveTtl = 500, negativeTtl = 0)

    suspend fun probe(key: String, connect: suspend () -> Unit): Boolean = observations.getOrLoad(key) {
        connect()
        true
    } == true

    fun invalidate() = observations.invalidate()
}
