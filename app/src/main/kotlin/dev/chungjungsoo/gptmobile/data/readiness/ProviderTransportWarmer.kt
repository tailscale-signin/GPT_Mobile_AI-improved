package dev.chungjungsoo.gptmobile.data.readiness

import android.os.SystemClock
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.request
import io.ktor.http.HttpMethod
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

/**
 * Bounded, unauthenticated HEAD to a selected public HTTPS origin. DNS/TLS/connection
 * pooling uses the same Ktor/OkHttp client as actual inference. No prompt, credentials,
 * model request, API path or query is transmitted.
 */
@Singleton
class ProviderTransportWarmer @Inject constructor(private val client: NetworkClient) {
    private val attempts = ConcurrentHashMap<String, Long>()

    suspend fun warm(endpoint: String, networkEpoch: Long, permissionEpoch: Long) {
        val origin = providerWarmOrigin(endpoint) ?: return
        val key = "$networkEpoch:$permissionEpoch:$origin"
        val now = SystemClock.elapsedRealtime()
        val admitted = AtomicBoolean(false)
        attempts.compute(key) { _, last ->
            if (last == null || now - last !in 0..60_000L) {
                admitted.set(true)
                now
            } else last
        }
        if (!admitted.get()) return
        if (attempts.size > 64) attempts.keys.filter { it != key }.take(32).forEach { attempts.remove(it) }
        try {
            withTimeout(1_800L) {
                client().request(origin) {
                    method = HttpMethod.Head
                    timeout {
                        requestTimeoutMillis = 1_600L
                        connectTimeoutMillis = 1_200L
                        socketTimeoutMillis = 1_500L
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            attempts.remove(key, now)
            throw cancelled
        } catch (_: Exception) {
            // Connection preparation is optional and must never hold up accepted Send.
        }
    }
}

/** Pure public-origin allowlist; strips credentials, routes, query and fragment. */
internal fun providerWarmOrigin(endpoint: String): String? {
    if (endpoint.length !in 8..2_048) return null
    val uri = runCatching { URI(endpoint.trim()) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null) return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    if (host.length > 253 || !host.contains('.') || host.contains(':') ||
        !host.matches(Regex("[a-z0-9.-]+")) ||
        host == "localhost" || host.endsWith(".local") || host.endsWith(".internal") ||
        host.endsWith(".test") || host.endsWith(".invalid") ||
        host.all { it.isDigit() || it == '.' }) return null
    val port = uri.port
    if (port != -1 && port !in 1..65_535) return null
    val suffix = if (port == -1 || port == 443) "" else ":$port"
    return "https://$host$suffix/"
}
