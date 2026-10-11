package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.model.EndpointLocality
import dev.chungjungsoo.gptmobile.data.model.endpointLocality
import java.util.concurrent.ConcurrentHashMap

/** A local server observed closing reused sockets gets fresh HTTP/1 connections for 10 minutes. */
internal object LocalEndpointConnectionPolicy {
    private val until = ConcurrentHashMap<String, Long>()
    fun peerClosed(endpoint: String) {
        if (endpointLocality(endpoint) == EndpointLocality.EXTERNAL) return
        if (until.size >= 64) until.keys.firstOrNull()?.let(until::remove)
        until[endpoint] = System.nanoTime() / 1_000_000 + 600_000
    }
    fun freshConnection(endpoint: String): Boolean {
        val deadline = until[endpoint] ?: return false
        if (deadline > System.nanoTime() / 1_000_000) return true
        until.remove(endpoint)
        return false
    }
}
