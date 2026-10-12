package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.model.EndpointLocality
import dev.chungjungsoo.gptmobile.data.model.endpointLocality
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ConnectionHealth {
    DNS_FAILED,
    ROUTE_UNAVAILABLE,
    CONNECTION_REFUSED,
    AUTHENTICATION_FAILED,
    CONNECT_TIMEOUT,
    HTTP_ERROR,
    MCP_HANDSHAKE_FAILED,
    STREAM_INTERRUPTED,
    HEALTHY,
    UNKNOWN
}

/** DNS is host scoped; TCP/protocol failures are endpoint scoped. TCP alone is never MCP health. */
internal object LocalServiceHealth {
    private data class State(val health: ConnectionHealth, val failures: Int, val until: Long)
    private val states = ConcurrentHashMap<String, State>()
    private val epoch = AtomicLong()
    val networkEpoch: Long get() = epoch.get()
    private fun now() = System.nanoTime() / 1_000_000
    private fun endpoint(url: String): URI? = runCatching { URI(url) }.getOrNull()?.takeIf { it.host != null }
    private fun port(uri: URI) = if (uri.port >= 0) {
        uri.port
    } else if (uri.scheme == "https") {
        443
    } else {
        80
    }
    private fun key(uri: URI) = "${uri.scheme}://${uri.host.lowercase()}:${port(uri)}${uri.path.orEmpty()}"
    private fun dnsKey(uri: URI) = "dns:${uri.host.lowercase()}"

    fun networkChanged() {
        states.clear()
        epoch.incrementAndGet()
    }

    fun requireAvailable(url: String) {
        val uri = endpoint(url) ?: return
        val time = now()
        val cooling = listOf(key(uri), dnsKey(uri)).mapNotNull(states::get).firstOrNull { it.until > time }
        if (cooling != null) {
            throw IOException("LOCAL_SERVICE_UNREACHABLE: ${cooling.health}; endpoint=${uri.host}:${port(uri)}; retryAfterMs=${cooling.until - time}. Check this connection; no alternate endpoint was used.")
        }
    }

    fun health(url: String): ConnectionHealth {
        val uri = endpoint(url) ?: return ConnectionHealth.UNKNOWN
        return states[dnsKey(uri)]?.takeIf { it.until > now() }?.health ?: states[key(uri)]?.health ?: ConnectionHealth.UNKNOWN
    }

    fun checkAgain(url: String) {
        val uri = endpoint(url) ?: return
        states.remove(key(uri))
        states.remove(dnsKey(uri))
    }

    fun recordHandshakeSuccess(url: String) {
        val uri = endpoint(url) ?: return
        trim()
        states.remove(dnsKey(uri))
        states[key(uri)] = State(ConnectionHealth.HEALTHY, 0, 0)
    }

    fun classify(failure: Throwable): ConnectionHealth {
        val causes = generateSequence(failure) { it.cause?.takeUnless { cause -> cause === it } }.take(8).toList()
        val message = causes.joinToString { it.message.orEmpty().take(512) }.lowercase()
        return when {
            causes.any { it is java.net.UnknownHostException } -> ConnectionHealth.DNS_FAILED
            causes.any { it is java.net.NoRouteToHostException } -> ConnectionHealth.ROUTE_UNAVAILABLE
            causes.any { it is java.net.SocketTimeoutException || it.javaClass.simpleName == "ConnectTimeoutException" } || "timed out" in message -> ConnectionHealth.CONNECT_TIMEOUT
            causes.any { it is ConnectException } && ("refused" in message || "econnrefused" in message) -> ConnectionHealth.CONNECTION_REFUSED
            causes.any { it is ConnectException } -> ConnectionHealth.ROUTE_UNAVAILABLE
            Regex("\\b(?:401|403)\\b").containsMatchIn(message) && ("http" in message || "unauthoriz" in message || "forbidden" in message) -> ConnectionHealth.AUTHENTICATION_FAILED
            "mcp version" in message || "initializ" in message || "handshake" in message -> ConnectionHealth.MCP_HANDSHAKE_FAILED
            "http" in message || causes.any { it.javaClass.simpleName in setOf("ModernMcpError", "StreamableHttpError") } -> ConnectionHealth.HTTP_ERROR
            listOf("stream", "unexpected end", "connection reset", "socket closed").any { it in message } -> ConnectionHealth.STREAM_INTERRUPTED
            else -> ConnectionHealth.UNKNOWN
        }
    }

    fun recordFailure(url: String, failure: Throwable) {
        if (failure is kotlinx.coroutines.CancellationException) return
        val uri = endpoint(url) ?: return
        val health = classify(failure)
        val scope = if (health == ConnectionHealth.DNS_FAILED) dnsKey(uri) else key(uri)
        trim()
        val count = ((states[scope]?.failures ?: 0) + 1).coerceAtMost(8)
        val delay = (1_000L shl count.coerceAtMost(5)) + kotlin.random.Random.nextLong(0, 1000)
        states[scope] = State(health, count, now() + delay)
    }

    private fun trim() {
        if (states.size >= 128) states.keys.firstOrNull()?.let(states::remove)
    }

    suspend fun probe(url: String) = withContext(Dispatchers.IO) {
        if (endpointLocality(url) == EndpointLocality.EXTERNAL) return@withContext
        val uri = endpoint(url) ?: return@withContext
        requireAvailable(url)
        try {
            Socket().use { it.connect(InetSocketAddress(uri.host, port(uri)), 1_000) }
        } catch (failure: IOException) {
            recordFailure(url, failure)
            throw IOException("LOCAL_SERVICE_UNREACHABLE: ${classify(failure)}; endpoint=${uri.host}:${port(uri)}; TCP probe failed; configured endpoint was preserved", failure)
        }
    }
}
