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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AmazonPublicHistory(
    val provider: String,
    val sourceUrl: String,
    val chartUrl: String,
    val checkedAt: Long,
    val png: ByteArray? = null,
    val notice: String? = null
) {
    fun toJson() = buildJsonObject {
        put("provider", provider)
        put("sourceUrl", sourceUrl)
        put("chartUrl", chartUrl)
        put("status", if (png == null) "unavailable" else "available")
        put("checkedAt", checkedAt)
        put("dataType", "provider_chart")
        put("numericSeriesAvailable", false)
        notice?.let { put("notice", it) }
    }
}

/** Public chart reads have no credentials, cookies, redirects, scraped numeric estimates or fabricated data. */
@Singleton
class AmazonPublicHistoryProvider internal constructor(private val client: HttpClient, private val clock: Clock = Clock.systemUTC()) {
    @Inject constructor() : this(
        HttpClient(OkHttp) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout)
        }
    )

    private val mutex = Mutex()
    private val cache = linkedMapOf<String, AmazonPublicHistory>()

    suspend fun fetch(domain: String, rawAsin: String, allowed: suspend () -> Boolean): AmazonPublicHistory = mutex.withLock {
        val asin = AmazonProducts.asin(rawAsin)?.takeIf { rawAsin.length == 10 } ?: error("Invalid ASIN.")
        require(domain in AmazonProducts.marketplaces)
        check(allowed()) { "Amazon history permission is disabled." }
        val key = "$domain/$asin"
        val now = clock.millis()
        cache[key]?.takeIf { now - it.checkedAt in 0..if (it.png == null) 60_000L else 90 * 60_000L }?.let { return@withLock it }
        val tld = domain.removePrefix("amazon.")
        val keepa = "https://graph.keepa.com/pricehistory.png?asin=$asin&domain=$tld&amazon=1&new=1&used=0&range=365&width=900&height=320"
        val region = when (domain) {
            "amazon.com" -> "us"
            "amazon.co.uk" -> "uk"
            "amazon.ca" -> "ca"
            "amazon.co.jp" -> "jp"
            else -> tld
        }
        val candidates = buildList {
            add(AmazonPublicHistory("Keepa", "https://keepa.com/#!product/${keepaDomain(domain)}-$asin", keepa, now))
            if (region in setOf("us", "uk", "ca", "de", "fr", "it", "es", "jp")) {
                add(AmazonPublicHistory("camelcamelcamel", "https://$region.camelcamelcamel.com/product/$asin", "https://charts.camelcamelcamel.com/$region/$asin/amazon-new-used.png?w=900&h=320&legend=1&tp=all&zero=0", now))
            }
        }
        var result = candidates.first().copy(notice = "Public history is unavailable for this product. Open the provider or use the local observations below.")
        for (candidate in candidates) {
            check(allowed()) { "Amazon history permission was revoked." }
            val bytes = try {
                withTimeoutOrNull(8_000) {
                    client.prepareGet(candidate.chartUrl) {
                        header(HttpHeaders.Accept, "image/png")
                        timeout {
                            requestTimeoutMillis = 7_000
                            connectTimeoutMillis = 5_000
                            socketTimeoutMillis = 5_000
                        }
                    }.execute { response ->
                        if (response.status.value != 200 || response.headers[HttpHeaders.ContentType]?.substringBefore(';') != "image/png") return@execute null
                        if ((response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > MAX_BYTES) return@execute null
                        val stream = response.bodyAsChannel()
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.readAvailable(buffer)
                            if (count < 0) break
                            if (output.size() + count > MAX_BYTES) return@execute null
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray().takeIf(::validPng)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            check(allowed()) { "Amazon history permission was revoked." }
            if (bytes != null) {
                result = candidate.copy(png = bytes)
                break
            }
        }
        if (cache.size >= 8) cache.remove(cache.keys.first())
        cache[key] = result
        result
    }

    companion object {
        private const val MAX_BYTES = 1_048_576
        internal fun validPng(bytes: ByteArray): Boolean {
            if (bytes.size < 24 || !bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) return false
            if (!bytes.copyOfRange(12, 16).contentEquals("IHDR".toByteArray())) return false
            val header = java.nio.ByteBuffer.wrap(bytes, 16, 8)
            return header.int in 1..2000 && header.int in 1..1000
        }
        private fun keepaDomain(domain: String) = when (domain) {
            "amazon.com" -> 1
            "amazon.co.uk" -> 2
            "amazon.de" -> 3
            "amazon.fr" -> 4
            "amazon.co.jp" -> 5
            "amazon.ca" -> 6
            "amazon.it" -> 8
            "amazon.es" -> 9
            "amazon.in" -> 10
            "amazon.com.mx" -> 11
            "amazon.com.br" -> 12
            "amazon.com.au" -> 13
            else -> 1
        }
    }
}
