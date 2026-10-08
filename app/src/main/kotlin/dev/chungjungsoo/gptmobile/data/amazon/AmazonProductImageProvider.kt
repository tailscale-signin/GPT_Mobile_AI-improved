package dev.chungjungsoo.gptmobile.data.amazon

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Only bounded image reads from Amazon's image CDN; no credentials or redirects. */
@Singleton
class AmazonProductImageProvider internal constructor(private val client: HttpClient, private val clock: Clock = Clock.systemUTC()) {
    @Inject constructor() : this(
        HttpClient(OkHttp) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout)
        }
    )

    private val mutex = Mutex()
    private val imageLocks = Array(16) { Mutex() }
    private val requests = Semaphore(2)
    private val cache = linkedMapOf<String, Pair<Long, ByteArray>>()

    suspend fun evict(urls: Collection<String>) {
        mutex.withLock { urls.forEach(cache::remove) }
    }

    suspend fun fetch(rawUrl: String, allowed: suspend () -> Boolean): ByteArray? {
        val url = AmazonProducts.imageUrl(rawUrl) ?: return null
        check(allowed()) { "Amazon image permission is disabled." }
        return imageLocks[(url.hashCode() and Int.MAX_VALUE) % imageLocks.size].withLock {
            check(allowed()) { "Amazon image permission was revoked." }
            val cached = mutex.withLock {
                cache.remove(url)?.takeIf { clock.millis() - it.first in 0..90 * 60_000L }?.also { cache[url] = it }?.second
            }
            if (cached != null) return@withLock cached
            val image = requests.withPermit {
                check(allowed()) { "Amazon image permission was revoked." }
                read(url)
            }
            check(allowed()) { "Amazon image permission was revoked." }
            if (image != null) {
                mutex.withLock {
                    check(allowed()) { "Amazon image permission was revoked." }
                    cache[url] = clock.millis() to image
                    while (cache.size > 16 || cache.values.sumOf { it.second.size } > 12 * 1_048_576) cache.remove(cache.keys.first())
                }
            }
            image
        }
    }

    private suspend fun read(url: String): ByteArray? = try {
        client.prepareGet(url) {
            header(HttpHeaders.Accept, "image/png,image/jpeg,image/webp")
            timeout {
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 5_000
            }
        }.execute { response ->
            if (response.status.value != 200 || response.headers[HttpHeaders.ContentType]?.substringBefore(';') !in setOf("image/png", "image/jpeg", "image/webp")) return@execute null
            if ((response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > MAX_BYTES) return@execute null
            val input = response.bodyAsChannel()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.readAvailable(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_BYTES) return@execute null
                output.write(buffer, 0, count)
            }
            output.toByteArray().takeIf(::validImage)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val MAX_BYTES = 3 * 1_048_576

        internal fun validImage(bytes: ByteArray): Boolean = validPngImage(bytes) ||
            (bytes.size >= 4 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte() && bytes[2] == (-1).toByte()) ||
            (bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) && bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()))

        private fun validPngImage(bytes: ByteArray): Boolean {
            if (bytes.size < 24 || !bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) return false
            if (!bytes.copyOfRange(12, 16).contentEquals("IHDR".toByteArray())) return false
            val dimensions = java.nio.ByteBuffer.wrap(bytes, 16, 8)
            return dimensions.int in 1..16_384 && dimensions.int in 1..16_384
        }
    }
}
