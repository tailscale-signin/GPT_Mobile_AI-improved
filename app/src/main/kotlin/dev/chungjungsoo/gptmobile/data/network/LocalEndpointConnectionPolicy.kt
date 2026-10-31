package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.model.EndpointLocality
import dev.chungjungsoo.gptmobile.data.model.endpointLocality
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** A local server observed closing reused sockets gets fresh HTTP/1 connections for 10 minutes. */
internal object LocalEndpointConnectionPolicy {
    private val until = ConcurrentHashMap<String, Long>()
    private fun route(endpoint: String): String = runCatching {
        val uri = URI(endpoint.trim())
        "${uri.scheme.lowercase()}://${uri.host.lowercase()}:${uri.port}"
    }.getOrElse { endpoint.trim().trimEnd('/') }

    fun peerClosed(endpoint: String) {
        if (endpointLocality(endpoint) == EndpointLocality.EXTERNAL) return
        if (until.size >= 64) until.keys.firstOrNull()?.let(until::remove)
        until[route(endpoint)] = System.nanoTime() / 1_000_000 + 600_000
    }

    /** Synthetic metadata/template probes have no tool effects and may retry once. */
    suspend fun <T> probe(endpoint: String, action: suspend () -> T): T {
        try {
            return action()
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException || generateSequence<Throwable>(failure) { it.cause?.takeUnless { cause -> cause === it } }.take(8).any { it is javax.net.ssl.SSLException } || !ResilientStreamingClient.isPrematureConnectionClose(failure)) throw failure
            peerClosed(endpoint)
            kotlinx.coroutines.delay(250)
            return action()
        }
    }

    fun freshConnection(endpoint: String): Boolean {
        val key = route(endpoint)
        val deadline = until[key] ?: return false
        if (deadline > System.nanoTime() / 1_000_000) return true
        until.remove(key)
        return false
    }
}
