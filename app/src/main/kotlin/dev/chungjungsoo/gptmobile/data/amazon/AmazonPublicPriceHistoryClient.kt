package dev.chungjungsoo.gptmobile.data.amazon

import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AmazonPublicHistoryReference(val marketplace: String, val asin: String, val rangeDays: Int, val chartUrl: String, val sourceUrl: String) {
    fun toJson() = buildJsonObject {
        put("provider", "Keepa")
        put("marketplace", marketplace)
        put("asin", asin)
        put("rangeDays", rangeDays)
        put("chartUrl", chartUrl)
        put("url", sourceUrl)
        put("format", "provider_price_history_chart")
        put("notice", "Public chart, not a numeric time-series API. Read the chart for coverage; missing history must not be inferred. Amazon and marketplace-new price series can represent different offers.")
    }
}

data class AmazonPublicHistoryChart(val reference: AmazonPublicHistoryReference, val png: ByteArray, val retrievedAt: Instant)

/** Public Keepa charts require no account/key. Never send provider credentials or crawl challenge pages. */
@Singleton
class AmazonPublicPriceHistoryClient private constructor(private val clientProvider: () -> HttpClient) {
    @Inject constructor(networkClient: NetworkClient) : this({ networkClient() })
    internal constructor(client: HttpClient) : this({ client })
    private val cache = ConcurrentHashMap<String, AmazonPublicHistoryChart>()
    private val locks = Array(16) { Mutex() }

    suspend fun chart(marketplace: String, asin: String, rangeDays: Int = 365, allowed: suspend () -> Boolean = { true }): AmazonPublicHistoryChart {
        val reference = reference(marketplace, asin, rangeDays) ?: throw AmazonProviderException("Public price history is unavailable for this marketplace or ASIN.")
        val key = reference.chartUrl
        return locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            if (!allowed()) throw AmazonProviderException("Enable an Amazon plugin and remote tools for this profile to retrieve price history.")
            cache[key]?.takeIf { Instant.now().toEpochMilli() - it.retrievedAt.toEpochMilli() in 0..CACHE_MS }?.let { return@withLock it }
            val client = clientProvider().config { followRedirects = false }
            try {
                val response = client.get(reference.chartUrl) { timeout { requestTimeoutMillis = 20_000 } }
                val channel = response.bodyAsChannel()
                val output = ByteArrayOutputStream()
                try {
                    if (response.status.value !in 200..299 || response.headers["Content-Type"]?.substringBefore(';')?.trim() != "image/png") {
                        throw AmazonProviderException("The public price-history provider is unavailable (HTTP ${response.status.value}).")
                    }
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = channel.readAvailable(buffer, 0, buffer.size)
                        if (count < 0) break
                        if (output.size() + count > MAX_BYTES) throw AmazonProviderException("The public chart exceeded the size limit.")
                        output.write(buffer, 0, count)
                    }
                } finally {
                    channel.cancel(null)
                }
                val bytes = output.toByteArray()
                if (!validChart(bytes)) throw AmazonProviderException("The provider did not return a supported price-history chart.")
                if (!allowed()) throw AmazonProviderException("Amazon access was revoked; the external chart was withheld.")
                val result = AmazonPublicHistoryChart(reference, bytes, Instant.now())
                if (cache.size >= 12) cache.entries.minByOrNull { it.value.retrievedAt }?.key?.let(cache::remove)
                cache[key] = result
                result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: AmazonProviderException) {
                throw failure
            } catch (_: Exception) {
                throw AmazonProviderException("Could not load public price history. Check connectivity or open the provider link.")
            } finally {
                client.close()
            }
        }
    }

    companion object {
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val CACHE_MS = 90 * 60 * 1000L
        private val domains = mapOf("amazon.com" to 1, "amazon.co.uk" to 2, "amazon.de" to 3, "amazon.fr" to 4, "amazon.co.jp" to 5, "amazon.ca" to 6, "amazon.it" to 8, "amazon.es" to 9, "amazon.in" to 10, "amazon.com.mx" to 11)

        fun reference(marketplace: String, asin: String, rangeDays: Int = 365): AmazonPublicHistoryReference? {
            val market = AmazonProducts.marketplace(marketplace) ?: return null
            val domain = domains[market] ?: return null
            val id = AmazonProducts.asin(asin) ?: return null
            if (rangeDays !in setOf(31, 90, 365)) return null
            return AmazonPublicHistoryReference(market, id, rangeDays, "https://graph.keepa.com/pricehistory.png?asin=$id&domain=${market.removePrefix("amazon.")}&amazon=1&new=1&used=0&salesrank=0&range=$rangeDays&width=800&height=400", "https://keepa.com/#!product/$domain-$id")
        }

        internal fun validChart(bytes: ByteArray): Boolean {
            if (bytes.size < 24 || !bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) return false
            fun dimension(offset: Int) = java.nio.ByteBuffer.wrap(bytes, offset, 4).int
            return bytes.copyOfRange(12, 16).decodeToString() == "IHDR" && dimension(16) == 800 && dimension(20) == 400
        }
    }
}
