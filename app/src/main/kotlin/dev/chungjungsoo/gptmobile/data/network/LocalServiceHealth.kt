package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.model.EndpointLocality
import dev.chungjungsoo.gptmobile.data.model.endpointLocality
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Short-lived shared transport health. Refused ports do not quarantine a whole host. */
internal object LocalServiceHealth {
    private val unavailableUntil = ConcurrentHashMap<String, Long>()
    private fun now() = System.nanoTime() / 1_000_000
    private fun endpoint(url: String): URI? = runCatching { URI(url) }.getOrNull()
        ?.takeIf { it.host != null && endpointLocality(url) != EndpointLocality.EXTERNAL }
    private fun port(uri: URI) = if (uri.port >= 0) {
        uri.port
    } else if (uri.scheme == "https") {
        443
    } else {
        80
    }

    fun requireAvailable(url: String) {
        val uri = endpoint(url) ?: return
        val time = now()
        unavailableUntil.entries.removeIf { it.value <= time }
        if ((unavailableUntil[uri.host] ?: 0) > time || (unavailableUntil["${uri.host}:${port(uri)}"] ?: 0) > time) {
            throw IOException("LOCAL_SERVICE_UNREACHABLE: temporarily unavailable; connectivity will be rechecked automatically")
        }
    }

    fun recordFailure(url: String, failure: Throwable) {
        val uri = endpoint(url) ?: return
        val causes = generateSequence(failure) { it.cause?.takeUnless { cause -> cause === it } }.take(8).toList()
        val refused = causes.any { it is ConnectException }
        val unreachable = refused || causes.any { it is java.net.SocketTimeoutException || it is java.net.NoRouteToHostException || it is java.net.UnknownHostException || it.javaClass.simpleName == "ConnectTimeoutException" }
        if (unreachable) {
            if (unavailableUntil.size >= 128) unavailableUntil.clear()
            unavailableUntil[if (refused) "${uri.host}:${port(uri)}" else uri.host] = now() + 10_000
        }
    }

    suspend fun probe(url: String) = withContext(Dispatchers.IO) {
        val uri = endpoint(url) ?: return@withContext
        requireAvailable(url)
        try {
            Socket().use { it.connect(InetSocketAddress(uri.host, port(uri)), 1_000) }
        } catch (failure: IOException) {
            val key = if (failure is ConnectException) "${uri.host}:${port(uri)}" else uri.host
            if (unavailableUntil.size >= 128) unavailableUntil.clear()
            unavailableUntil[key] = now() + 10_000
            throw IOException("LOCAL_SERVICE_UNREACHABLE: gateway health probe failed", failure)
        }
    }
}
