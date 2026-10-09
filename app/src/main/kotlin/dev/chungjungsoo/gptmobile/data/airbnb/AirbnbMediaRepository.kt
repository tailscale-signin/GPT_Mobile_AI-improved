package dev.chungjungsoo.gptmobile.data.airbnb

import dev.chungjungsoo.gptmobile.data.agent.tool.AgentToolResolver
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProductImageProvider
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

@Singleton
class AirbnbMediaRepository @Inject constructor(private val tools: AgentToolResolver) {
    private val client = HttpClient(OkHttp) {
        followRedirects = false
        install(HttpTimeout)
    }
    private val cache = linkedMapOf<String, ByteArray>()
    private val mutex = Mutex()
    private val locks = Array(16) { Mutex() }
    private val requests = Semaphore(2)

    suspend fun photo(owner: String?, raw: String): ByteArray? {
        val url = AirbnbListings.photoUrl(raw) ?: return null
        if (!tools.pluginMediaAllowed(owner, ToolPluginId.AIRBNB)) return null
        return locks[(url.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val retained = mutex.withLock { cache.remove(url)?.also { cache[url] = it } }
            if (retained != null) return@withLock retained.takeIf { tools.pluginMediaAllowed(owner, ToolPluginId.AIRBNB) }
            val bytes = requests.withPermit {
                if (!tools.pluginMediaAllowed(owner, ToolPluginId.AIRBNB)) return@withPermit null
                try {
                    client.prepareGet(url) {
                        timeout {
                            requestTimeoutMillis = 12_000
                            connectTimeoutMillis = 8_000
                            socketTimeoutMillis = 8_000
                        }
                    }.execute { response ->
                        if (response.status.value != 200 || (response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > MAX_BYTES) return@execute null
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        val input = response.bodyAsChannel()
                        while (true) {
                            val count = input.readAvailable(buffer)
                            if (count < 0) break
                            if (output.size() + count > MAX_BYTES) return@execute null
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray().takeIf(AmazonProductImageProvider::validImage)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            if (!tools.pluginMediaAllowed(owner, ToolPluginId.AIRBNB)) return@withLock null
            if (bytes != null) {
                mutex.withLock {
                    cache[url] = bytes
                    while (cache.size > 24 || cache.values.sumOf { it.size } > 16 * 1_048_576) cache.remove(cache.keys.first())
                }
            }
            bytes
        }
    }

    private companion object {
        const val MAX_BYTES = 3 * 1_048_576
    }
}
