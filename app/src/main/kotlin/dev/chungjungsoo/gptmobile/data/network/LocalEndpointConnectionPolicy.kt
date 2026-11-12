package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.model.EndpointLocality
import dev.chungjungsoo.gptmobile.data.model.endpointLocality
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext

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

    /** Only read-only metadata/template probes belong here. Mutations and inference never do. */
    suspend fun <T> probe(endpoint: String, onStaleConnection: suspend () -> Unit = {}, action: suspend () -> T): T {
        val readId = java.util.UUID.randomUUID().toString().replace("-", "")
        try {
            return withContext(LocalMetadataReadAttempt(0, readId)) { action() }
        } catch (failure: Exception) {
            val causes = generateSequence<Throwable>(failure) { it.cause?.takeUnless { cause -> cause === it } }.take(8).toList()
            // A response rejection or invalid document is definitive, even when its
            // message happens to contain a transport word. Never bypass TLS or a hold.
            if (causes.any {
                    it is kotlinx.coroutines.CancellationException ||
                        it is javax.net.ssl.SSLException ||
                        it is java.security.cert.CertificateException ||
                        it is io.ktor.client.plugins.ResponseException ||
                        it is ProviderHttpFailure ||
                        it is LocalServiceUnavailableException ||
                        it is kotlinx.serialization.SerializationException ||
                        it is IllegalArgumentException
                }
            ) {
                throw failure
            }
            val closed = causes.any { it is java.io.EOFException } || ResilientStreamingClient.isPrematureConnectionClose(failure)
            val connect = causes.any {
                it is java.net.ConnectException ||
                    it is java.net.SocketTimeoutException ||
                    it is java.net.NoRouteToHostException ||
                    it is java.net.UnknownHostException ||
                    it is java.nio.channels.UnresolvedAddressException ||
                    it.javaClass.simpleName in setOf("ConnectTimeoutException", "SocketTimeoutException", "HttpRequestTimeoutException")
            }
            if (!closed && !connect) throw failure
            if (closed) {
                peerClosed(endpoint)
                // Closing an idle TLS socket can perform blocking I/O. A caller
                // resuming on Main must never do that cleanup on its own thread.
                withContext(Dispatchers.IO) { onStaleConnection() }
            }
            // One retry owner, two attempts total. Stagger simultaneous dashboard,
            // readiness and recovery reads without extending an outer request budget.
            kotlinx.coroutines.delay(kotlin.random.Random.nextLong(150, 451))
            return withContext(LocalMetadataReadAttempt(1, readId)) { action() }
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

/** This ordinal describes only our bounded read loop; it does not count engine or SDK retries. */
internal class LocalMetadataReadAttempt(val ordinal: Int, val readId: String = java.util.UUID.randomUUID().toString().replace("-", "")) :
    AbstractCoroutineContextElement(Key),
    ThreadContextElement<LocalMetadataReadAttempt?> {
    // Ktor's Logger callback is synchronous rather than suspending. Carry the
    // coroutine's exact read identity across thread switches; never match by URL
    // or time, which would merge concurrent reads of the same endpoint.
    override fun updateThreadContext(context: CoroutineContext): LocalMetadataReadAttempt? =
        current.get().also { current.set(this) }

    override fun restoreThreadContext(context: CoroutineContext, oldState: LocalMetadataReadAttempt?) {
        if (oldState == null) current.remove() else current.set(oldState)
    }

    companion object Key : CoroutineContext.Key<LocalMetadataReadAttempt> {
        private val current = ThreadLocal<LocalMetadataReadAttempt?>()

        fun correlateLog(message: String): String = current.get()?.let {
            "incident_id=${it.readId} · operation_id=${it.readId} · retry_attempt=${it.ordinal} · $message"
        } ?: message
    }
}
