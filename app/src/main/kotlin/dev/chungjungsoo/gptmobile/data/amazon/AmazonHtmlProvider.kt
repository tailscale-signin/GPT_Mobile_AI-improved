package dev.chungjungsoo.gptmobile.data.amazon

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class AmazonReadContext(val timeoutSeconds: Int, val dailyLimit: Int, val isAllowed: suspend () -> Boolean)

interface AmazonProvider {
    suspend fun search(request: AmazonSearchRequest, context: AmazonReadContext): AmazonFetchResult
    suspend fun products(request: AmazonProductRequest, context: AmazonReadContext): AmazonFetchResult
}

/** Dedicated no-cookie, no-credential, no-log client; only sanitized product facts leave this provider. */
@Singleton
class AmazonHtmlProvider internal constructor(
    private val client: HttpClient,
    private val budget: AmazonRequestBudget,
    private val clock: Clock = Clock.systemUTC()
) : AmazonProvider {
    @Inject constructor(budget: AmazonRequestBudget) : this(createClient(OkHttp), budget)

    override suspend fun search(request: AmazonSearchRequest, context: AmazonReadContext): AmazonFetchResult = bounded(context) {
        val url = "https://www.${request.marketplace.domain}/s?k=${URLEncoder.encode(request.query, "UTF-8")}"
        val html = page(url, request.marketplace, context)
        withContext(Dispatchers.Default) { AmazonHtmlParser.search(html, request, clock.instant()) }
    }

    override suspend fun products(request: AmazonProductRequest, context: AmazonReadContext): AmazonFetchResult = bounded(context) {
        val products = mutableListOf<AmazonProductObservation>()
        val errors = mutableListOf<AmazonItemFailure>()
        var pages = 0
        for (asin in request.asins) {
            try {
                val html = page(requireNotNull(AmazonProducts.productUrl(request.marketplace.domain, asin)), request.marketplace, context)
                pages++
                val result = withContext(Dispatchers.Default) { AmazonHtmlParser.product(html, asin, request.marketplace, clock.instant()) }
                products += result.products
                errors += result.errors
            } catch (failure: AmazonReadException) {
                if (failure.code == AmazonReadError.PLUGIN_DISABLED) throw failure
                errors += AmazonItemFailure(failure.code, failure.message.orEmpty(), asin)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                errors += AmazonItemFailure(AmazonReadError.NETWORK_ERROR, "The Amazon product lookup failed; no facts were verified for this item.", asin)
            }
        }
        AmazonFetchResult(products, errors, pages)
    }

    private suspend fun page(initial: String, market: AmazonFreeMarket, context: AmazonReadContext): String {
        var next = initial
        repeat(4) { redirect ->
            if (!validPageUrl(next, market)) throw AmazonReadException(AmazonReadError.PARSE_CHANGED, "Amazon redirected outside the supported public product pages.")
            val target = next
            val result = budget.request(market, context.dailyLimit, context.isAllowed) {
                client.prepareGet(target) {
                    header(HttpHeaders.Accept, "text/html")
                    header(HttpHeaders.AcceptLanguage, market.language)
                    header(HttpHeaders.UserAgent, "GPT-Mobile-Amazon-Research/0.1")
                }.execute { response ->
                    val status = response.status.value
                    when {
                        status == 429 -> throw AmazonReadException(AmazonReadError.RATE_LIMITED, "Amazon rate-limited this lookup.", retryAfter(response.headers[HttpHeaders.RetryAfter]))
                        status == 401 || status == 403 -> throw AmazonReadException(AmazonReadError.CHALLENGE_REQUIRED, "Amazon blocked this lookup; login and challenges are not supported.")
                        status in 300..399 -> {
                            val location = response.headers[HttpHeaders.Location] ?: throw AmazonReadException(AmazonReadError.NETWORK_ERROR, "Amazon returned a redirect without a destination.")
                            PageResult(redirect = runCatching { URI(target).resolve(location).toString() }.getOrElse { throw AmazonReadException(AmazonReadError.PARSE_CHANGED, "Amazon returned an invalid redirect.") })
                        }
                        status != 200 -> throw AmazonReadException(AmazonReadError.NETWORK_ERROR, "Amazon did not complete this lookup (HTTP $status).")
                        else -> {
                            if (response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase() !in setOf("text/html", "application/xhtml+xml")) {
                                throw AmazonReadException(AmazonReadError.PARSE_CHANGED, "Amazon did not return an HTML product page.")
                            }
                            val channel = response.bodyAsChannel()
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8_192)
                            while (true) {
                                val count = channel.readAvailable(buffer)
                                if (count < 0) break
                                if (output.size() + count > MAX_HTML_BYTES) throw AmazonReadException(AmazonReadError.RESPONSE_TOO_LARGE, "Amazon's page exceeded the 2 MiB lookup limit.")
                                output.write(buffer, 0, count)
                            }
                            val html = output.toByteArray().toString(Charsets.UTF_8)
                            withContext(Dispatchers.Default) { AmazonHtmlParser.checkForChallenge(html) }
                            PageResult(html = html)
                        }
                    }
                }
            }
            if (!context.isAllowed()) throw AmazonReadException(AmazonReadError.PLUGIN_DISABLED, "Amazon Research Free was disabled during this lookup.")
            result.html?.let { return it }
            if (redirect == 3) throw AmazonReadException(AmazonReadError.PARSE_CHANGED, "Amazon exceeded the three-redirect limit.")
            next = requireNotNull(result.redirect)
        }
        error("Unreachable redirect state")
    }

    private suspend fun <T> bounded(context: AmazonReadContext, block: suspend () -> T): T = try {
        withTimeout(context.timeoutSeconds.coerceIn(5, 120) * 1000L) { block() }
    } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw AmazonReadException(AmazonReadError.TIMEOUT, "The Amazon lookup timed out. No automatic retry was sent.")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: AmazonReadException) {
        throw failure
    } catch (_: Exception) {
        throw AmazonReadException(AmazonReadError.NETWORK_ERROR, "The Amazon lookup failed. No product facts were verified for this request.")
    }

    private fun retryAfter(raw: String?): Long? = raw?.toLongOrNull()?.takeIf { it in 1..86_400 }?.times(1000)
        ?: raw?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - clock.millis() }.getOrNull()?.takeIf { delay -> delay > 0 } }

    private data class PageResult(val html: String? = null, val redirect: String? = null)

    companion object {
        const val MAX_HTML_BYTES = 2 * 1024 * 1024

        internal fun validPageUrl(raw: String, market: AmazonFreeMarket): Boolean = runCatching {
            val uri = URI(raw)
            uri.scheme == "https" &&
                uri.host in setOf(market.domain, "www.${market.domain}") &&
                uri.port in setOf(-1, 443) &&
                uri.userInfo == null &&
                uri.fragment == null &&
                (uri.path == "/s" || Regex("/(?:dp|gp/product)/[A-Z0-9]{10}/?").matches(uri.path.orEmpty()))
        }.getOrDefault(false)

        internal fun createClient(engine: HttpClientEngineFactory<*>) = HttpClient(engine) {
            expectSuccess = false
            followRedirects = false
            if (engine == OkHttp) {
                engine {
                    (this as? OkHttpConfig)?.config {
                        retryOnConnectionFailure(false)
                        followRedirects(false)
                        followSslRedirects(false)
                    }
                }
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 45_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 15_000
            }
            install(ContentEncoding) {
                gzip()
                deflate()
            }
        }
    }
}
